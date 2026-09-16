---
name: project-traps
description: >-
  本项目历史上真实踩过的禁区：JNI 符号是不带签名的名称导出（改 Kotlin 侧签名却用旧 dll 只会参数错位、
  不报错）、init.js 的能力必须内联、桌面 WebView 的脚本注入已整体删除、键盘焦点全走原生链、
  可见页窗口必须是 AWT 的、不要顺手重构无关代码或把刻意钉住的 alpha 依赖降级。
  Use when 要动不熟悉的模块、或准备改上面这些区域之前扫一眼。
agent_created: true
---

# 项目禁区

清单在 [`references/forbidden-zones.md`](./references/forbidden-zones.md)。

**这份清单是线索，不是结论。** 它来自历史踩坑记录，部分条目依赖当时的实现细节、可能已随代码演进失效
（文件头已标「待重新核定」）。与当前代码冲突时**以代码 + 实跑为准**，并顺手把该条改对。
逐条重新核定是一项独立待办，见仓库根 [`TODO.md`](../../../TODO.md)。

其中几条在别处有更权威的正本，**出现分歧时以正本为准**，不要在这里重复维护：

| 主题 | 正本 |
|---|---|
| JNI 符号导出、dll 三处同步、加载方式差异 | [`cxx/AGENTS.md`](../../../cxx/AGENTS.md) |
| QuickJS 引用计数与所有权 | skill `quickjs-ownership` |
| 桌面 WebView 脚本注入 / 键盘焦点 / AWT 窗口 | skill `webview2-windows` |
| 静默失败的集中索引 | 根 [`AGENTS.md`](../../../AGENTS.md) §1 |
