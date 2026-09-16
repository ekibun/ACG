---
name: coding-style
description: >-
  写代码时遵循的风格基线：**每级 2 空格缩进**（四种文件统一）、**注释用中文且按统一格式**、
  Kotlin 官方编码约定、Compose API 指南（命名、Modifier 位次、参数顺序、状态提升）、
  Google C++ 风格指南（命名、不用异常），以及本项目叠加的少量约定（包名、平台实现文件命名、
  Gradle 任务注册写法）。
  Use when 新增或修改 shared/ 下的 Kotlin 与 Compose 代码、cxx/ 下自研的 C++，或任何 build.gradle.kts 时。
agent_created: true
---

# 编码风格

**这是目标基线，不是对现状的描述。** 缩进、通配符 import、尾随逗号这几项**已由工具执行**
（正本是根 `.editorconfig` 与 `.clang-format`，跑 `ktlintFormat` / `clang-format -i` 即可），
且 **2026-09-16 已对全仓跑过一次**（`webview.cpp` 的 4 空格遗留也是那次整篇重排掉的）；
只剩**注释中英混杂**。所以**不要拿「周围代码就是这么写的」当理由沿用**：新代码按本基线写。
格式化工具自身的两个坑 —— `ktlintFormat` 会误删/误加 import、compose-rules 的
`preview-public-check` 对本项目是误报 —— 见 [`references/kotlin.md`](./references/kotlin.md)。

## 适用范围：只管我们自己的代码

**在范围内**：`shared/`、`androidApp/`、`desktopApp/` 下的 Kotlin 与 Compose；
`cxx/{webview,quickjs,ffmpeg}/` 下自研的 `.cpp`（就三个文件：`webview.cpp` / `quickjs.cpp` /
`ffmpeg.cpp`）；所有 `*.gradle.kts`。

**不在范围内** —— 风格不由我们决定，**更不要"顺手格式化"**：

- `cxx/ffmpeg/ffmpeg/`、`cxx/quickjs/quickjs/` —— 上游 **submodule**；
- `cxx/webview/sdk/` —— 微软 WebView2 SDK 的随附头文件（`WebView2.h` 一个文件就六万多行）。

**报风格问题、跑格式化、写检查脚本之前，先确认目标文件落在哪一侧。**

## 缩进（四种文件同一条）

- **块缩进：每级 2 个空格，不用 tab**。Kotlin / Compose / C++ / Gradle KTS 一律如此 ——
  这条**覆盖** Kotlin 官方基线的 4 空格。
- **续行：比它所属的声明多缩进 4 个空格**（换行后的参数表、初始化列表、链式调用等）。
  续行量 ≠ 块缩进量，别把两者混成一条规则。
- **不做水平/竖排对齐**（Kotlin 官方也明确反对）。
- 存量：**已统一** —— 正本是根 `.editorconfig`（`indent_size = 2`、`continuation_indent_size = 4`）
  与根 `.clang-format`（`IndentWidth: 2`、`ContinuationIndentWidth: 4`），Kotlin 侧由 ktlint 的
  Gradle 插件执行、C++ 侧由 clang-format 执行。**别手工去凑**，改完跑一遍工具即可。

## 注释（四种文件同一条）

**中文 + 一律行注释 `//` + 文档注释用 `/** … */`**。写什么、不写什么、怎么标待办，
完整规则在 [`references/comments.md`](./references/comments.md) —— **只读那一份，别在这里找**。

按要改的东西查对应参考，**只读需要的那一份**：

| 改什么 | 读哪份 |
|---|---|
| `shared/**/*.kt` 的通用 Kotlin 写法 | [`references/kotlin.md`](./references/kotlin.md) |
| `@Composable` 函数、状态、Modifier | [`references/compose.md`](./references/compose.md) |
| `cxx/**` 自研 C++（`webview/` `quickjs/` `ffmpeg/`） | [`references/cpp.md`](./references/cpp.md) |
| `*.gradle.kts`、`gradle/libs.versions.toml` | [`references/build.md`](./references/build.md) |

每份参考里，**「基线」= 官方/社区规范原文的规则，「本项目」= 本仓库叠加的决定**，
两者冲突时**以「本项目」为准**。两者都没覆盖的地方，优先照抄范围内最规范的那个文件，而不是自创。

本技能**只管风格**。硬约束（`commonMain` 不许出现平台符号、`expect`/`actual` 的粒度、
`audioFormat` 方向的含义、`engine` 类名是对外契约等）在根
[`AGENTS.md`](../../../AGENTS.md) 与 [`cxx/AGENTS.md`](../../../cxx/AGENTS.md)；构建 / 测试 / dll 同步见 skill `build-and-test`。此处不重复。
