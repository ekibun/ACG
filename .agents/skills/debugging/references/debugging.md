# 调试（崩溃 / 挂死 / 日志）

> **待重新核定**：排查步骤来自历史经验，具体命令 / 路径可能随环境与代码变化，以当前代码 + 实跑为准。

## 崩溃

看 `hs_err_pid*.log` 的 `Problematic frame` + `Java frames`
（直接指到 native 函数与上层 Kotlin 调用）。

## 挂死

先看 log / 结果目录 mtime 有没有推进，再看 worker 的 CPU 时间是否在涨
（不涨 = 阻塞而非计算），再用 `jstack.exe <pid>` 抓栈（**重定向到文件再读**）。
杀掉卡住的 worker 后 Gradle 会补写部分 XML，卡住那条带 `<skipped/>`、`time` 等于挂死时长。

## 原生日志开关

只有下面这些会被透传给跑起来的 App：

- `ACG_WEBVIEW_DEBUG=1` 走 stderr
- `ACG_WEBVIEW_LOG=<路径>` 追加到文件

（`MSYS2_BIN` 是**构建期** `buildJni`→`exec.cmd` 桥要的环境变量，不是日志开关，见 `AGENTS.md` §4。）

## Chromium 日志

Chromium 自己的日志才是引擎级问题唯一说得清的东西：设
`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--enable-logging --v=1`（必须在创建 environment 之前），
日志落在 user data folder 的 `EBWebView/chrome_debug.log`。
想试别的浏览器参数用真 JVM 宿主，`jvmTest` 认 `ACG_WEBVIEW2_ARGS`（**只对 jvmTest 生效**，
`:desktopApp:run` 不吃它），别用临时探针。

## 快速信号

WebView2 起来了：stdout 出现 `Failed to unregister class Chrome_WidgetWin_0`。
