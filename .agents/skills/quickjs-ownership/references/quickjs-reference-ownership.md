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

Kotlin 侧**只暴露一个**释放出口，让调用点没有选择余地：

```kotlin
internal fun releaseValue(handle: Long) {
  if (handle == 0L) return
  if (closed) return
  jsReleaseValue(ptr, handle)
  jsDestroyHandle(handle)
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

## 规则 4 —— 对象身份要用**持久**登记表，而不是每次调用的 cache

`jsToJava` 里的 `cache[ptr] = wrapper` 是**单次调用的局部变量**，只能给"一次遍历之内"的身份。
如果 API 是 `obj["a"]` 这种"每次访问都是一次全新转换"，那么 `a["a"] === a`
就需要一张活得比调用更久的表，键为 `JS_VALUE_GET_PTR(obj)`。

把它放在 Kotlin 侧（一个 `HashMap<Long, Any>`），由 native 回调：

```
peekWrapper(ptr) -> Any?            // 已有的包装，没有则 null
registerWrapper(ptr, wrapper)       // 登记刚造出来的包装
reuseWrapper(wrapper, dupHandle)    // 命中：releaseValue(dupHandle) 还掉 native 多给的那份，
                                    // 再 dup() 给调用方单独记一票，返回同一个包装
```

- **每轮 `jsToJava` 转换都给调用方一票，命中已有包装时也不例外**（就是上面那个 `dup()`）。
  漏掉它**不会报错**：调用方 `free()` 减的是**别人的**票，谁先还谁就把别人手里的包装一并销毁
  （use-after-free）；而「干脆不还」则变成每轮在 `refs` 上挂一笔。2026-09-19 修的就是这一处。
- 包装被释放时**要撤掉登记**（`forgetWrapper`），否则被复用的 native 指针会映射到一个已销毁的对象上。
- **不要把 promise 放进这张表**：它的包装由 `then` 回调驱动，而那个回调在调用结束后就退休了，
  重放缓存条目会得到一个 null 的 `then`。promise 只交给调用内的 `cache`。

### 与 flutter_qjs 的转换模型差异（这点很坑人）

`flutter_qjs` 的 `_jsToDart` 是**一次把整个对象图**转成原生 Dart `Map`/`List`，
`cache[valptr] = ret` 只是那一次转换里的局部变量。所以它的 `a['a'] === a` 靠 per-pass cache 就成立。

本工程不同：`obj["a"]` 每次都是**一轮独立的 `jsToJava`**，native 的 cache 是每次新建的局部变量。
**不要因为"flutter_qjs 用一个局部 cache 就做到了"而推断我们也行** —— 两个模型不一样。

另外：`javaToJsImpl` 用于断环的 cache **必须是 `IdentityHashMap`**。
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
`unclosedValuesAreSweptByCloseAndCheckLeaks` 的 64 个 `JSObject`），所以基线恒为 **2**。
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
| 会**返回**东西的调用（`jsCallImpl`、`jsToJava`、`getPropertyValue`、`evaluate`…） | `runOnDispatcher` | 抛 `IllegalStateException` |
| 只**归还 / 撤登记**的收尾动作（`releaseValue`、`release()`） | `onJsThreadQuietly` | 静默跳过 |

- **返回值与转换结果也要留在 dispatcher 上**：`jsToJava` 会遍历对象图、查常驻包装表，
  全都在碰这个 runtime。只把 `jsCallImpl` 收回 dispatcher、把 `jsToJava` 丢在调用方线程上，
  就是历史上那个偶发崩溃的根因（`jsCall` → `jsToJava` 与 `executePendingJob` / GC 并发）。
- `releaseValue` / `release()` 的调用方可以是**任意线程**（`close()` 出现在 `finally` 里）；
  非 JS 线程时它们要 `runBlocking` 一次调度 —— **UI 线程 `close()` 会等一次调度，这是明确
  接受的取舍**，别改成「异步投递、投递完就返回」（runtime 可能已经被销毁）。
- 计数与「已销毁」标记也必须原子（`AtomicInteger` / `AtomicBoolean` + `compareAndSet`）：
  `dup()` 由 native 回调在 JS 线程上调，`free()` 可能来自任意线程；普通 `Int` / `Boolean`
  会丢更新，少一次 `dup` 是提前释放，少一次 `free` 就是 `gc_obj_list` 断言。
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
4. 持久的 `ptr -> wrapper` 登记表；java→js 的 cache 用 `IdentityHashMap`。
5. `Cleaner` 的清理动作只捕获值，加一次性 CAS 守卫。
6. `collectLeaks()` 先记录再归还；`refs` 用强引用身份集。
7. 验证方式：把**上游**的 `assert` 还原（删掉你临时加的 `fprintf` / `abort` 诊断）后
   套件仍然全绿 —— 这才说明 bug 是真修好了，而不是被诊断代码掩盖了。
