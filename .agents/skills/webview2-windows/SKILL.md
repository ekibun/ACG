---
name: webview2-windows
description: >-
  排查与改造 Windows 桌面端自研 WebView2 宿主：白屏、ProcessFailed 报 BROWSER_PROCESS_EXITED
  （0x80000003）、导航不触发 NavigationStarting、cookie 不跨视图存活、退出时挂死、
  渲染与点击正常但键盘无响应、IME 输入法不断被取消、嵌在 AWT 或 Compose Desktop 窗口里不可见或尺寸错误。
  也覆盖 Chromium 自身日志的位置、哪些浏览器参数真的有用、如何在不重编的情况下开关参数、
  HWND 重挂到 JAWT 组件（SetParent + WS_CHILD）、以及键盘焦点全走原生链的边界。
  Use when 调试或改动 cxx/webview 与桌面端 WebView 宿主、处理上述任一现象时。
agent_created: true
---

# WebView2 / Windows 宿主

完整排查手册在 [`references/webview2-windows.md`](./references/webview2-windows.md)，**按现象查对应小节，
不要整篇读进来**。

三条先记住的硬边界（改之前不知道就会踩）：

- `initScript` / 文档级前置脚本**已整体删除**，`webview.cpp` 里没有任何脚本注入。
  要加只能走 `AddScriptToExecuteOnDocumentCreated`（桌面）或 `androidx.webkit` 的
  `addDocumentStartJavaScript`（Android）；`onPageStarted` 里补 `evaluateJavascript` **不构成**
  文档级保证。理由见手册。
- **键盘焦点全走原生链**（`AttachThreadInput` + `MoveFocus`），不要恢复 DOM 焦点桥 / 脚本注入；
  `requestWebViewFocus` 的幂等闸门不能动。
- 可见页窗口**必须是 AWT 的** `application { Window(...) }` —— 宿主靠 JAWT 从 AWT 组件取 HWND 挂子窗口。
  AWT 是重型组件、永远画在 Compose 之上，所以 `AcgWebView` 的 `content` 覆盖层在 Windows 上会被盖住。

**不要换第三方 KMP WebView 库**：我们的宿主依赖 `WebResourceRequested` 子资源拦截、自定义
`AdditionalBrowserArguments` 和 CDP 访问，现成的封装把这些钩子挂在导航事件上，换过去能力全丢。
