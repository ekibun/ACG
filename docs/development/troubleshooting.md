# 项目陷阱与排查

**出了事先按症状检索**：先查是不是已知的坑，对不上再走取证步骤。

| 症状长什么样 | 读哪份 |
|---|---|
| 编译通过但行为错 / 改播放链路前核对不变量（外部库行为依据与判据） | [`playback-invariants.md`](./playback-invariants.md) |
| 崩溃、挂死、还没查出原因，需要取证据（日志 / 栈 / 内存） | [`debugging.md`](./debugging.md) |
| 桌面端 WebView 现象：白屏、GPU/浏览器进程崩溃、焦点与键盘、JAWT 嵌入、Cookie 生命周期 | [`webview2-windows.md`](./webview2-windows.md) |
| HTTP 流的读取与会话生命周期（seek 作废、白读推进、通道存量） | [`playback-invariants.md`](./playback-invariants.md) 第五节 |
| 不变量与"不要做 X"的清单（动手前核对当前代码） | [`cxx/AGENTS.md`](../../cxx/AGENTS.md) 的 JNI / webview / quickjs 各节，以及下面的正本表 |

几条 Read 顺序建议：

- **挂住 / 超时**，先看 `playback-invariants.md` 第六节「`CompletableDeferred(null)` 会挑错重载」
  是不是 Deferred 没人 complete；没有再去 `debugging.md` 用 `withTimeoutOrNull` 逐语句打点
  （挂起的协程不在任何线程栈上，jstack 给不出挂起点）。
- **要改某个模块之前**，先扫 [`cxx/AGENTS.md`](../../cxx/AGENTS.md) 对应库的硬约束，
  再按需读 `playback-invariants.md` 的对应节。

## 分工

排查按症状分流，**不要在这里重复维护**。出现分歧时以对应正本为准：

| 主题 | 正本 |
|---|---|
| 外部库（ffmpeg / kotlinx / ktor / skiko）行为依据、播放链路判据 | [`playback-invariants.md`](./playback-invariants.md) |
| HTTP 流的读取与会话生命周期（推演过程） | [`playback-invariants.md`](./playback-invariants.md) 第五节 |
| JNI 符号导出、加载方式差异、MSYS2 与工具链 | [`cxx/AGENTS.md`](../../cxx/AGENTS.md) |
| QuickJS 引用计数与所有权 | [`cxx/AGENTS.md`](../../cxx/AGENTS.md) 的 quickjs 节 |
| 构建 / 测试 / 验收闸门、dll 三处同步 | [build-and-test 技能](../../.agents/skills/build-and-test/SKILL.md) |
| WebView 接口的设计规格与已裁定项（改契约层前先看，别把已定的重新打开） | [`webview-context-redesign.md`](../design/webview-context-redesign.md) |
| 能力桥（`_binding`）形态与为什么不用反射 | [`quickjs-ability-bridge.md`](../design/quickjs-ability-bridge.md) |
