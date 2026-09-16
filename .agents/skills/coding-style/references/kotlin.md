# Kotlin 风格

来源：[Kotlin 官方编码约定](https://kotlinlang.org/docs/coding-conventions.html)。
下面标「基线」的是官方原文规则；标「本项目」的是本仓库叠加的决定。

> 官方一句话总纲：**当有疑问时，照抄周围代码的风格**。但本仓库的约定**比官方更严**
> （如块缩进取 2 而非 4、不用通配符 import），所以有约定时以本文为准。

## 格式（基线）

- **缩进：块缩进每级 2 个空格、续行 4 个空格** —— 这条**覆盖**官方基线的 4 空格块缩进；
  全语言统一的规则见 [`SKILL.md`](../SKILL.md) 的「缩进」一节。
- 左花括号放在构造开始行的**末尾**，右花括号**单独一行**并与起始构造水平对齐。
- 行长**不设字符数上限**，判断标准是「能否放在一行」：放不下就换行。
- **尾随逗号：声明处鼓励使用**（参数、when 分支等），**调用处自选**。
  IDEA 里开启：`Settings | Editor | Code Style | Kotlin → Other → Use trailing comma`。
- 空行：文件注解与 `package` 之间空行；类头与类体之间空行；多行 `when` 的相邻分支块之间可空行。
- 水平空白：
  - 二元运算符两侧有空格（`a + b`），**例外** `..` 两侧无空格（`0..i`）；一元运算符无空格（`a++`）。
  - 控制流关键字与左括号之间有空格（`if (`）；主构造 / 方法声明 / 调用的左括号**前**无空格。
  - `(` `[` 之后、`)` `]` 之前**绝不**空格；`.` 与 `?.` 两侧**绝不**空格。
  - `//` 之后有空格；类型参数尖括号两侧无空格（`Map<K, V>`）；`::` 两侧无空格。
  - 可空类型 `?` **前**无空格（`String?`）。**避免任何形式的水平对齐**。
- 冒号：分隔「类型与超类型 / 委托构造 / `:` 后的 `object`」时**冒号前有空格**；
  分隔「声明与类型」时**冒号前无空格**；**冒号后始终有空格**。
- 花括号：`if` / `when` 条件多行时主体**必须**用花括号，且条件闭括号与左花括号各占一行；
  `else` / `catch` / `finally` / `do-while` 的 `while` 与前花括号同行；
  短的 `when` 分支可同行无花括号（`true -> bar()`），长的用花括号。
- **修饰符顺序**（官方给的固定序列，省略冗余修饰符，非库开发不写 `public`）：
  `public/protected/private/internal` → `expect/actual` → `final/open/abstract/sealed/const` →
  `external` → `override` → `lateinit` → `tailrec` → `vararg` → `suspend` → `inner` →
  `enum/annotation/fun` → `companion` → `inline/value` → `infix` → `operator` → `data`。
  **所有注解放在修饰符之前。**
- 注解：置于声明前、单独一行且同缩进；无参注解可同行（`@JsonExclude @JvmField var x`）；
  单个无参注解（如 `@Test`）可与声明同行。

## 源文件组织（基线）

- 目录结构跟随包结构（省略公共根包）。
- 同一文件可放多个声明（类 + 顶层函数/属性），前提是**语义紧密相关**且**文件不超过几百行**。
- **类内布局顺序**：属性与初始化块 → 次级构造函数 → 方法 → 伴生对象。
- 方法既**不按字母也不按可见性**排序；**相关代码放在一起**，选定顺序后保持。
- **重载函数必须彼此相邻**。
- 实现接口时，实现成员的顺序**与接口声明顺序一致**（可穿插私有方法）。
- 嵌套类放在使用它的代码旁边；若仅供外部使用且类内未引用，放到末尾（伴生对象之后）。
- 扩展函数：与类放同一文件（若对该类所有客户端都有用）；只对特定客户端有用就放在该客户端代码旁；
  **不要**为了"把某类的所有扩展收齐"而单独建文件。

## 命名（基线）

| 实体 | 规则 | 例 |
|---|---|---|
| 包 | 全小写、无下划线 | `soko.ekibun.acg` |
| 类 / 对象 | UpperCamelCase | `DeclarationProcessor` |
| 函数 / 属性 / 局部变量 | 小写开头驼峰、无下划线 | `processDeclarations` |
| 常量属性（`const` 或深度不可变的顶层/对象 `val`） | SCREAMING_SNAKE_CASE | `MAX_COUNT` |
| 枚举常量 | SCREAMING_SNAKE_CASE 或 UpperCamelCase 均可 | `OK` / `Ok` |
| backing property | 私有属性加下划线前缀 | `_elementList` |
| 测试方法（仅测试） | 允许反引号包空格名 | `` `ensure everything works` `` |

- 返回 `Unit` 的 `@Composable` 函数是**例外**，用 UpperCamelCase（见 `compose.md`）。
- 缩写：两个字母全大写（`IOStream`）；**超过两个字母只首字母大写**（`XmlFormatter`，不是 `XMLFormatter`）。
- 命名选择：类名用名词/名词短语；方法名用动词/动词短语，并暗示**是否修改对象**
  （`sort` 改、`sorted` 不改）；**避免** `Manager`、`Wrapper`、`Util` 这类无意义词。

## 函数与属性（基线）

- **优先带默认参数值的函数，而不是写重载**。
- 多个同类型参数（尤其 `Boolean`）在调用处**用命名参数**，除非含义绝对清楚。
- 工厂函数**避免与类同名**，用明确的特殊名；只有在没有更合适语义时才同名。
- 返回 `Unit` 时**省略返回类型声明**。
- 尽可能**省略分号**。
- 字符串模板：简单变量不加花括号（`$name`）；复杂表达式才加（`${children.size}`）。
- 长参数列表换行：左括号后换行，每个参数单独一行缩进四空格。
- 链式调用换行：`.` 或 `?.` 放在**下一行**，缩进一次。

## Lambda（基线）

- 花括号与箭头两侧有空格；单 lambda 参数的函数尽量**把 lambda 放在括号外**。
- 标签与左花括号之间无空格（`lit@{`）。
- 多行 lambda 的参数名放在第一行、箭头后换行；参数太长时箭头单独一行。
- 简短且不嵌套的 lambda 用 `it`；**嵌套 lambda 显式声明参数名**。
- 避免在 lambda 里做多个标签返回；把它重构成单出口；不要用带标签的返回做最后一条语句。

## 惯用法（基线）

- 优先 `val` 而非 `var`；优先不可变集合接口（`List` / `Set`）；工厂函数返回不可变集合
  （`listOf` 而不是 `arrayListOf`）。
- 优先 `if` / `when` / `try` 的**表达式形式**（`return if (x) foo() else bar()`）。
- 二元条件用 `if`，**三个及以上分支用 `when`**。
- 可空 `Boolean` 用 `if (value == true)` / `if (value == false)` 判断，不要 `!!`。
- 循环优先用高阶函数（`filter` / `map`）；`forEach` 除外，它可以用 `for`。
- 区间用 `..<` 而不是 `..n-1`。
- **函数 vs 属性**：不抛异常、计算廉价或可缓存、状态不变时结果相同 → 用属性；否则用函数。
- 扩展函数放宽使用，但要**把可见性收到最小**，避免污染 API。
- `infix` 只用于两个地位相似的对象之间，不用于修改接收者。

## 文档注释（基线）

- 长 KDoc：`/**` 单独一行，后续每行以 `*` 开头。
- 短 KDoc 可单行：`/** This is a short documentation comment. */`
- **避免 `@param` 与 `@return` 标签** —— 把描述写进正文并链接参数；
  只有当内容太长、放正文会妨碍阅读时才用标签。

## 本项目

- **注释用中文、格式四种文件统一** —— 完整规则在 [`comments.md`](./comments.md)，
  一句话概括：中文注释 + 一律 `//`（多行就多行 `//`）+ 文档注释用 `/** … */`（KDoc 形态）。
- 平台实现文件命名跟随官方源集后缀建议：`Xxx.android.kt` / `Xxx.jvm.kt`，与 `Xxx.kt` 成对，
  放在各自源集的同等包路径下（本项目现状就是这样，保持一致）。
- 可见性以 `private` 为主；`internal` 只在确实要给同模块的测试或兄弟类用时才用。
- 一个「契约」一个文件是允许的：把某个功能的 state / controller / `@Composable` 入口放在同一文件
  （如 `AcgWebView.kt`），符合官方的「语义紧密相关」；但不要靠这个理由把文件堆到上千行。
- 格式化与 lint **已配置**：根 `.editorconfig`（ktlint 读它）+ `org.jlleitschuh.gradle.ktlint` 插件
  （挂 `io.nlopez.compose.rules:ktlint` 规则集）。约定由工具执行，**别再靠人守**；
  还没接的是「什么时候跑」，见根 `TODO.md`。
- ⚠️ **`ktlintFormat` 的自动修复不可盲信，跑完必须重跑编译闸门**（2026-09-16 实测两次翻车）：
  它**删了仍在使用的 import**（`VideoSurface.kt` 的 `androidx.compose.ui.Modifier` —— 只用在默认值里，
  被当成未用；`QuickJS.kt` 的 `CompletableDeferred`），**又加了没用的**（`await`）。
  这些只会在编译期暴露，所以「`ktlintFormat` 跑绿了」**不等于**代码没坏。
- ⚠️ **compose-rules 的 `preview-public-check` 对本项目是错误假设**：它给「带 `@Preview` 的 composable」
  自动加 `private`，而本项目 `App()` / `CodeScreen()` / `PlayScreen()` **既挂 `@Preview` 又是真实入口**
  （分别被 `MainActivity`、桌面 `main.kt`、`App()` 调用）→ 加完跨文件调用**全断**。
  正确处置是**保持 public 并加 `@Suppress("ktlint:compose:preview-public-check")`** ——
  compose 规则不吃 `.editorconfig`，只能用 `@Suppress`。
- 任务**按 sourceSet 拆**（`ktlintCommonMainSourceSetCheck` 等），逐模块跑，不加 `--continue`
  会在第一个失败模块中止、把违规数读少。

## 官方未规定、本项目需自行决定的三处

这三处官方没有给规则，现有代码曾不统一。**三条都已拍板，取值都写在根 `.editorconfig`，
并由 ktlint 的标准规则执行**（改口径要 `.editorconfig` 与本文件一起改）：

1. **通配符 import**：不用，显式 import —— 标准规则 `no-wildcard-imports`。
2. **最大行长**：**软上限 120 字符**（`.editorconfig` 里 `max_line_length = 120`）。
   官方本身不设上限，判断标准仍是「能否放在一行」。
   ⚠️ 这条**不只是"多一条规则"**：`max_line_length` 一从 `off` 变成具体数字，ktlint 的
   **一整套换行类规则**会同时被激活 —— `function-signature`、`parameter-list-wrapping`、
   `argument-list-wrapping`、`chain-method-continuation`、`multiline-expression-wrapping` 等。
   2026-09-17 设成 120 时全仓冒出 5 处新违规；而且 `multiline-expression-wrapping` 会**否决**
   「把签名收成一行、函数体不动」这种最小改法 —— 它要求多行表达式体另起一行，于是整个
   `= object : ... { }` 的函数体要跟着 +2 缩进。**改这个值要预期到这种扩散**，
   别以为只是加一条长度检查。
3. **调用处尾随逗号**：多行参数列表**一律加** —— 标准规则 `trailing-comma-*`。
