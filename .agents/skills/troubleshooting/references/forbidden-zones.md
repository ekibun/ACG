# 禁区（踩过的坑）

>

改代码时容易踩的坑、以及"不要做 X"的约束。这些是不变量，改动前先核对当前代码。

- **原生桥**：JNI 符号是**不带签名的名称导出**（`Java_soko_ekibun_web_Webview2Context_<name>`）。
  改了 native 方法签名却用着旧 dll **不会报错**，只会参数错位 → 改签名后务必确认 dll 已刷新。
- **QuickJS**：`init.js` 里的能力**一律内联**，动态 `import()` / `require()` 会让进程 `abort()`。
  引用所有权由 `Pointer` / `QuickJS` 的代码结构保证，硬约束见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的 quickjs 节。
- **桌面 WebView**：`initScript` / 文档级前置脚本**已整体删除**，`webview.cpp` 里已无任何脚本注入。
  要加只能走 `AddScriptToExecuteOnDocumentCreated`（桌面）/
  `androidx.webkit` 的 `addDocumentStartJavaScript`（Android）——
  `onPageStarted` 里补 `evaluateJavascript` **不构成**文档级保证。
- **键盘焦点全走原生链**：对根窗口线程做一次长期 `AttachThreadInput`（`cxx/webview/webview.cpp`），
  按键才会按前台线程的队列路由；不要恢复 DOM 焦点桥 / 脚本注入。焦点转发器必须**幂等**，
  否则会弄坏输入法；也不要在别人的进程里留下一个 attach。机制见
  [`webview2-windows.md`](./webview2-windows.md) §9。
- **可见页窗口必须是 AWT 的** `application { Window(...) }` —— 宿主靠 JAWT 从 AWT 组件取 HWND 挂子窗口。
  AWT 是重型组件、永远画在 Compose 之上，所以 `WebView` 的 `content` 覆盖层在 Windows 上会被盖住。
- **通用**：不要顺手重构无关代码、不要顺手统一命名/格式、不要把 alpha 依赖降级。
- 测试偶发失败先重跑确认不是环境问题，再定性 —— **别只靠重跑掩盖**。历史上
  `QuickJSTest > objectWithVariousTagsRoundTrips` 就是真实竞态（两个线程并发进同一个
  `JSRuntime`），已按「native 访问只能在 JS 线程上」修掉；症状清单见
  [silent-failures.md](./silent-failures.md)。
