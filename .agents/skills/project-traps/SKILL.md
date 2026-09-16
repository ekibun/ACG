---
name: project-traps
description: >-
  本项目历史上真实踩过的坑：一类"不报错、只失效"的问题（改过 native 却用着旧 dll、漏拷 dll、
  改 soko.ekibun.acg.engine 下的类名、在 init.js 里 import()/require()、动 submodule、
  QuickJSTest 偶发竞态），以及禁区清单（桌面 WebView 的脚本注入已整体删除、键盘焦点全走原生链、
  可见页窗口必须是 AWT 的、不要顺手重构无关代码或把刻意钉住的 alpha 依赖降级）。
  Use when 要动不熟悉的模块、或准备改上面这些区域之前扫一眼。
agent_created: true
---

# 项目陷阱

| 要看什么 | 读哪份 |
|---|---|
| 一类"不报错、只失效"的问题（最容易误判成代码写错） | [`references/silent-failures.md`](./references/silent-failures.md) |
| 禁区清单（真实踩过的坑 + "不要做 X" 的约束） | [`references/forbidden-zones.md`](./references/forbidden-zones.md) |

**两份都是线索，不是结论。** 条目来自历史记录，部分依赖当时的实现细节、可能已随代码演进失效
（文件头已标「待重新核定」）。与当前代码冲突时**以代码 + 实跑为准**，并顺手把该条改对。
逐条重新核定是一项独立待办，见仓库根 [`TODO.md`](../../../TODO.md)。

其中几条在别处有更权威的正本，**出现分歧时以正本为准**，不要在这里重复维护：

| 主题 | 正本 |
|---|---|
| JNI 符号导出、加载方式差异、MSYS2 与工具链 | [`cxx/AGENTS.md`](../../../cxx/AGENTS.md) |
| 构建 / 测试 / 验收闸门、dll 三处同步 | skill `build-and-test` |
| QuickJS 引用计数与所有权 | skill `quickjs-ownership` |
| 桌面 WebView 脚本注入 / 键盘焦点 / AWT 窗口 | skill `webview2-windows` |
