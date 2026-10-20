# 格式化 / lint

```bash
./gradlew ktlintCheck --continue              # Kotlin 校验（标准规则 + compose-rules 规则集）
./gradlew ktlintFormat --continue             # Kotlin 重写；能自动修的它直接修掉
./gradlew clangCheck                          # C++ 校验（--dry-run --Werror）
./gradlew clangFormat                         # C++ 重写（就地 -i）
```

> **DO：** `ktlintCheck` 必须带 `--continue`：它按 sourceSet 拆成多个任务，不加会在第一个失败模块就中止，报出来的违规数只覆盖跑过的模块，会被误读成"违规不多"。

> **DO：** C++ 格式**走 Gradle 任务**，不要直接调 `clang-format` 二进制。任务经 `cxx/exec.cmd` 进
> MSYS2 MINGW64 shell（那里 `/mingw64/bin` 已在 PATH），基线 clang 22.1.8 自然命中 ——
> 不用配路径、也不设 `ACG_CLANG_FORMAT`。范围固定是 `cxx/{webview,quickjs,ffmpeg}/` 三个顶层 `.cpp`。

> **DON'T：** 用 `ktlintFormat` / `clangFormat` "顺手格式化"——它们会改到本次改动之外的行，违反外科手术式
> 改动；且 `ktlintFormat` 曾误删仍在使用的 import，只在编译期暴露。只想看自己这次写的行合不合规，跑
> `ktlintCheck` / `clangCheck`。真的跑过 `ktlintFormat`，**必须重跑编译门禁**（命令见 [`build.md`](./build.md)）。

> **DO：** 提交前 `.githooks/pre-commit` 会当场拦一次：一次跑 `./gradlew ktlintCheck clangCheck --continue`
> （Kotlin 查全仓、C++ 查那三个固定文件），两类都没有改动也照样跑一遍。钩子只校验、不改文件。
> 装法：`git config core.hooksPath .githooks`（每个 clone 做一次）。验证不用真提交：`git hook run pre-commit`。

> **DON'T：** 接 GitHub Actions 这类「推到远端之后才跑」的 CI：拦不住「改动进仓库」这个动作。
