---
name: coding-style
description: Use when 新增或修改 shared/ 下的 Kotlin 与 Compose 代码、cxx/ 下自研的 C++，或任何 build.gradle.kts 时。
---

# 编码风格

**格式由工具保证，本技能只管工具管不了、且根 `AGENTS.md` 没写的那些**。

- 缩进、续行、行长、import、换行等**格式**：正本在根 `.editorconfig`（Kotlin/KTS/Gradle）与
  `.clang-format`（C++），pre-commit 自动拦（`ktlintCheck` / `clang-format --dry-run`）。
  改格式口径改这两个文件，此处不复述取值。
- **注释用中文、标识符/路径/命令不翻译、注释简洁不复述代码**已在根 `AGENTS.md` §2.5，
  本技能不重复；下面只写它没覆盖的。
- 适用范围：只管**我们自己的代码**——`cxx/ffmpeg/ffmpeg/`、`cxx/quickjs/quickjs/`（上游 submodule）
  与 `cxx/webview/sdk/`（微软 SDK 头文件）**不在内**，不要对它们"顺手格式化"。

## 一、注释形式：四种文件同一套

> **DO：** `.kt` / `@Composable` / `.cpp` / `*.gradle.kts` 注释同形——普通注释一律 `//`
> （多行就连续多行 `//`，不用 `/* */` 块拼）；文档注释用 `/** … */`（KDoc 形态，C++ 与 Gradle 也
> 写成 `/**` 单独一行、后续每行 `*` 开头、` */` 单独一行）。

> **DO：** 注释按「是不是对外契约」分档——`public` 的方法/类型是契约，写清用法与不显然的约束；
> **属性与 `private` 成员只写「不读就会用错」的那几条**（口径边界、陷阱、防误改约束），
> 「为什么长成这样」的推演不留。

> **DO：** 改到旧注释就顺手改成中文；一个文件改完**整篇过一遍**注释，不只改手上碰的那一处。

## 二、本项目与官方/工具不同的特例

> **DO：** `@Composable` 且返回 `Unit` 的函数用 UpperCamelCase 名词（Compose 官方基线，App 档 MUST）。
> 已在 `.editorconfig` 配好 `ktlint_function_naming_ignore_when_annotated_with = Composable`，
> 别再为此关掉整条命名规则。

> **DO：** `Modifier` 参数必须是**第一个可选参数**（App 档 MUST），不另起第二个。

> **DON'T：** 给「带 `@Preview` 又是真实入口」的 composable 加 `private`。compose-rules 的
> `preview-public-check` 会给带 `@Preview` 的 composable 自动加 `private`，但本项目 `App()` /
> `CodeScreen()` / `PlayScreen()` 既挂 `@Preview` 又是真实入口（分别被 `MainActivity`、`main.kt`、
> `App()` 调用）→ 加完跨文件调用全断。**正确处置：保持 public 并加
> `@Suppress("ktlint:compose:preview-public-check")`**（compose 规则不吃 `.editorconfig`，只能
> `@Suppress`）。

> **DON'T：** 让 `ktlintFormat` 的自动修复直接过关。**它曾误删仍在使用的 import**
> （`VideoSurface.kt` 的 `androidx.compose.ui.Modifier`、`QuickJS.kt` 的 `CompletableDeferred`），
> 又加了没用的 `await`——这些只会在编译期暴露。跑完 `ktlintFormat` **必须重跑编译闸门**。

> **DO：** C++ 注释写完**必须跑 `clang-format -i`**：`ReflowComments` 会把中英混排注释按列宽重排，
> 句中断行被换成空格、句子被切开。回修姿势是**塌成一条物理行**再 `-i`（逐行删空格无效）；
> 收敛判据是再跑一次 `diff` 为空。写在 C++ 里的中文注释要短（`ColumnLimit: 80` 按显示宽度算，
> 一行约 38 个汉字顶格）。实测数据见
> [`docs/development/format-config-rationale.md`](../../../docs/development/format-config-rationale.md)。
