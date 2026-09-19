# QuickJS JNI 引用所有权（Kotlin/KMP）

讲**运行时所有权模型** —— 也就是只以"测试进程 `abort()` 掉"的形式表现出来的那部分。
构建相关的部分见 `cxx/AGENTS.md`。

---

## 致命失败模式

下面这些会崩掉**整个进程**，所以 Gradle 只报一句结果、没有任何用例细节。必须能从事后日志里认出来。

| `quickjs.c` 里的断言 | 含义 |
|---|---|
| `assert(list_empty(&rt->gc_obj_list))`（约 `:2464`，在 `JS_FreeRuntime`） | **泄漏。** 释放 runtime 时还有活引用。 |
| `assert(js_rc(p)->ref_count > 0)`（约 `:6689`，在 `gc_decref_child`） | **过度释放。** 多减了一次 `JS_FreeValue`。 |
| `assert(js_rc(p)->ref_count == 0)`（约 `:6425`） | **动态 `import()` / 模块加载。** 本桥的模块加载路径不是引用计数干净的。模块**不存在**时撞这个断言；模块**存在**时改为在 `JS_FreeRuntime` 撞 `gc_obj_list`。两条路都一样：**本桥上不要用 `import()` / `require()`，把能力内联进引导脚本。** |

Gradle 日志里的样子：

```
Assertion failed: js_rc(p)->ref_count > 0, file .../quickjs.c, line 6689
org.gradle.internal.remote.internal.MessageIOException: Could not write ...
Caused by: java.io.IOException: Connection reset by peer
> Task :shared:jvmTest FAILED
```

那个 `Connection reset by peer` 是 JVM 被 `abort()` 杀掉，**不是** Gradle/网络问题。别去查它。

---

## 规则 1 —— 把"放掉一个 JS 引用"和"删掉包装"拆开

一个跨 JNI 传递的句柄是 `new JSValue(...)` —— 一个**堆包装** + 一个 **JS 引用**。
两者生存期独立，所以需要两个函数：

```cpp
void jsReleaseValue(jlong ctx, jlong obj) {           // == JS_FreeValue
  if (obj == 0) return;
  JS_FreeValue((JSContext *) ctx, *((JSValue *) obj));
}
void jsDestroyHandle(jlong obj) { delete (JSValue *) obj; }
```

把两者合成一个 `jsFreeValue` 正是让 `JS_DefinePropertyValue` 不可能写对的原因 ——
释放包装顺带把引用计数也减了，于是任何"只想退休自己的包装"的调用点都会静默偷走一次引用。

（上游 flutter_qjs 的 `cxx/ffi.cpp` 恰恰就是**一个** `jsFreeValue(ctx, v, int32_t free)`，
第三参为 `1` 时顺带 `delete v`。两种拆法本身都能用，但要让**调用点的口径一致** ——
混着来就是上面那个坑。本工程选的是拆成两个。）

Kotlin 侧**只暴露一个**释放出口，让调用点没有选择余地：

```kotlin
internal fun releaseValue(handle: Long) {
  if (handle == 0L) return
  // ⚠️ 别照抄成裸调两个 native 函数：`closed` 的判断在 onJsThreadQuietly 里面，
  // 整段还要回 JS 线程（理由与取舍见规则 7）。仓库现状是下面这样。
  onJsThreadQuietly {
    jsReleaseValue(ptr, handle)
    jsDestroyHandle(handle)
  }
}
```

---

## 规则 2 —— `JS_DefinePropertyValue` 消费 `val`、借用 `obj`

它的函数体结尾**无条件**调 `JS_FreeValue(ctx, val)`。所以：

```cpp
Java_..._definePropertyValue(JNIEnv *, jclass, jlong ctx, jlong obj, jlong k, jlong v, jint flags) {
  auto atom = JS_ValueToAtom((JSContext *) ctx, *(JSValue *) k);
  auto ret = JS_DefinePropertyValue((JSContext *) ctx, *(JSValue *) obj, atom,
                                    *(JSValue *) v, flags);
  JS_FreeAtom((JSContext *) ctx, atom);
  jsReleaseValue(ctx, k);   // JS_ValueToAtom 只是借用了 k -> 这一票归我们放
  jsDestroyHandle(k);
  jsDestroyHandle(v);       // v 的 JS 引用已被被调方消费掉，只剩包装 -> delete
                            // 不要再 jsReleaseValue(ctx, v) —— 那就是双重释放
  return ret;
}
```

`JS_DefineProperty` 对 `this_obj` 只是**借用**；它自己存了一份属性的引用。
所以**不要**在这里释放 `obj`。

其他值得钉死（均已对 `quickjs.c` 核过）的所有权事实：

- `JS_Call`、`JS_Eval`、`JS_GetProperty`、`JS_GetPropertyStr`、`JS_GetPropertyUint32`
  → 返回一个**新的、由调用方拥有**的引用。
- `JS_NewCFunctionData` → 内部会 `JS_DupValue(ctx, data[i])`；调用方自己那几票仍然归调用方放。
- `JS_NewPromiseCapability` → 写进 `resolving_funcs` 的两个值是**交给调用方**的；
  `js_promise_resolve` 会用 `JS_FreeValue` 释放它们。

---

## 规则 3 —— JS→Java 只能有一个分发点，否则递归调用点会丢对象

陷阱：一个标量转换函数（`bool`/`int`/`string`/`double`）**没有对象分支**，
所以任何经它走一趟的对象都会变成静默的 `nullptr` → Java 侧 `null`。
数组元素和 promise 的 `then` 正是这样的递归点。
症状：一堆互不相关的用例同时挂掉，报 `promise has no callable then` /
`null cannot be cast to non-null type Array`。

修法是让分发函数成为唯一入口，由它再扇出：

```cpp
static jobject jsToJavaScalar(JNIEnv *, JSContext *, JSValue);      // 非对象
static jobject jsToJavaObject(JNIEnv *, JSContext *, JSValue, std::unordered_map<void*,jobject>&);

jobject jsToJava(JNIEnv *env, JSContext *ctx, JSValue obj,
                 std::unordered_map<void *, jobject> cache = {}) {
  if (JS_VALUE_GET_TAG(obj) == JS_TAG_OBJECT)
    return jsToJavaObject(env, ctx, obj, cache);
  return jsToJavaScalar(env, ctx, obj);
}
```

之后递归点一律调 `jsToJava(env, ctx, elem, cache)`，**永远不要**直接调标量那个。

---

## 规则 4 —— 普通对象**整图展开**成纯数据；函数是唯一保留的包装

`jsToJavaObject` 对普通对象**不造包装**，而是递归填一个 `java.util.LinkedHashMap`：

```cpp
auto map = env->NewObject(env->FindClass("java/util/LinkedHashMap"), ...);
cache[ptr] = map;                      // ⚠️ 必须在**填之前**登记
for (每个自有属性) {                     // JS_GetOwnPropertyNames + JS_GetProperty
  map->put(jsToJava(jsKey, cache), jsToJava(jsVal, cache));
}
return map;
```

- **`cache[ptr] = map` 必须在填之前**：递归回自身时命中的正是那个**还在填的** `map`，
  于是 `a['a'] === a` 天然成立。填完再登记就晚了 —— 环上会无限递归。
- `cache` 是**每次调用新建**的局部变量（`jsToJava` 的默认形参 + `jsToJavaEntry`），
  只在一次遍历内有效。这不是权宜之计：产物既然是纯数据，就**不存在**"跨调用的对象身份"。
- 展开出来的东西与 JS 侧**脱钩**：改 `Map` 不会影响 JS，也没有 `close()` / 引用计数这回事。
- 数组走 `Object[]`，规则同上。
- **函数是唯一的例外**：`JSFunction` 是可调用的活对象，展开成数据没有意义，所以仍然造包装、
  持一票、要 `close()`。`jsToJavaObject` 里 `JS_IsFunction` 单独一个分支就是为它。
- promise 同理不展开：它要等 `then` 回调，落在这个位置的值是个 `Deferred` —— 上游
  `_jsToDart` 是 `completer.future`（Dart 的 `Future` 对应 Kotlin 的 `Deferred`），
  两边是同一件事。
- ⚠️ **别删 cache 里那些 JNI local ref**。`a['a'] = a` 时递归交还的 `jVal` 就是 `map`
  自己，对它 `DeleteLocalRef` 等于把要返回的引用一并销毁 —— 表现是**静默变成 null**
  （不抛异常、不崩溃，值就没了）。2026-09-20 实测：环形对象整个转成 null，
  而普通对象一切正常，非常容易误判成"展开没写对"。
  判据：递归**之后** `cachedRefExists(cache, v)` 为真就别删 —— 进 cache 的只有**容器**
  （数组、普通对象，以及 promise 那个 `Deferred`）；**函数包装不进** cache（理由见下一条），
  标量 / 字符串也不进 —— 这两类照删，别让 local ref 白涨。
- ⚠️ **函数包装不进 cache —— 这条是 2026-09-20 用一次真实 bug 换来的**。
  上游 `_jsToDart` 的函数分支是直接 `return _JSFunction(ctx, val)`，只有数组和普通对象
  两支才写 `cache[valptr] = ret`。本工程上一版（ff531fe）给函数也写了
  `cache[ptr] = wrapper`，看着像「顺手保留身份」，实际是把「谁负责 `free()`」变成了悬案：
  同一个 `Promise.prototype.then` 会被数组里每个 promise 取到，而 Kotlin 的
  `wrapJSPromiseAsync` 拿到它就当是自己的、调用完立刻 `close()` —— 第一个 promise
  一 `close`，后面的 promise 命中 cache 拿到的就是**已关闭**的包装 → 症状是
  `TypeError: not a function`（同一用例重跑 3 次挂 2 次；之所以偶发，是因为已析构的
  `JSValue` 内存还没被覆写时，看着仍像函数）。
  当时的修法是加个 `jsToJavaOwnedFunction()` 只为 `then` 绕开 cache —— 那是**治标**：
  根因就是多写的那行登记，删掉它，`then` 天然独占，那个函数也就不需要了。
  **代价**：同一个函数出现在两个位置会得到两个 `JSFunction`（上游同样如此），换来一条
  干净规则 —— **每个函数包装都是独占的一票，谁拿到谁还**。
  **判据**：谁 `close()`，谁就必须是那唯一的主人。只要存在「某个能被 `close()` 的包装
  可能被共享」，这共享就是错的。
- ⚠️ **`cache` 必须由整棵图共用同一张表**。本工程用指针传，空指针表示"最外层自己开一张"。
  按值传的话递归拿到的是副本，新登记的条目回不到上层：环还能靠"登记早于复制"侥幸命中，
  但**共享子对象**（`a.b = c; a.d = c`）会被转成两份不同的副本，身份当场断掉。
  上游 `_jsToDart` 传的是同一个 `Map` 对象，也是这个道理。

### 别走回头路：曾经那张「持久登记表」

2026-09-19 之前这里写的是「对象身份要用持久登记表」—— Kotlin 侧维护 `HashMap<Long, Any>`，
native 回调 `peekWrapper` / `registerWrapper` / `reuseWrapper`，为的是让 `obj["a"]` 这种
**惰性代理**模型下的跨调用身份成立。改用整图展开之后，那一整套被整体删掉了。

删它的理由不是"能省则省"，而是它**必然引入**一组自己造出来的坑：每轮转换都得给调用方记一票
（`reuseWrapper` 里的 `dup()`）、包装销毁时要撤登记（否则指针复用后映射到已销毁对象）、
promise 必须排除在表外……而这些在 `_jsToDart` 的模型里**一个都不存在**。

**判据**：如果你发现自己在给"同一个 JS 对象 → 同一个 Java 包装"维护一张活过单次调用的表，
先问一句「产物为什么不是纯数据」。

### 与 flutter_qjs 的转换模型（现在两边一致）

`flutter_qjs` 的 `_jsToDart` 就是**一次把整个对象图**转成原生 Dart `Map`/`List`，
`cache[valptr] = ret` 只在那一次转换里有效 —— 所以 `a['a'] === a` 靠 per-pass cache 就成立。
本工程的 `jsToJava` 现在对齐这个模型，上游 `flutter_qjs_test.dart` 的
`expect(wrapA['a'], wrapA, reason: 'recursive object')` 断言的正是这件事。

函数分支两边也一致：**都不写回 `cache`**（上游直接 `return _JSFunction(ctx, val)`，
本工程的 `jsToJavaObject` 同理）。于是同一个函数出现在两个位置时，两边都得到两个包装 ——
这是**故意**的：包装是独占的一票，谁拿到谁还，谁也不能替别人 `close()`。

另一处：`javaToJsImpl` 用于断环的 cache **必须是 `IdentityHashMap`**。
普通 `HashMap` 会对**键**调 `hashCode()`/`equals()`，而自引用 Map（`a["a"] = a`）
在算哈希时无限递归 → `StackOverflowError`。

---

## 规则 5 —— `Object.finalize()` → `java.lang.ref.Cleaner`

`finalize()` 跑在任意 GC 线程上（无线程安全），且自 JDK 18 起已被标记为待移除。

```kotlin
private val cleaner = Cleaner.create()

private class Reachable {                       // 绝不持有 Context 的引用！
  @Volatile var ptr: Long = 0
  private val armed = AtomicBoolean(true)
  fun disarm(): Long? = if (armed.compareAndSet(true, false)) ptr else null
}
private lateinit var runtimeHandle: Reachable

init {
  val dispatcherRef = dispatcher               // 只捕获「值」，绝不捕获 `this`
  val reachable = Reachable().also { it.ptr = ptr }
  runtimeHandle = reachable
  cleaner.register(this) {
    val handle = reachable.disarm() ?: return@register
    runCatching { runBlocking(dispatcherRef) { destroyContext(handle) } }
  }
}

private fun destroyRuntimeOnce() {
  val handle = if (::runtimeHandle.isInitialized) runtimeHandle.disarm() else null
  if (handle != null) destroyContext(handle)
}
```

不那么显然的要求：

- **清理动作不能捕获被登记的对象。** 一旦捕获，该对象永远可达，cleaner 永不触发。
- 动作跑在**守护线程**上 —— 每个 native 调用都必须回 `dispatcher`。
- `cleaner.register(o, action)` 是**两个参数**；`cleaner.register(this) { ... }` 用的是 SAM 转换。
- `close()` 与 cleaner 是通往 `JS_FreeRuntime` 的**两条独立路径**，而它**不可重入** ——
  CAS 的 `disarm()` / `destroyRuntimeOnce()` 守卫是必须的，否则就是双重释放。
- Kotlin：`lateinit` 不能先赋值再声明；`open` 属性不允许 `private setter`。

---

## 规则 6 —— 把泄漏变成可捕获的异常，且**先记录再归还**

`JS_FreeRuntime` 里那句 `assert(list_empty(...))` 是 `abort()`。
照 flutter_qjs 的两阶段清理来写，泄漏就变成可以断言的异常：

```kotlin
internal fun collectLeaks(): List<String> {
  val snapshot = refs.toList()      // refs = 强引用身份集
  val leaked = snapshot.map { "  ${it.describe()}" }   // 1. 先记录
  snapshot.forEach { it.release() }                    // 2. 再归还
  refs.clear()
  return leaked
}
```

顺序是承重的：**记录**本身就是泄漏的定义（此刻还在册 = 调用方没还），
**归还**是为了不让它升级成 `JS_FreeRuntime` 的 `abort()`。
`close()` 打印；`closeAndCheckLeaks()` 抛 `JSError("reference leak:\n...")`。

⚠️ **别拿测试 XML 里 `reference leak` 的出现次数当泄漏数**：`JSError` 的 `init` 里就有
`printStackTrace()`，凡 `assertFailsWith<JSError>` 的用例都会往 `<system-err>` 留一份。
本仓有两条**故意**泄漏的用例（`QuickJSTest.referenceLeak` 的 1 个 `JSFunction`、
`unclosedValuesAreSweptByCloseAndCheckLeaks` 的 64 个 `JSFunction`），所以基线恒为 **2**。
（那 64 条**必须**用函数造：普通对象整图展开之后是纯数据、没有票可漏，拿它造不出泄漏。）
真正的意外信号是 **`close()` 路径**打印的 `QuickJS reference leak`（`QuickJS.kt`），它必须是 0。

`close()` 里还有两个坑：

- 清理必须在 `closed = true` **之前**跑，因为 `releaseValue` 在 closed 之后会拒绝工作 ——
  否则整轮归还是空操作，残留反而撑到 `JS_FreeRuntime` 去 abort。
- `refs` 必须是**强引用**身份集
  （`Collections.newSetFromMap(IdentityHashMap<JSValue, Boolean>())`）。
  用 `WeakHashMap<Long, JSValue>`（键是 native 指针）会同时踩两个雷：
  指针复用会覆盖条目、条目会被 GC 静默清掉，于是清理时根本找不到泄漏的那一个。

---

## 规则 7 —— native 访问只能在 JS 线程上

QuickJS 是**单线程**的：引用计数是裸 `int`、GC 链表与 Shape 哈希链都无锁。只要有两个线程
同时进同一个 `JSRuntime` 就会丢更新 —— 要么提前释放（`EXCEPTION_ACCESS_VIOLATION`，
崩在 `get_shape_prop` / `list_del` 这类地方），要么漏减（对象/Shape 卡在 `gc_obj_list`，
`JS_FreeRuntime` 的断言 `abort()`）。**症状是偶发崩、重跑就变绿**，因此极易被误判成环境问题。

`Context` 只有一个专用线程（`dispatcher`），两类入口分工不同：

| 入口 | 用哪个 | 关闭之后 |
|---|---|---|
| 会**返回**东西的调用（`jsCallImpl`、`toJava`、`evaluate`…） | `runOnDispatcher` | 抛 `IllegalStateException` |
| 只**归还 / 撤登记**的收尾动作（`releaseValue`、`release()`） | `onJsThreadQuietly` | 静默跳过 |

- **返回值与转换结果也要留在 dispatcher 上**：`jsToJava` 要递归遍历整个对象图，全都在碰
  这个 runtime。只把调用收回 dispatcher、把转换丢在调用方线程上，就是历史上那个偶发崩溃的
  根因（调用 → 转换 与 `executePendingJob` / GC 并发）。`JSFunction.invoke` 因此把两步
  包在同一个 `runOnDispatcher` 里 —— 调用与转换之间不再有线程切换的缝。
- `releaseValue` / `release()` 的调用方可以是**任意线程**（`close()` 出现在 `finally` 里）；
  非 JS 线程时它们要 `runBlocking` 一次调度 —— **UI 线程 `close()` 会等一次调度，这是明确
  接受的取舍**，别改成「异步投递、投递完就返回」（runtime 可能已经被销毁）。
- 计数与「已销毁」标记也必须原子（`AtomicInteger` / `AtomicBoolean` + `compareAndSet`）：
  `free()` 可能来自**任意线程**（`JsEngine` 把 JS 实参交给 `job.invokeOnCompletion` 归还，
  那条回调跑在协程调度线程上），而关闭时的两阶段清理又会在 JS 线程上再 `release()` 一次。
  普通 `Int` / `Boolean` 会丢更新 —— 少一次计量就是提前释放（use-after-free），
  少一次归还就是 `gc_obj_list` 断言。
- 判据不是「重跑变绿」，而是**跨线程并发进 JS 的次数归零** —— 那就要插桩计数。
  注意 Kotlin 侧的 `System.err.println` **不出现在控制台**：Gradle 默认不转发测试进程的
  stdout/stderr，要去 `shared/build/test-results/jvmTest/*.xml` 的 `<system-err>` 里数
  （native 侧 `fprintf` 走的是另一条路，见下节）。

---

## 调试手法

### 追一个 native abort

`.cpp` 里的 `fprintf(stderr, ...)` **不会**出现在 JUnit XML 的 `system-err` 里，
它落在 Gradle 的原始日志里：

```bash
./gradlew.bat :shared:jvmTest --tests '...' --console=plain > build/dbg.log 2>&1
grep -E '\[dbg\]' build/dbg.log
```

- grep `Assertion` / `abort` / `Connection reset` 定位失败点。
- **绝不要因为你的 grep 没输出就认为构建成功。** 用 `grep -E 'error C'` 会漏掉 `error:`
  和本地化的 `错误 1`。看 `tail`，或者匹配 `error|错误`。
- `cmake --build` 只打印 `Built target quickjs` 而没有任何编译行时，改动没被吃到 —— `touch` 那个 `.cpp`。
- 从 XML 读用例结果而不是控制台：`shared/build/test-results/jvmTest/TEST-<class>.xml`
  （遍历 `testcase`，看有没有 `failure` 子节点）。
- abort 会截断整个套件，所以"只有 N/M 条跑了"本身就是信号。把可疑的那条单独跑；
  单独跑能过就说明是累积/时序性泄漏，不是这条用例本身的问题。
- `--rerun-tasks` 会改变执行顺序，可能暴露只在特定顺序下出现的泄漏；判定全绿前多跑几轮。

### 不崩溃但毫无进展：重入调用 JS 函数会把 promise 挂死

这里最恶心的失败模式不是 abort 而是**静默挂死** —— 测试框架什么都不报
（没有日志、没有 XML，Gradle 就是不说话了），把整个构建超时烧掉。

复现条件：Java 侧的 `JSInvokable` 处理器拿到一个 JS 回调作为参数，
然后**在自己的调用内部同步调用它** —— 即 native 的 `evaluate` / `jsCall` 还没返回时就
`fn.invoke(...)`。外层的 `await` 于是永远不会结清。

先诊断，别动代码：

```powershell
# 1. worker 是忙还是阻塞？CPU 时间几乎不涨 = 阻塞。
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -like '*-Dorg.gradle.internal.worker.tmpdir*' }
# 2. 抓栈（重定向到文件再读 —— 工具 stdout 不回显）
& "$jdk\bin\jstack.exe" <pid> | Set-Content build\threaddump.txt -Encoding UTF8
```

`jstack` 里挂死的样子：测试线程停在你自己 `await` 下面的 `BlockingCoroutine.joinBlocking`，
QuickJS 派发线程**空闲在 `LinkedBlockingQueue.take`**，且**没有任何线程在 native 帧里** ——
即运行时没在跑，一个 JS job 都没被驱动过。

另一个有用的迹象：杀掉卡住的 worker 后，Gradle 会补写部分 XML，
其中在飞的那条带 `<skipped/>`、`time` 等于挂死时长。

规则：**JS 回调要从平台线程调用，时机是"JS 协程已挂在 `await` 上、派发线程空闲"的时刻。**
生产代码也是这个顺序（WebView 拦截器回调、事件监听器），所以要测真实顺序，不要测重入顺序。

测试卫生的推论：任何 await promise 的地方都加超时 ——
`runBlocking` 里套 `withTimeout(15_000) { ... }`，把"永久挂起"变成一条带信息的失败用例。
一次挂死的代价比一次红灯大一个数量级。

### `JS_TAG_OBJECT` 就是 `-1`，别把它当成异常

`JSValue` 是 `struct { JSValueUnion u; int64_t tag; }`，在 `JS_PTR64` 下
（正常的 64 位情形；`JS_NAN_BOXING` 此时**未**启用）有
`JS_VALUE_GET_TAG(v) == ((int32_t)(v).tag)`。
`JS_TAG_OBJECT = -1`，所以调试打印里看到 `tag=-1` 是一个**完全健康的对象**。
`JS_TAG_EXCEPTION = 6`。得出"tag 为 -1 说明有异常"会把你带上一条很长的错路。

### `JS_GetArrayBuffer` 对非 ArrayBuffer 一定会抛

它内部调 `js_get_array_buffer`，后者会 `JS_ThrowTypeErrorInvalidClass(...)`。
只拿它当类型探针用时，**必须**把挂起的异常清掉，否则会毒化之后每一个
"返回 NULL 但不抛"的 API：

```cpp
uint8_t *buf = JS_GetArrayBuffer(ctx, &size, obj);
if (!buf) JS_FreeValue(ctx, JS_GetException(ctx));
```

重构时删掉这个探针，会换来一波莫名其妙的失败（`promise has no callable then` 等）。

---

## 移植检查清单

1. native 侧两个释放函数；Kotlin 侧只有一个 `releaseValue`。
2. `definePropertyValue` 释放 `k`、不释放 `v`；两个包装都 delete。
3. 唯一的 `jsToJava` 分发点；递归点全部走它。
4. 普通对象整图展开（`cache[ptr]` 要在**填之前**登记）；**函数不进 `cache`**（包装独占，
   谁拿到谁还）；promise 展开成 `Deferred`（上游是 `Future`）；java→js 的 cache 用 `IdentityHashMap`。
5. `Cleaner` 的清理动作只捕获值，加一次性 CAS 守卫。
6. `collectLeaks()` 先记录再归还；`refs` 用强引用身份集。
7. 验证方式：把**上游**的 `assert` 还原（删掉你临时加的 `fprintf` / `abort` 诊断）后
   套件仍然全绿 —— 这才说明 bug 是真修好了，而不是被诊断代码掩盖了。
