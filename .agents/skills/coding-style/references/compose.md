# Compose 风格

来源：[Compose API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md)。
标「基线」的是官方原文规则（原文区分 Framework / Library / App 三档 MUST/SHOULD，本仓库按
**App 档**执行；标 MUST 的即使 App 档只要求 SHOULD，也按 MUST 办）。

适用范围：**只管我们自己在 `shared/**` 下写的 `@Composable`** —— `cxx/` 下的 submodule 与 SDK
头文件不在其中（见 [`SKILL.md`](../SKILL.md) 的「适用范围」）。缩进与注释同样看那里。

## @Composable 函数命名（基线）

- **返回 `Unit` 的** `@Composable` 用 **PascalCase**，且名字必须是**名词**
  （可在名词前加描述性形容词）。写 `FancyButton`、`BackButtonHandler`；
  不写 `fancyButton`（大小写）、`RenderFancyButton`（动词）、`drawProfileImage`（不是名词）。
- **返回非 `Unit` 值的** `@Composable` 用标准 Kotlin 函数命名（camelCase），
  且**不许**用官方给工厂函数的豁免权。写 `fun defaultStyle(): Style`，不写 `fun Style(): Style`。
- **内部 `remember {}` 并返回可变对象**的工厂函数**必须**加 `remember` 前缀：
  `rememberCoroutineScope()`，不是 `createCoroutineScope()`。
  （若 `remember` 只是次要目的，例如 `collectAsState()` 主要是订阅，则不算工厂函数，不用加。）
- `CompositionLocal` 的键**不要**用 `CompositionLocal` 或 `Local` 作**后缀**（别写 `ThemeLocal`）；
  找不到更好的名字时可以用 `Local` 作**前缀**（`LocalTheme`）。

## 状态持有者（基线）

- 为某个 composable 提升出来的状态类型，命名为 **该 composable 名 + `State`**
  （`VerticalScroller` → `VerticalScrollerState`）。
- 该类型标注 `@Stable` 并正确实现契约；**若不是 final 类，应声明为 `interface`** 而不是抽象类或开放类
  —— 接口能让调用方用**一个**自定义类型同时实现多个状态接口，从而保持**单一真相源**。
- 提供一个与类型同名的工厂函数返回默认实现（`fun FooState(): FooState = FooStateImpl(...)`）。
- 默认状态用**默认参数** `remember { XxxState() }` 提供。
- **不要用 `null` 当哨兵值**表示「内部自己 remember 的状态」，会产生意外行为。
- 自定义 `@Stable` 类型的 `.equals()` 必须始终返回相同值。

## Modifier（基线，全部按 MUST 执行）

- 元素函数**必须**接受 `Modifier` 参数，**必须**命名为 `modifier`，**不许**有第二个。
- `modifier` **必须**是参数表里**第一个可选参数**（所有必需参数之后、其他可选参数之前）。
- 默认值**必须**是 `Modifier`（除非该元素没有可测量内容尺寸，如 `Canvas`，此时可要求调用方必给）。
- **必须**把收到的 `modifier` 传给它发射的根节点。
- 可以往收到的 `modifier` **末尾**拼接附加修饰符，**绝不能拼到开头**。

```kotlin
@Composable
fun FancyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = Text(
    text = text,
    modifier = modifier.surface(elevation = 4.dp).clickable(onClick).padding(...),
)
```

## 参数顺序（基线）

- 通用：**必需参数 → 可选参数**，其中 `modifier` 是第一个可选参数。
- 布局函数：单个 `@Composable` 参数**应命名 `content`**（有多个时，最常用的那个叫 `content`）；
  且**应放在最后一个参数位置**，以便调用处用尾随 lambda。
- **发射与返回二选一**：不许有既发射节点又返回值的 `@Composable`；与调用方沟通只能靠向前传的参数。

## 状态提升（基线）

- 优先**无状态、受控**：composable 自己不持有状态，接受调用方拥有的状态参数 + 事件回调。
  写 `Checkbox(isChecked: Boolean, onToggle: () -> Unit)`，而不是自己 `remember` 初始值再回调通知
  （后者让调用方无法实施自己的校验策略）。
- 状态的参数用**值**，事件的参数用**回调**，两者分开。
- 当参数涨到「多个状态 + 多个回调」时，**应**抽成一个 `@Stable` 接口，让 composable 直接收这个对象；
  该对象可以聚合相关策略（例如把 `scrollPosition` 自动 clamp 进 `scrollRange`）。

## 参数命名惯例（基线的事实标准）

- 事件回调用 **`on` 前缀** + camelCase 动作：`onClick`、`onToggle`、`onScrollPositionChange`、`onBackPressed`。
- 状态值用描述性名词/布尔，不加后缀：`isChecked`、`scrollPosition`、`scrollRange`。
- 状态持有者参数的**类型**以 `State` 结尾，参数名通常为 `<x>State`：`verticalScrollerState`、`inputState`。

## 本项目

- **UI 状态沿用 `remember { mutableStateOf(...) }` + `by` 委托**，需要跨重组存活的复杂状态放在
  普通 class 里用 `mutableStateOf` + `internal set`，由同文件的 `rememberXxxState()` 工厂创建
  （参考 `shared/src/commonMain/kotlin/soko/ekibun/acg/web/AcgWebView.kt`）。
- 本仓库**没有**使用 `ViewModel` / `StateFlow` / `collectAsState`。**不要**为了「架构更正确」而引入
  它们；真有跨屏共享状态需求时先讨论。注意 `shared/build.gradle.kts` 里引了 `viewmodel-compose`
  但全仓零使用 —— 那是遗留依赖，不要把它当成「项目选了 MVVM」的信号。
- **Modifier 一律具名传递、放在第一个可选参数位**。现有代码位置不统一（有的放在参数末尾、
  有的位置传参），新代码按基线；改到旧代码时顺手对齐那一处即可。
- 状态与事件的参数命名按基线（`onX` / `xState`），不要自创 `handleX` / `xxxChanged` 之类。
