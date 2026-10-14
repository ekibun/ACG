# 格式化配置的实测依据与决策沿革

只收录 `.editorconfig`（Kotlin / KTS / Gradle）与根 `.clang-format`（C++）背后的**实测数据与决策沿革**。
不在 agent 自动决策路径上；规则本身在那两个配置文件里，与官方不同的特例见
[`.agents/skills/coding-style/SKILL.md`](../../.agents/skills/coding-style/SKILL.md)。
数据会随工具版本漂移，以当前仓库为准。

## Kotlin 行尾注释：一格 vs 两格

Kotlin / KTS 行尾注释用**一个空格**（代码 + 一个空格 + `//`），不用两格。

实测佐证：`ktlintCheck` 的 `standard:no-multi-spaces` 会把「代码 + 两个空格 + `//`」判违规；
加两格 → `:shared:ktlintCheck` 当场失败，改回一格 → 绿。违规点在代码与 `//` 之间那一处。

C++ 侧相反：`.clang-format` 的 `SpacesBeforeTrailingComments: 2`（Google 预设），行尾注释前两个空格。

## Kotlin 行长：为什么必须是具体数字

`.editorconfig` 的 `[*.{kt,kts}]` 里写 `max_line_length = 120`，不能写 `off`：

- 写成具体数字才会让 ktlint 的 `standard:max-line-length` 真正生效；写成 `off` 等于关掉这条规则。
- ⚠️ 这条**不只是多一条长度检查**：`max_line_length` 一从 `off` 变成数字，ktlint 的
  **一整套换行类规则**会同时被激活 —— `function-signature`、`parameter-list-wrapping`、
  `argument-list-wrapping`、`chain-method-continuation`、`multiline-expression-wrapping` 等。
  设成 120 时全仓冒出 5 处新违规；`multiline-expression-wrapping` 还会否决「把签名收成一行、
  函数体不动」这种最小改法，要求多行表达式体另起一行 —— 于是整个 `= object : ... { }` 的
  函数体要跟着 +2 缩进。**改这个值要预期到这种扩散。**

Kotlin 官方本身不设行长上限（判断标准是「能否放在一行」）；120 是本项目的软上限决定。

## C++ 注释：clang-format 重排

`.clang-format`：`BasedOnStyle: Google` + `ReflowComments: true` + `ColumnLimit: 80`。

`ReflowComments` 把中英混排注释的句中断行合并成空格（如 `…没人` / `接；…` → `…stderr` / `没人 接；…`）。
整篇重排 `webview.cpp` 时命中 20 处、已人工回修，回修后是 clang-format 的**不动点**（再跑 `-i` 无改动）；
`quickjs.cpp` 塌行重写后同样收敛。

回修正确姿势：把被重排段落**塌成一条物理行**再跑 `clang-format -i`；逐行删空格无效（下次 `-i` 会重插）。
收敛判据：`-i` 后再跑一次 `diff` 为空。

> 这条决定了「中文注释要写短」（见下）：写长了 clang-format 会在句中断行并被 `ReflowComments` 重排，
> 于是句中凭空多出空格。与其事后回修，不如一开始压在一条线内。

## 中文注释显示宽度

`ColumnLimit: 80` 数的是**显示宽度**（一个汉字按 2 列），不是 UTF-8 字节数。

探针（两行注释）：显示宽度 80 过、81 报违规；同样两行的字节数是 110 / 111。
结论：一行 `// ` + 38 个汉字即顶格，再长 clang-format 自行折行。

## `#include` 排序：`jawt_md.h` 的顾虑不成立

`SortIncludes: true` + `IncludeBlocks: Regroup` 会按类别重组并排序 `#include` 块。
曾担心 `cxx/webview/webview.cpp` 的 `jawt_md.h` 必须排在 `windows.h` 之后，重排会破坏它 ——
**核实不成立**：JDK 11 / 21 的 `include/win32/jawt_md.h` 自带 `#include <windows.h>`；
且重排后 `windows.h` 依然排在 `jawt.h` / `jawt_md.h` 之前。重排没造成问题。

## clang-format 版本

基线来自 MSYS2 mingw64 提供的 clang-format 22（二进制 22.1.8）。**换大版本会让格式输出漂移** ——
调整前先确认版本，必要时整仓重跑 `./gradlew clangFormat` 并复核 diff。

怎么跑：C++ 格式检查**不走二进制**，`./gradlew clangCheck`（校验）/ `./gradlew clangFormat`（重写）。
Gradle 任务经 `cxx/exec.cmd` 进 MSYS2 MINGW64 shell，那里 `/mingw64/bin` 已在 PATH，
clang-format 自然命中 —— 不用配路径、也不设 `ACG_CLANG_FORMAT`。
