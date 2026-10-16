# 能力桥（`_binding`）的形态依据

`JsEngine` 里那个桥为什么长这样、以及**为什么不能用反射**。改 `JsEngine` 的派发、给能力桥加新方法、或排查「`no method '…'`」之前先读本文。代码里只留设计理由（`when` 直接调各能力、私有方法免疫），出处与取证在此。

## 一、能力桥的形态

`init.js` 的引导函数收**一个**桥：

```js
async (_binding) => { /* … */ }
```

形如 `_binding(method, args)` —— **通用派发**，`args` 是那一份实参数组，按名字转发到各能力。桥名不点出宿主语言：现在是 KMP，两端的宿主未必是 JVM。

> **DO:** `args` 是**一个数组**、作为 `_binding` 实参的**元素**整体过桥，不是摊平成位置参数。`console.log(a, b, c)` 送过去是 `_binding('console', ['log', [a, b, c]])`，第二层数组是 `console.log` 自己的实参数组。写成 `['log', a, b, c]` 会让 Kotlin 侧 `console(type, data: Array<Any?>)` 收错形状。各能力的实参一律打进 `args`：`webview` 的 4 个实参也是 `_binding("webview", [url, header, script, onInterceptRequest])`，不是位置参数。加分支时对着 `JsEngine` 的 `when` 逐个核下标。

> **DO:** 把「桥本身的形状」当真正的静默风险来查，而不是下标错位——下标错位实测是**响**的（相邻下标类型对不上就 `ClassCastException`），真正静默的是 `args` 被多包一层时 `argv[1]` 只是越界/类型不符、而桩测试全绿。

> **DON'T:** 让 `JSInvokable` companion 把 `argv` 多包一层（`block(arrayOf(argv))`）。这是为绕 `Array<out Any?>` 不能当 `Array<Any?>` 传（Kotlin 数组不变）的类型问题，但包出来语义错：`argv[1]` 越界、`argv[0] as String` 拿到数组。正确解法是把 block 的入参类型改成投影 `Array<out Any?>`（companion 的 KDoc 已记）。这个错只有真派发才暴露——桩测试自己实现 `JSInvokable`、直接拿 `argv`，所以老测试全绿；`JsEngineDispatchTest` 第一次跑就撞上。

Kotlin 侧对应 `JSInvokable { argv -> … }`：**lambda 只有一个值参数**，`thisVal` 是隐式接收者、不占参数位，而 `argv` 是 `vararg` 打包成的实参数组（**原样**，不额外包层）。

> **DO:** 实参走 `args` 数组**不改变所有权语义**（查 `QuickJS.jsToJava`）：函数元素与顶层 `argv` 走同一函数分支，产出的 `JSFunction` 一票归「拿到它的人」`free()`；数组元素为 JS `null` 时得 Java `null`，与顶层位置参数同路。

## 二、为什么不用反射

**约束：反射派发在这条链路上是错的、不是慢——是查不到。** `Class.getMethods()` **只返回 public 方法**；private 的只在 `getDeclaredMethods()` 里。而各能力方法全是 `private`，所以按名字找的写法：

```kotlin
val candidates = objWrap.javaClass.methods.filter { it.name == methodName && … }
// private 的能力一个都查不到 ⇒ candidates 恒空 ⇒ 抛 no method '…'
```

⇒ `fetch` / `text()` / `encodeURI` / `webview` 四条链路**一律不可达**。

> **DO:** 现在的 `when(method)` **直接调**各能力，没有「按名字找方法」这一步 ⇒ 可见性不再影响能否被调用。方法名写错是**编译错误**，而反射版写错要等运行期。

`javap` 对编译产物佐证各能力为 `private`（`soko.ekibun.acg.engine.JsEngine`）：`private final void console(java.lang.String, java.lang.Object[])`、`private final byte[] encode(java.lang.String, java.lang.String)`、`private final java.lang.String decode(byte[], java.lang.String)`、`private final Deferred<…> fetchAsync(java.util.Map<…>)`、`private final Deferred<…> webviewAsync(…)`。

> **DON'T:** 断言 JS 返回值时套 `JSON.stringify`。真派发的返回路径就是 `jsToJava`（`cxx/quickjs/quickjs.cpp`）：JS 普通对象整图展开成 `java.util.LinkedHashMap`、数组展开成 `List`（`ArrayList`）、`ArrayBuffer` 变 `ByteArray`。测试里直接 `return {…}`，Kotlin 侧 `assertIs<Map<*, *>>` / `assertIs<List<*>>` 即可；套了 `JSON.stringify` 得 `String`（`assertIs<Map>` 当场红），且 `ArrayBuffer` 降级成 `{}`、丢掉「有长度的二进制缓冲」信息。

### 三条只有真派发才暴露的写法约束

- **`JSInvokable` companion 里不能写 `thisVal?.block(argv)`。** 接收者声明成可空是**刻意的**（JS 裸调 `f()` 时 `this === undefined`，经 `jsToJavaScalar` 的 `default: return nullptr` 落到 Kotlin 就是 `null`，属正常语义），但可空接收者的函数类型走 `invoke` 协议、编译器不插空检查——加 `?.` 会让 block 整个不执行且**编译照过**。正确写法是 `thisVal.block(argv)`：编译器在这一点上帮忙，`this.ptr` 之类直接编译不过（`reference has a nullable type`），比运行期炸好。
- **`init.js` 的 `FormData.set` 只能对 `__items__` 用 `splice`**，不能写 `this.splice`（后者静默无效）。
- **`init.js` 的脚本结果分支没有「非 JSON 就原样返回」兜底，这是故意的**：宿主给的 `json` 一定是 JSON（WebView2 的 `ExecuteScript` 自己序列化，裸文本会加引号变合法 JSON，到不了这里），而 `webview.cpp` 没脚本时回空串——所以只需判 `json == null || json === ""`，与 `http.js` 的 `it = it && JSON.parse(it)` 对齐。加兜底只掩盖「宿主给了非 JSON」这个真问题。

## 三、`init` 工厂函数的引用计数

```kotlin
val init = runBlocking { ctx1.evaluate(moduleHandler("@init")!!, "<init>") } as JSFunction
try {
  init(JSInvokable { … })
} finally {
  init.close()
}
```

> **DO:** 必须 `as JSFunction`，不能 `as JSInvokable`——后者丢掉 `AutoCloseable`，这个工厂函数从此没人关，**每个引擎实例固定漏 1 票**，`reset()` 的泄漏报告随之永久带一条噪音（而泄漏报告要能当判据用，0 条是判据成立的必要条件）。

`init` 是**工厂函数**：JS 侧没有别的引用持有它（native 的 `jsToJava` 对函数分支 `JS_DupValue` 出一票交给调用方），**调用完归还即安全**。

> **DON'T:** 把它改成 `use { }`。`use` 的契约是「拿这个对象去调一个方法」，而 `init` 是**借用一次就还的工厂函数**，`use` 块里的 `it` 只会绑到 `init` 自己，语义反而更费解；`try/finally` 明确标出那一次借用的生命周期边界。

## 四、参照实现的形态（出处）

本工程的 `init.js` 与能力桥形态来自本地只读参照工程 `D:\Work\Self\ACG\.workbuddy\ref\`（未纳入 git 跟踪，别指望 clone 后能读到）：

| 参照工程 | 位置 | 对本工程的意义 |
| --- | --- | --- |
| nezumi | `js/init.js`、`lib/engine/engine.dart` | 派发形态来源：`_methodHandler` 是普通函数 + `switch` 直接调 |
| BangumiPlugin | `assets/modules/http.js` | 后台页 JS 契约来源：`webview(url, header, script, onInterceptRequest)` |
| animeko | `AGENTS.md` | 注释规范来源（见 [coding-style 技能](../../.agents/skills/coding-style/SKILL.md)） |

> **DO:** 只参考参数传递形态，不照抄机制。nezumi 的 `_dart` 依赖 `IsolateQjs`（Dart isolate 桥），而本工程 `init.js` 明确记载 JNI 桥上 `import()` / `require()` 会让进程 `abort()`；nezumi 的 `fetch` 也是整包缓冲（dio `ResponseType.bytes`），流式无参考。出处只留在本文、不进代码注释（未跟踪目录在别人 clone 里不存在）。

## 五、桩测试验错了东西

桩用 `getOrNull(i)` + 静默默认值（`?: ""` / `?: emptyArray()` / `?: Charsets.UTF_8`）：形状错了不响、只吃到默认值；而真派发是硬下标 + 硬转型，错位当场抛。桩的分支只消费参数、不断言形状——验的是「桩自己喂进去的东西」，正文改了它不知道。删桩也暴露了两条以前被挡着的真分支：①「没传脚本」那支 Kotlin 侧直接发 `ScriptFinished(json = null)` 触发 `init.js` 的 `json == null`/`json === ""` 判定；② 可见视图链路上 WebView2 把 `undefined` 序列化成 `"null"`，后台链路只判 `null`/`""` 不判 `"null"`。

> **DON'T:** 把桩的时序当成真实能力保护。桩版 `interceptCallbackCanBeInvokedFromKotlin` 断言「脚本挂在 `await webview(...)` 上、Kotlin 用手攥的 `CompletableDeferred` 决定放行」——那个闸门只有桩有，真实实现走不到该时序（实测挂满 608s）。`grep -n "Stop()" cxx/webview/webview.cpp` 一次就能确认：回调返回命中 ⇒ native 立刻给空 204 并 `webview_->Stop()`，整轮加载中止；`loadBackgroundWebView` 的 `outcome.complete` 幂等、只取第一个终态，所以「命中」与「放行」在真引擎里互斥。真等价覆盖是 `WebViewHostTest.releasingEveryRequestLetsPageFinish`：接了回调、每次都放行（一次都不命中），页面照样跑完、整轮以 `Scripted` 收场。

> **DON'T:** 把桩时期「命中后放行」的时序翻译成真引擎用例——它会在真实现里必红（整轮以 `Intercepted` 收场），因为命中与放行互斥。迁条目前先问「真实现里这条时序存在吗」。

> **DON'T:** 给「真破坏会自己响」的东西留文本断言（如锁引导函数形参个数/名字的 `initFactoryTakesOnlyOneBridge`）。删形参会让 8 条行为测试全红（真破坏本来就会响），而加形参/改名全绿（那不是缺陷、是换设计）——为「有人选了不同设计」判红是越界。这条由代码评审把关，不进测试套件。文本规则能换成行为断言就换掉（如 `formDataShapeIsReachable` 锁 FormData 键名），别让它与行为用例并存成双份。

## 检查清单

1. 加新能力：在 `JsEngine` 里加 `private` 方法 + 在 `when` 加一个分支，**不要**加反射。
2. `args` 是数组，按下标取；`console` 的实参数组整体作为**一个元素**送达；`webview` 的 4 个实参也打进 `args`，不是位置参数。
3. `JSInvokable { }` 的 lambda **只能一个值参数** `{ argv -> … }`；写 `{ argv, _ -> }` 编译不过。`argv` 是实参数组本体（`Array<out Any?>`），**不要**在 companion 里再包一层。
4. 引导函数**只收一个形参**——加第二个桥收益只是「少一个 `when` 分支」，不值。这条**由代码评审把关，不进测试套件**。
5. **别再写桩测试**——`InitJsTest` / `WebviewJsTest` 已删除。桩用 `getOrNull` + 静默默认值，形状错了不响，且它验的是「自己喂进去的东西」，正文改了它不知道。改完派发跑 `JsEngineDispatchTest`（零桩、真 `JsEngine`）——那是唯一的哨兵。真引擎给不了的（宿主平台约定的取值、非法宿主值、回调触发时机的 gate）**先想清楚是不是真实能力**；是的话用 WebView2 自己的加载过程当时钟，不是自己攥 `Deferred`。
6. 任何 `as JSInvokable` 用在需要 `close()` 的 `JSFunction` 上都是漏票。
7. 跨语言的数据形状（`FormData` 的 `__js_proto__` / `__items__` / 每项的 `name` / `value` / `type` / `filename`）**键名要逐字对齐** `Http.kt` 那边。错一个字母的后果是**静默降级**（文件名送不到 multipart 头，HTTP 层面仍合法，不引起任何异常）。`JsEngineDispatchTest.formDataShapeIsReachable` 锁着这条。
8. **别给「真破坏会自己响」的东西留测试，也别给「换个设计」判红。** 文本规则能换成行为断言就换掉，别让它与行为用例并存成双份。`JsEngineDispatchTest` 现在一条源码文本断言都没有（9 条全走真派发）；「唯一测试面」只对「行为」成立，文本层面没人守——这是有意的。
