# 排查取证（崩溃 / 挂死 / 日志）

出问题、**症状还没对上已知坑**时，用这篇取证据。已知答案的条目在
[`playback-invariants.md`](./playback-invariants.md)（外部库行为依据与判据），
先去那边对。

> **DO：** 命令、路径与数值以当前代码 + 实跑为准。
> **DO：** 结果**落盘再读** —— Windows 上 JVM 按控制台代码页编解码，中文日志直接读会乱码；
> PowerShell 的 stdout 也不回显。

## 崩溃

看 `hs_err_pid*.log` 的 `Problematic frame` + `Java frames`
（直接指到 native 函数与上层 Kotlin 调用）。

## 挂死

先看 log / 结果目录 mtime 有没有推进，再看 worker 的 CPU 时间是否在涨
（不涨 = 阻塞而非计算），再用 `jstack.exe <pid>` 抓栈（**重定向到文件再读**）。

**先拿到 test worker 的 pid**：`jps -l` 只列出了 GradleDaemon、**没列出** test worker
（；原因未核实，别据此断言「没有 worker」）。改从进程表按命令行找：

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Select-Object ProcessId,CommandLine | Format-List | Out-File -Encoding utf8 <落盘路径>
```

认 `CommandLine` 里的 `Gradle Test Executor` 那条。⚠️ PowerShell 的 stdout 不回显，必须落盘再读。
杀掉卡住的 worker 后 Gradle 会补写部分 XML，卡住那条带 `<skipped/>`、`time` 等于挂死时长。

### 判「阻塞在哪」：先看**对端**线程，再看主线程

- **对端在跑**（栈里停在 native 或你的业务帧）→ 真卡在那一层。
- **对端空转**（park 在 `LinkedBlockingQueue.take` 这类地方、队列里没任务）→ 对端**闲着**，
  说明主线程在等一个**永远不会完成**的通知：`Deferred` / `CompletableDeferred` 没人 complete，
  或 `await` 的目标调度早已结束。**别据此误判成 native 卡死。**
  已知成因之一见 [`playback-invariants.md`](./playback-invariants.md) 第六节
  （`CompletableDeferred(null)` 的重载解析）。

### 分步打点：一次把范围缩到**具体语句**

⚠️ **挂起的协程不在任何线程栈上**：jstack 只能证明「没有任务在跑」（所有业务线程都 idle），
**给不出挂起点**。所以 `withTimeoutOrNull` 包住每个可疑调用、把「卡住」变成可读返回值，是唯一
直接的办法（`pause` 正常返回、`stepBack` 超时 —— 线程栈里两者长得一模一样）。

```kotlin
fun mark(s: String) { File(".../dbg.txt").appendText("${System.currentTimeMillis()} $s\n") }
mark("BOOT")
val ctx = QuickJS()
mark("created")
val r1 = runBlocking { withTimeoutOrNull(5000) { ctx.closeDeferred().await() } }
mark("close1 -> $r1")          // 超时打 null；正常打真值
```

```
close1 -> []            ← 立即返回
close2 -> null          ← 隔了 5s ⇒ 这次超时了，问题在第二条
```

⚠️ 打点用例**用完即删**，别把诊断代码留在仓库里。

### `pause()` 返回慢**不等于**挂死

排查取包超时的时候容易被这里绊一下：`FFPlayer.pause` 要等 `playingJob.join()`，而那个 job 跑的是
整轮取包循环 ⇒ **pause 的耗时等于「当前那个循环轮次走到下一个挂起点」的时间**，不是常数。

- **机制上界 = 一个轮次**，不是某个超时值：轮次里最长的那次挂起是取包的 `withTimeout(100)`，
  超时分支不再额外 `delay`。
- **下界也不是「毫秒级」**：同一轮也可能停在回压分支的 `delay(1)` 上。
- ⇒ 落在哪一格取决于探测那一刻，所以**是个跨度不是定值**。

判据：**几十毫秒量级是正常的**，要怀疑挂死得看到**秒级**（`closeAsync` 那种 5 s 超时才对应自锁，
成因见 [`playback-invariants.md`](./playback-invariants.md) 第六节「作废预读通道用 `cancel()`」）。

### 数用例数别信控制台

读 `shared/build/test-results/jvmTest/*.xml` 的 `tests` / `failures` / `errors` / `skipped`
属性 —— **同一条 gradle 命令连跑会全 `UP-TO-DATE`（1 秒）**，XML 还是上一次的，控制台
一个字都不打，用例数会被上一轮的结果骗过。`mtime` 也要一并核对。

## 原生日志

`cxx/webview/webview.cpp` 的日志**一律写 stderr，没有开关** —— 消费端只有 Gradle 起的
测试与桌面端进程，两个都有 stderr。测试进程那份在 Gradle 的测试输出里，桌面端那份在
`./gradlew :desktopApp:run` 的控制台上。

唯一被透传给跑起来的 App 的环境变量是 `MSYS2_BIN`，那是**构建期** `buildJni`→`exec.cmd`
桥要用的，**不是日志开关**，见 [`cxx/AGENTS.md`](../../cxx/AGENTS.md) 的「原生构建」。

## Chromium 日志

Chromium 自己的日志才是引擎级问题唯一说得清的东西：设
`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--enable-logging --v=1`（必须在创建 environment 之前），
日志落在 user data folder 的 `EBWebView/chrome_debug.log`。
想试别的浏览器参数用真 JVM 宿主，`jvmTest` 认 `WEBVIEW2_ARGS`（**只对 jvmTest 生效**，
`:desktopApp:run` 不吃它），别用临时探针。

**快速信号**：WebView2 起来了 → stdout 出现 `Failed to unregister class Chrome_WidgetWin_0`。

## 量「运行中的 App」的内存

App 由 `hotRun` 起时，进程命令行里带 `-Dcompose.reload.argfile=...` 与业务包名，按它找 pid：

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*soko.ekibun.acg*' } |
  Select-Object ProcessId, @{n='WS_MB';e={[int]($_.WorkingSetSize/1MB)}}
```

- **用哪个 jcmd**：App 跑在 Gradle 给的 JDK 上（本机是 `.gradle/jdks/jetbrains_s_r_o_-25-*`），
  拿同版本或更新的 `jcmd` 去 attach，否则可能 `AttachNotSupportedException`。
- `jcmd <pid> GC.heap_info` → **Java 堆** committed/used（跟 RSS 分开看，这是第一刀）。
- `jcmd <pid> GC.class_histogram` → 找 `CleanableImpl` / `Managed$CleanerThunk`：**数它们**就是在数
  「已失去 wrapper、等 Cleaner 回收的 skiko 对象」（每个 = 一块 native 内存）。
- 进程 RSS（`WorkingSet64`/`PrivateMemorySize64`）**采样成序列**看形状：
  **锯齿**（涨→一次性掉，周期十秒级）= 靠 GC/Cleaner 回收的堆积；**单调涨** = 真泄漏。
- `jcmd <pid> GC.run` 后**立刻**（<200 ms）采样才有意义：间隔久了会被新分配盖掉。
  注意：**回收 ≠ RSS 下降** —— native 的 free 空间未必还给 OS。
