# 构建脚本风格（Gradle Kotlin DSL）

来源：Gradle 官方 [Avoiding Unnecessary Task Configuration](https://docs.gradle.org/current/userguide/task_configuration_avoidance.html)，
以及本项目根 [`AGENTS.md`](../../../../AGENTS.md) 的既有约定。标「基线」的是官方规则，标「本项目」的是本仓库叠加的决定。

## 依赖与版本（本项目）

- **所有依赖版本集中在 `gradle/libs.versions.toml`，模块里一律引用 `libs.*`，不硬编码版本号**。
  现状已符合；唯一例外是 `settings.gradle.kts` 里 foojay 插件的 `version "1.0.0"`（插件在 settings
  阶段无法用 version catalog，属已知例外）。
- 插件统一用 `alias(libs.plugins.xxx)` 引入。
- 文档（包括 `AGENTS.md`）里**不写版本号**，以 `libs.versions.toml` 为准。
- 非依赖性的版本值（`packageVersion`、`versionCode` / `versionName`）目前直接写在模块脚本里，
  这是现状；要改它们时不要顺手搬到别处。

## 任务注册（基线 + 本项目）

Gradle 官方的原则是**配置回避**：任务用懒 API 注册，只有真正需要时才实例化。
未使用的任务不做配置，能显著减少配置时间。

**该用（懒 API）**：

| 场景 | 写法 |
|---|---|
| 新建任务 | `tasks.register<T>("name") { … }` —— 返回 `TaskProvider`，不是 `Task` |
| 配置已存在的任务 | `tasks.named<T>("name") { … }` |
| 按类型批量配置 | `tasks.withType<T>().configureEach { … }` |
| 按容器批量配置 | `configureEach { … }` |

**不要用（急 API）**：`tasks.create(…)`、`tasks.getByName(…)`、`tasks.getByPath(…)`、
`tasks.findByName(…)`、`tasks.all { }`、`tasks.withType(X) { }`（带 lambda 的即急）、
`tasks.whenTaskAdded(…)`、`task myTask(type: MyTask) { }` 这种简写。

两条容易踩的坑：

- **只在当前任务的配置动作里修改当前任务**。在 A 的配置块里 `b.get().dependsOn(this)` 是不可靠的
  —— B 的配置动作可能根本不执行。要建立关系就写成 `a.configure { dependsOn(b) }`。
- **不要按名字再查一遍已注册的任务**。注册时把 `TaskProvider` 存进变量，之后直接引用它；
  按名字查找会强制实例化，把刚才省下的开销又花回去。

**本项目额外一条**：任务注册用 `tasks.register<T>("name")`；
`by tasks.registering(...)` 委托写法**已弃用且会编译失败**，不要用。

## 格式（本项目）

- **块缩进每级 2 个空格、不用 tab，续行 4 个空格**（与 Kotlin / C++ 同一套，见
  [`SKILL.md`](../SKILL.md) 的「缩进」一节）。
- 注释：**中文 + 行注释 `//`**，见 [`comments.md`](./comments.md)；`libs.versions.toml`
  里的分组与说明注释同样照此。
- 适用范围只覆盖本仓库自己的 `*.gradle.kts` 与 `gradle/libs.versions.toml`，
  不含 `cxx/` 下的 submodule（它们自带构建脚本，不归我们管）。

## 脚本结构（本项目现状）

- 没有 `buildSrc` / convention plugin，共享配置直接写在 `settings.gradle.kts` 与各模块脚本里。
  新增共享逻辑时先问清楚要不要抽 convention plugin，不要顺手复制粘贴到多个模块。
- **不要顺手做无关的格式化**：脚本现在由 ktlint 统一格式（`ktlintCheck` 覆盖到 `*.kts`），
  整齐这件事交给工具；别为了"整齐"重排整个文件——那会把真正的改动淹掉。
