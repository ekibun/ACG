---
name: debugging
description: >-
  本项目崩溃 / 挂死 / 开日志的排查路径：怎么看 hs_err_pid*.log 的 Problematic frame 与 Java frames、
  怎么区分阻塞与纯计算（看 worker 的 CPU 时间是否在涨）、jstack 抓栈、被强杀的 worker 在测试 XML 里
  长什么样、ACG_WEBVIEW_DEBUG 与 ACG_WEBVIEW_LOG 两个原生日志开关、以及 Chromium 自己的日志怎么开。
  Use when 程序崩溃、测试挂死、或需要打开原生日志 / Chromium 日志来定位问题时。
agent_created: true
---

# 调试（崩溃 / 挂死 / 日志）

排查步骤在 [`references/debugging.md`](./references/debugging.md)。

两点前置说明：

- 这份材料来自历史经验，命令与路径可能随环境或代码变化（文件头已标「待重新核定」）。
  与现状不符时以实跑为准，并顺手改对。
- **结果落盘再读**：Windows 上 JVM 按控制台代码页编解码，中文日志直接读会乱码。
  原生日志的开关是**构建/运行期环境变量**，不要和日志开关搞混（见 `references/debugging.md` 末尾）。
