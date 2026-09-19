# 禁区（踩过的坑）

> **待重新核定**：本文件内容来自历史踩坑记录，部分条目依赖当时的实现细节，可能已随代码演进失效。
> 改动前请以**当前代码 + 实跑**为准；发现某条已不对，直接改这里。

改代码时容易踩的坑、以及"不要做 X"的约束。每条都曾真实地踩过——但实现可能已变，下面只作参考。

- **原生桥**：JNI 符号是**不带签名的名称导出**（`Java_soko_ekibun_acg_web_NativeWebView_<name>`）。
  改了 native 方法签名却用着旧 dll **不会报错**，只会参数错位 → 改签名后务必确认 dll 已刷新。
- **QuickJS**：`init.js` 里的能力**一律内联**，动态 `import()` / `require()` 会让进程 `abort()`。
  引用所有权有明确模型，改动前读 [quickjs-reference-ownership.md](../../quickjs-ownership/references/quickjs-reference-ownership.md)。
- **桌面 WebView**：`initScript` / 文档级前置脚本**已整体删除**，`webview.cpp` 里已无任何脚本注入。
  要加只能走 `AddScriptToExecuteOnDocumentCreated`（桌面）/
  `androidx.webkit` 的 `addDocumentStartJavaScript`（Android）——
  `onPageStarted` 里补 `evaluateJavascript` **不构成**文档级保证。
- **键盘焦点全走原生链**（`AttachThreadInput` + `MoveFocus`），不要恢复 DOM 焦点桥 / 脚本注入；
  `requestWebViewFocus` 的幂等闸门不能动。
- **可见页窗口必须是 AWT 的** `application { Window(...) }` —— 宿主靠 JAWT 从 AWT 组件取 HWND 挂子窗口。
  AWT 是重型组件、永远画在 Compose 之上，所以 `AcgWebView` 的 `content` 覆盖层在 Windows 上会被盖住。
- **通用**：不要顺手重构无关代码、不要顺手统一命名/格式、不要把 alpha 依赖降级。
- 测试偶发失败先重跑确认不是环境问题，再定性 —— **别只靠重跑掩盖**。历史上
  `QuickJSTest > objectWithVariousTagsRoundTrips` 就是真实竞态（两个线程并发进同一个
  `JSRuntime`），已按「native 访问只能在 JS 线程上」修掉；症状清单见
  [silent-failures.md](./silent-failures.md)。
