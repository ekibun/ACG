# 格式化 / lint

> **DON'T：** 验收阶段跑下面任何一条。只在**用户确认提交时**或明确要求时跑（根 [`AGENTS.md`](../../../../AGENTS.md) §2.5）。

```bash
./gradlew ktlintCheck --continue              # Kotlin 校验（标准规则 + compose-rules 规则集）
./gradlew ktlintFormat --continue             # Kotlin 重写；能自动修的它直接修掉
./gradlew clangCheck                          # C++ 校验（--dry-run --Werror）
./gradlew clangFormat                         # C++ 重写（就地 -i）
```

> **DO：** C++ 格式**走 Gradle 任务**，不要直接调 `clang-format` 二进制。任务经 `cxx/exec.cmd` 进
> MSYS2 MINGW64 shell（那里 `/mingw64/bin` 已在 PATH），基线 clang 22.1.8 自然命中 ——
> 不用配路径、也不设 `ACG_CLANG_FORMAT`。范围固定是 `cxx/{webview,quickjs,ffmpeg}/` 三个顶层 `.cpp`。

> **DON'T：** 用 `ktlintFormat` / `clangFormat` "顺手格式化"——它们会改到本次改动之外的行，违反外科手术式
> 改动。只想看自己这次写的行合不合规，跑 `ktlintCheck` / `clangCheck`（只校验、不改文件）。

> **DO：** 看结果分清是哪个 task 挂的：`ktlintCheck` 按 sourceSet 拆成多个任务，不加 `--continue` 会在
> 第一个失败的模块就中止，报出来的违规数只覆盖跑过的模块，会被误读成"违规不多"。

规则正本是根目录的 `.editorconfig`（Kotlin / KTS / Gradle）与 `.clang-format`（C++，Google 基线、
只偏离「行尾统一 LF」一处），两边取值必须一致。这两个文件为什么要这么设、各自的实测依据，见
[`docs/development/format-config-rationale.md`](../../../../docs/development/format-config-rationale.md)。

> **DO：** 提交前 `.githooks/pre-commit` 会当场拦一次：一次跑 `./gradlew ktlintCheck clangCheck --continue`
> （Kotlin 查全仓、C++ 查那三个固定文件），两类都没有改动也照样跑一遍。钩子只校验、不改文件。
> 装法：`git config core.hooksPath .githooks`（每个 clone 做一次）。验证不用真提交：`git hook run pre-commit`。

> **DON'T：** 接 GitHub Actions 这类「推到远端之后才跑」的 CI：拦不住「改动进仓库」这个动作。

`.editorconfig` 的 `max_line_length` 是 **120**（不是 `off`）。写成数字会让 ktlint 的
`standard:max-line-length` 生效、并连带激活一整套换行类规则；写成 `off` 等于关掉 —— 两者互斥。
改这个值预期会扩散到多处代码，依据见
[`docs/development/format-config-rationale.md`](../../../../docs/development/format-config-rationale.md)
的「Kotlin 行长」一节。

> **DON'T：** 让 `ktlintFormat` 的自动修复直接过关 —— 它曾误删仍在使用的 import，只会在编译期暴露。
> 跑完**必须重跑编译闸门**（四条编译任务的命令见本技能 [`gates.md`](./gates.md)）。
