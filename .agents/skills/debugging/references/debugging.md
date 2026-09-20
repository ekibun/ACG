# 调试（崩溃 / 挂死 / 日志）

> **待重新核定**：排查步骤来自历史经验，具体命令 / 路径可能随环境与代码变化，以当前代码 + 实跑为准。

## 崩溃

看 `hs_err_pid*.log` 的 `Problematic frame` + `Java frames`
（直接指到 native 函数与上层 Kotlin 调用）。

## 挂死

先看 log / 结果目录 mtime 有没有推进，再看 worker 的 CPU 时间是否在涨
（不涨 = 阻塞而非计算），再用 `jstack.exe <pid>` 抓栈（**重定向到文件再读**）。
杀掉卡住的 worker 后 Gradle 会补写部分 XML，卡住那条带 `<skipped/>`、`time` 等于挂死时长。

### 判「阻塞在哪」：先看**对端**线程，再看主线程（2026-09-20 实测）

- **对端在跑**（栈里停在 native 或你的业务帧）→ 真卡在那一层。
- **对端空转**（park 在 `LinkedBlockingQueue.take` 这类地方、队列里没任务）→ 对端**闲着**，
  说明主线程在等一个**永远不会完成**的通知：`Deferred` / `CompletableDeferred` 没人
  complete，或 `await` 的目标调度早已结束。**别据此误判成 native 卡死。**
  实测过一次：`CompletableDeferred(null)` 被重载解析挑到 `CompletableDeferred(parent: Job?)`
  那个重载，造出永不完成的 Deferred —— `closeIsIdempotentAcrossEntryPoints` 卡死 8 分钟，
  而 `quickjs` 线程正空转在 `take`。

### 分步打点：一次把范围缩到**具体语句**

挂死往往只发生在「第二条语句」上，而栈只给到函数名。按语句打点、并**用
`withTimeoutOrNull` 把「卡住」变成可读的返回值**，比反复 jstack 快得多：

```kotlin
fun mark(s: String) { File(".../dbg.txt").appendText("${System.currentTimeMillis()} $s\n") }
mark("BOOT")
val ctx = runBlocking { QuickJS.create() }
mark("created")
val r1 = runBlocking { withTimeoutOrNull(5000) { ctx.closeAndCollect().await() } }
mark("close1 -> $r1")          // 超时打 null；正常打真值
```

```
close1 -> []            ← 立即返回
close2 -> null          ← 隔了 5s ⇒ 这次超时了，问题在第二条
```

⚠️ 打点用例用完即删，别把诊断代码留在仓库里（手册「移植检查清单」第 7 条）。

### 数用例数别信控制台

读 `shared/build/test-results/jvmTest/*.xml` 的 `tests` / `failures` / `errors` / `skipped`
属性 —— **同一条 gradle 命令连跑会全 `UP-TO-DATE`（1 秒）**，XML 还是上一次的，控制台
一个字都不打，用例数会被上一轮的结果骗过。`mtime` 也要一并核对。


## 原生日志开关

只有下面这些会被透传给跑起来的 App：

- `ACG_WEBVIEW_DEBUG=1` 走 stderr
- `ACG_WEBVIEW_LOG=<路径>` 追加到文件

（`MSYS2_BIN` 是**构建期** `buildJni`→`exec.cmd` 桥要的环境变量，不是日志开关，见 skill `build-and-test` 的 `references/dll-sync.md`。）

## Chromium 日志

Chromium 自己的日志才是引擎级问题唯一说得清的东西：设
`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--enable-logging --v=1`（必须在创建 environment 之前），
日志落在 user data folder 的 `EBWebView/chrome_debug.log`。
想试别的浏览器参数用真 JVM 宿主，`jvmTest` 认 `ACG_WEBVIEW2_ARGS`（**只对 jvmTest 生效**，
`:desktopApp:run` 不吃它），别用临时探针。

## 快速信号

WebView2 起来了：stdout 出现 `Failed to unregister class Chrome_WidgetWin_0`。
