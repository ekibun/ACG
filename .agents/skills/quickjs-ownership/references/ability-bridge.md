# 能力桥（`_binding`）的形态依据

`JsEngine` 里那个桥为什么长这样，以及**为什么不能用反射**。改 `JsEngine` 的派发、
给能力桥加新方法、或排查「`no method '…'`」之前先读本文。

代码里只留设计理由（`when` 直接调各能力、私有方法免疫），**出处与取证在这里**。

---

## 一、能力桥的形态

`init.js` 的引导函数收**一个**桥：

```js
async (_binding) => { /* … */ }
```

形如 `_binding(method, args)` —— **通用派发**，`args` 是那一份实参数组，按名字转发到
各能力。桥名不点出宿主语言：现在是 KMP，两端的宿主未必是 JVM。

⚠️ `args` 是**一个数组**，作为 `_binding` 实参的**元素**整体过桥 —— 不是摊平成位置参数。
`console.log(a, b, c)` 送过去是 `_binding('console', ['log', [a, b, c]])`，第二层数组是
`console.log` 自己的实参数组。写成 `['log', a, b, c]` 会让 Kotlin 侧的
`console(type, data: Array<Any?>)` 收错形状。

各能力的实参一律打进 `args`：`webview` 的 4 个实参也是
`_binding("webview", [url, header, script, onInterceptRequest])`，不是位置参数。
加分支时对着 [JsEngine] 的 `when` 逐个核下标。

⚠️ **别把「下标错位是静默的」当判据** —— 那是推的，实测是**响**的：相邻下标类型对不上就
`ClassCastException`（webview 的 4 个下标两两串位都会当场抛）。真正静默的是**桥本身的形状**：
`args` 被多包一层时，`argv[1]` 只是越界/类型不符，而三条桩测试全绿（见下）。

Kotlin 侧对应 `JSInvokable { argv -> … }`：**lambda 只有一个值参数**，`thisVal` 是隐式接收者、
不占参数位，而 `argv` 是 `vararg` 打包成的实参数组（**原样**，不额外包层）。

⚠️ `JSInvokable` 的 companion 曾把 `argv` 多包一层（`block(arrayOf(argv))`）——
大概率是为了绕 `Array<out Any?>` 不能当 `Array<Any?>` 传（Kotlin 数组不变）的类型问题，
但包出来的语义是错的：`argv[1]` 越界、`argv[0] as String` 拿到的是数组。
正确解法是把 block 的入参类型改成投影 `Array<out Any?>`（companion 的 KDoc 已记）。
**这个错只有真派发才暴露** —— 桩测试都自己实现 [JSInvokable] 接口、直接拿 `argv`，
所以三条老测试全绿。`JsEngineDispatchTest` 第一次跑就撞上了它。

实参走 `args` 数组**不改变所有权语义**（查 `QuickJS.jsToJava`，2026-10-05 起转换在
Kotlin 侧）：
函数元素与顶层 `argv` 走的是同一个函数分支，产出的 `JSFunction` 一票归「拿到它的人」
`free()`；数组元素为 JS `null` 时得到 Java `null`，与顶层位置参数同路。

---

## 二、为什么不用反射

**反射派发在这条链路上是错的，不是慢 —— 是查不到。**

`Class.getMethods()` **只返回 public 方法**；private 的只在 `getDeclaredMethods()` 里。
而各能力方法全是 `private`，所以按名字找的写法：

```kotlin
// ⚠️ 这段已不存在，留着是为了说明它为什么错的
val candidates = objWrap.javaClass.methods.filter { it.name == methodName && … }
// private 的能力一个都查不到 ⇒ candidates 恒空 ⇒ 抛 no method '…'
```

⇒ `fetch` / `text()` / `encodeURI` / `webview` 四条链路**一律不可达**。
现在的 `when(method)` **直接调**各能力，**没有「按名字找方法」这一步** ⇒ 可见性不再影响能否被调用。

副作用是好的：方法名写错是**编译错误**，而反射版写错要等运行期。

### 这条回归是本工程独有的

| 位置 | 写法 | 后果 |
|---|---|---|
| 引入提交 `1dc16fa` | `javaClass.methods`（从 `declaredMethods.first`改过来） | ❌ 排除了 private |
| `nekomp` 至今 | `declaredMethods.first` | ✅ 仍能查到 private |

`1dc16fa` 的动机是**修「重载时 `first{}` 选错」** —— 动机对，副作用排除了所有 private。

### `javap` 对编译产物的佐证

```
$ javap -p -classpath shared/build/classes/kotlin/jvm/main soko.ekibun.acg.engine.JsEngine
  private final void console(java.lang.String, java.lang.Object[]);
  private final byte[] encode(java.lang.String, java.lang.String);
  private final java.lang.String decode(byte[], java.lang.String);
  private final kotlinx.coroutines.Deferred<java.lang.Object> fetchAsync(java.util.Map<…>);
  private final kotlinx.coroutines.Deferred<java.lang.Object> webviewAsync(…);
```

### 为什么长期没被发现：这条路径零覆盖

**历史**（2026-10-03 之前）：`InitJsTest` / `WebviewJsTest` 都**注入自己的桩**顶替 `_binding`，
`NativeWebViewHostTest` 则直接构造 `WebViewTask` 调 `loadBackgroundWebView`、绕开整条 JS 桥
—— 三个都不走 `JsEngine` 的真实派发。⇒ `1dc16fa` 那种把四条链路全打成不可达的改动，
**套件照样全绿**。

**现在**：`JsEngineDispatchTest`（jvmTest，**零桩**、真 `JsEngine.instance`）是这条路径的
**唯一**测试面 —— 那两个桩文件已删除。断言写在**真 JS 表达式**里（`encodeURIComponent` /
`TextDecoder` / `console.log` / 真 `fetch` / 真 `webview` / 真 `FormData`）。
`NativeWebViewHostTest` 补另一半（WebView2 宿主本身的行为），它证明不了派发改对了。

### 桩为什么必须删（不是「不优雅」，是**验错了东西**）

桩用 `getOrNull(i)` + 静默默认值（`?: ""` / `?: emptyArray()` / `?: Charsets.UTF_8`）：
**形状错了不响**，只会吃到默认值。而真派发是硬下标 + 硬转型，错位当场抛。
更要命的是那些桩的分支**只消费参数、不断言形状** —— 验的是「桩自己喂进去的东西」，
正文改了它不知道。

⚠️ 实证：桩文件存在时，`JSInvokable` companion 把 `argv` 多包一层
（`block(arrayOf(argv))`）这个 bug **全套件无感**。删掉桩之后不可能再发生。

⚠️ 反过来，删桩也暴露了两条**真分支**以前被桩挡着看不见：
① `webview.cpp` 在 `!hasScript` 时回空串 ⇒ `init.js` 的 `json === ""` 判定；
② 可见视图链路上 WebView2 把 `undefined` 序列化成 `"null"`（4 个字符），
后台链路只判 `null`/`""` 不判 `"null"` ⇒ 后台脚本没返回值时脚本侧拿到 `null` 而非 `undefined`。

⚠️ 删桩时**别把「桩的时序」当成「真实能力保护」**保留：桩版那条
`interceptCallbackCanBeInvokedFromKotlin` 断言「脚本挂在 `await webview(...)` 上、
Kotlin 用手攥的 `CompletableDeferred` 决定何时放行」—— 那个闸门只有桩有，
真实实现走不到该时序（走不到才导致实测挂满 608s）。它的 KDoc 自己写着「不复现它」。

⚠️⚠️ **别把它「翻译」成真引擎版本 —— 我这么干过，产物是一条假用例。**
当时的假设是「命中 → 放行」在真实现里也该存在，改用「页面自己的加载过程当时钟」：
在 `/sub.png` 上命中、主框架与 `<script>` 上返回 null，断言整轮以 `Scripted` 收场。
**实测整轮仍以 `Intercepted` 收场**，断言必红。查 `cxx/webview/webview.cpp` 的
`RequestHandler::Invoke` 才知道两条硬约束：

- 回调返回命中 ⇒ 立刻 `view->settled = true` + `webview->Stop()`；
- 同一个 `Invoke` 开头还有 `if (!view || view->settled) return S_OK;`
  ⇒ **命中之后连回调都不会再被调一次**，更谈不上「再放行一次」。

加上 `loadBackgroundWebView` 的 `outcome.complete` 幂等、**只取第一个终态**，
所以「命中」与「放行」在真引擎里是**互斥的两种终局**，不存在「先命中再放行」的时序。
真正的等价覆盖是 `NativeWebViewHostTest.releasingEveryRequestLetsPageFinish`：
**接了回调、每次都返回 null（一次都不命中）**，页面照样跑完、整轮以 `Scripted` 收场 ——
这才是「放行」这一支的真能力。

⇒ 迁条目前先问「**真实现里这条时序存在吗**」，别因为桩里能造就假定能。
`grep -n "settled\|Stop()" cxx/webview/webview.cpp` 一次就能问清。

⚠️ 「`when` 分支与 `init.js` 送来的实参对不对得上」这件事，**本文件不再有任何断言**。
原先有个文本哨兵 `initFactoryTakesOnlyOneBridge`（锁引导函数形参个数与名字），
已删 —— 实测证明它冗余：删形参会让 8 条行为测试全红（**真破坏本来就会响**），
而加形参 / 给形参改名**全绿**（那不是缺陷，是换设计）。理由与实测表见检查清单第 8 条。
⇒ 现在这一层靠**代码评审**把关（清单第 4 条），不靠测试。

⚠️ **断言 JS 返回值时别套 `JSON.stringify`** —— 真派发的返回路径就是 `jsToJava`
（`cxx/quickjs/quickjs.cpp`）：JS 普通对象**整图展开**成 `java.util.LinkedHashMap`、
数组展开成 `List`（`ArrayList` 实例）、`ArrayBuffer` 变 `ByteArray`。
所以测试里直接 `return {…}`，Kotlin 侧 `assertIs<Map<*, *>>` / `assertIs<List<*>>` 就行；
套了 `JSON.stringify` 会得到 `String`（`assertIs<Map>` 当场红），而且 `ArrayBuffer`
还会被降级成 `{}` —— 顺带丢掉了「它其实是个有长度的二进制缓冲」这条信息。
这条等价于「断言 Kotlin 侧真实的跨语言类型」，比断言 JSON 文本更贴近生产路径。

### 三条只有真派发才暴露的写法约束

这三条都在**源码里以裸约束的形式**贴着那个函数（不另加符号），出处一并记在这里：

- **`JSInvokable` companion 里不能写 `thisVal?.block(argv)`。** 接收者声明成可空是**刻意的**
  （JS 里裸调 `f()` 时 `this === undefined`，经 `jsToJavaScalar` 的 `default: return nullptr`
  落到 Kotlin 就是 `null`，属正常语义），但**可空接收者的函数类型走 `invoke` 协议、
  编译器不插空检查** —— 加 `?.` 会让 block 整个不执行，而且**编译照过**。
  正确写法就是 `thisVal.block(argv)`：编译器在这一点上帮了忙，`this.ptr` 之类会直接
  编译不过（`reference has a nullable type`），比留到运行期炸好。
- **`init.js` 的 `FormData.set` 只能对 `__items__` 用 `splice`**，不能写 `this.splice`
  （后者静默无效）。
- **`init.js` 的脚本结果分支没有「非 JSON 就原样返回」的兜底**，这是**故意的**：
  宿主给的 `json` 一定是 JSON（WebView2 的 `ExecuteScript` 自己序列化，裸文本会被加引号
  变成合法 JSON，压根到不了这里），而 `webview.cpp` 在没有脚本时回的是**空串** ——
  所以只需判 `json == null || json === ""` 就够，与 `http.js` 的
  `it = it && JSON.parse(it)` 对齐。加兜底只会掩盖「宿主给了非 JSON」这个真问题。

---

## 三、`init` 工厂函数的引用计数

```kotlin
val init = runBlocking { ctx1.evaluate(moduleHandler("@init")!!, "<init>") } as JSFunction
try {
  init(JSInvokable { … })
} finally {
  init.close()
}
```

⚠️ **必须 `as JSFunction`，不能 `as JSInvokable`** —— 后者丢掉 `AutoCloseable`，
这个工厂函数从此没人关，**每个引擎实例固定漏 1 票**，`reset()` 的泄漏报告随之永久带一条
噪音（而泄漏报告要能当判据用，0 条是判据成立的必要条件）。

`init` 是**工厂函数**：JS 侧没有别的引用持有它（native 的 `jsToJava` 对函数分支
`JS_DupValue` 出一票交给调用方），**调用完归还即安全**。

### A/B 实测（commit `2cc467f`）

| 形态 | `reset()` 的 stderr |
|---|---|
| 有 `close()` | **0 字节** |
| 注释掉 `close()` 作对照 | 65 字节，`QuickJS reference leak: JSFunction(refs=1, ptr=…)` |

对照组是为了证明探针检得出这条泄漏 —— 0 不是「报告恰好为空」。

⚠️ **不要把它改成 `use { }`**：`use` 的契约是「拿这个对象去调一个方法」，而`init` 是
**借用一次就还的工厂函数**，`use` 块里的 `it` 只会绑到 `init` 自己，语义反而更费解。
`try/finally` 明确标出那一次借用的生命周期边界。

---

## 四、参照实现的形态（出处）

本工程的 `init.js` 与能力桥形态来自本地只读参照工程
`D:\Work\Self\ACG\.workbuddy\ref\`（**未纳入 git 跟踪**，别指望 clone 后能读到）：

| 参照工程 | 位置 | 对本工程的意义 |
|---|---|---|
| **nezumi** | `js/init.js`、`lib/engine/engine.dart` | 派发形态的来源：`_methodHandler` 是**普通函数 + `switch` 直接调** |
| **BangumiPlugin** | `assets/modules/http.js` | 后台页 JS 契约的来源：`webview(url, header, script, onInterceptRequest)` |
| **animeko** | `AGENTS.md` | 注释规范的来源（见 `coding-style/references/comments.md`） |

⚠️ nezumi 的 `_dart` 依赖 `IsolateQjs`（Dart isolate 桥），而本工程 `init.js` 明确记载
JNI桥上 `import()` / `require()` 会让进程 `abort()` ⇒ **只参考参数传递形态，不照抄机制**。
nezumi 的 `fetch` 也是整包缓冲（dio `ResponseType.bytes`），**流式无参考**。

⚠️ 这些路径**不进代码注释** —— 未跟踪的目录在别人的clone 里不存在，指向它的注释等于
指向一个找不到的东西。出处只留在本文。

---

## 检查清单

1. 加新能力：在 `JsEngine` 里加 `private` 方法 + 在 `when` 加一个分支，**不要**加反射。
2. `args` 是数组，按下标取；`console` 的实参数组整体作为**一个元素**送达；
   `webview` 的 4 个实参也打进 `args`，不是位置参数。
3. `JSInvokable { }` 的 lambda **只能一个值参数** `{ argv -> … }`；
   写 `{ argv, _ -> }` 编译不过。`argv` 是实参数组本体（`Array<out Any?>`），
   **不要**在 companion 里再包一层。
4. 引导函数**只收一个形参** —— 加第二个桥收益只是「少一个 `when` 分支」，不值。
   ⚠️ 这条**由代码评审把关，不进测试套件**（已实测，见第 8 条）。
5. **别再写桩测试** —— `InitJsTest` / `WebviewJsTest` 已删除。桩用 `getOrNull` + 静默默认值，
   形状错了不响，而且它验的是「自己喂进去的东西」，正文改了它不知道。
   改完派发跑 `JsEngineDispatchTest`（零桩、真 `JsEngine`）—— 那是唯一的哨兵。
   真引擎给不了的（宿主平台约定的取值、非法宿主值、回调触发时机的 gate）**先想清楚是不是
   真实能力**；是的话用 WebView2 自己的加载过程当时钟，不是自己攥 `Deferred`。
6. 任何 `as JSInvokable` 用在需要 `close()` 的 `JSFunction` 上都是漏票。
7. 跨语言的数据形状（`FormData` 的 `__js_proto__` / `__items__` / 每项的
   `name` / `value` / `type` / `filename`）**键名要逐字对齐** `Http.kt` 那边。
   错一个字母的后果是**静默降级**（文件名送不到 multipart 头，HTTP 层面仍合法，
   不引起任何异常）。`JsEngineDispatchTest.formDataShapeIsReachable` 锁着这条。
8. **别给「真破坏会自己响」的东西留测试，也别给「换个设计」判红。** 这是删文本断言
   的判据，2026-10-03 逐条实测过：
   - `noDartResidue`（断言 `init.js` 无 `_dart` / `createClass` ＋ 两条源码文本规则）
     —— 已删。其中两条 FormData 规则**本来就不成立**：`for (var x in __items__)`
     与 `__items__.append(` 改回去，`formDataShapeIsReachable` 就红
     （`keys()` 吐出索引字符串 / `TypeError` 让 Promise 拒绝）。实测确认：
     改 `keys()` 后 9 条里**只有它红**。⇒ **文本规则能换成行为断言就换掉**，
     别让它与行为用例并存成双份。
   - `initFactoryTakesOnlyOneBridge`（断言引导函数形参个数**与**名字）—— 已删。
     实测三种改法：

     | 改法 | 行为测试的反应 |
     |---|---|
     | 删掉形参 | **8 条全红**（`_binding` 成 undefined ⇒ 整片崩） |
     | 加第二个形参 | 全绿 |
     | 形参改名（连函数体一起改） | 全绿 |

     前者说明**真破坏本来就响**，文本断言是给已经会响的东西再加一层响；
     后两者**不是缺陷**（多一个桥只是换一种设计，能力照样验过；改名连行为都没变）
     ⇒ 为「有人选了不同设计」判红是越界。那种约定写在**本文清单**里，
     由代码评审把关。
   - `JsEngineDispatchTest` 现在**一条源码文本断言都没有**（9 条全走真派发）。
     「唯一测试面」的说法只对「行为」成立，文本层面没人守 —— 这是有意的。
