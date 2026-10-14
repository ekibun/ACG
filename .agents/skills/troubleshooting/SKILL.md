---
name: troubleshooting
description: Use when 程序崩溃、测试挂死、现象解释不通或疑似"不报错只失效"，或动不熟悉的模块之前扫禁区。
---

# 项目陷阱与排查

**出了事先按症状检索**：先查是不是已知的坑，对不上再走取证步骤。

| 症状长什么样 | 读哪份 |
|---|---|
| 静默失效 / 编译通过但行为错 / 已知根因的那类坑 | [`references/silent-failures.md`](./references/silent-failures.md) |
| 崩溃、挂死、还没查出原因，需要取证据（日志 / 栈 / 内存） | [`references/debugging.md`](./references/debugging.md) |
| 桌面端 WebView 现象：白屏、GPU/浏览器进程崩溃、焦点与键盘、JAWT 嵌入、Cookie 生命周期 | [`references/webview2-windows.md`](./references/webview2-windows.md) |
| 不变量与"不要做 X"的清单（动手前核对当前代码） | [`references/forbidden-zones.md`](./references/forbidden-zones.md) |

几条 Read 顺序建议：

- **挂住 / 超时**，先看 `silent-failures.md` 第 27 条是不是 Deferred 没人 complete；
  没有再去 `debugging.md` 用 `withTimeoutOrNull` 逐语句打点（挂起的协程不在任何线程栈上，jstack 给不出挂起点）。
- **要改某个模块之前**，先扫 `forbidden-zones.md` —— 那是唯一真正"动手前"该读的清单。

其中几条在别处有更权威的正本，**出现分歧时以正本为准**，不要在这里重复维护：

| 主题 | 正本 |
|---|---|
| JNI 符号导出、加载方式差异、MSYS2 与工具链 | [`cxx/AGENTS.md`](../../../cxx/AGENTS.md) |
| 构建 / 测试 / 验收闸门、dll 三处同步 | skill `build-and-test` |
| QuickJS 引用计数与所有权 | [`cxx/AGENTS.md`](../../../cxx/AGENTS.md) 的 quickjs 节 |
| WebView 接口的设计规格与已裁定项（改契约层前先看，别把已定的重新打开） | [`docs/design/webview-context-redesign.md`](../../../docs/design/webview-context-redesign.md) |
