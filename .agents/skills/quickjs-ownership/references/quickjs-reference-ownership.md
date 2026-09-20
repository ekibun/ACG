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

## 规则 5 —— 销毁只走显式 `close()`：不设 GC 兜底，但要 `AutoCloseable` + 一次性守卫

`finalize()` 跑在任意 GC 线程上（无线程安全），且自 JDK 18 起已被标记为待移除。
**但别顺手换成 `java.lang.ref.Cleaner`** —— 2026-09-20 已实测并移除，两条理由都成立：

- **Android 上它是 API 33 才有的类。** 本工程 `minSdk = 24` 且没开 core library
  desugaring，低版本上是 `NoClassDefFoundError`；挂在实例字段上就是**构造即炸**
  （`QuickJS` 属于这一种），挂在 companion 字段上就是**类加载即炸**（`AvFrame` 属于这一种）。
- **清理动作不能捕获被登记的对象，而 Kotlin 很容易让你捕到。** 一旦捕获，该对象永远
  可达，清扫器永不触发 —— `QuickJS` 的旧动作就因为写了成员调用，被编译器把 `this`
  带进了捕获表（javap 实证），那层兜底在 JVM 上从未生效过，是**死代码**。

于是：**显式 `close()` 是唯一销毁路径**。真正的兜底不在这里，而在规则 6 的
`refs` 强登记表 + `collectLeaks()`。

```kotlin
class QuickJS private constructor(
  ...,
  private val jsDispatcher: CoroutineDispatcher,
) : Pointer(dispatcher = jsDispatcher) {           // 直接继承基类，见规则 5.1

  companion object {
    // 构造入口是**挂起**工厂：构造必须落在归属线程上（initContext 那一步，见下）
    suspend fun create(...): QuickJS =
      withContext(sharedDispatcher) { QuickJS(..., sharedDispatcher) }

    // 全进程共享一条线程，线程名 `quickjs`；**从不 shutdown**
    val sharedDispatcher: CoroutineDispatcher =
      Executors.newSingleThreadExecutor { r -> Thread(r, "quickjs") }.asCoroutineDispatcher()
  }

  private val runtimeAlive = AtomicBoolean(true)   // 「已销毁」标记，与 isClosed 不是一回事

  // 句柄必须在**属性初始化器**里算好 —— 理由见下面的 ⚠️（构造期子类字段还是 0）
  private val handle: Long = initContext(this@QuickJS, stackSize, memoryLimit, timeout)

  init { check(handle != 0L) { "initContext failed" } }

  override fun initPtr(): Long = handle            // 基类那几扇读门都从这里取值（规则 5.1 第 2 条）

  override suspend fun releaseImpl() {             // 门只在上游：closeAndCollect() 先 markClosed()
    runtimeAlive.set(false)                        // 先落「已销毁」，onJsThreadQuietly 才会停
    destroyContext(ptrValue())
  }

  override fun close() {                           // 投递即返回，不等释放
    CoroutineScope(jsDispatcher).launch { closeAndCollect().await() }
  }
}
```

不那么显然的要求：

- `JS_FreeRuntime` **不可重入** —— 两条线程同时 `close()` 就是双重释放。守卫必须是
  **取走式**原子动作（`getAndSet` / `compareAndSet`），裸 `if (destroyed) return` 挡不住。
  2026-09-20 起这一类守卫统一收进 `Pointer.markClosed()`。
- **`releaseImpl()` 里不能叠一道自己的 `markClosed()` ——「关闭」这道门全局只该有一处。**
  基类的入口（`Pointer.closeDeferred()`、本类的 `closeAndCollect()`）**先抢名额、再调
  `releaseImpl()`**；实现里再抢一次必然失败，结果是「`close()` 返回成功、其实没销毁」，
  runtime 连同它整个堆漏在 native。2026-09-20 收口后的形态：门只在 `closeAndCollect()`
  那次 `Pointer.markClosed()`，`releaseImpl()` 就是裸销毁 `destroyContext(ptrValue())`
  外加把 `runtimeAlive` 落下。此前那套两层叠法（`destroyNow()` 自己再抢一次 + 裸
  `releaseImpl()`）在「基类那条路」上是错的，已整段删掉。
- **runtime 句柄必须建在归属线程上**（`initContext` 那一步，2026-09-20 起由挂起工厂
  `create()` 的 `withContext(sharedDispatcher)` 强制）。`JS_NewRuntime()` 与
  `JS_SetMaxStackSize()` 都把**调用线程的帧地址**记成 `stack_top`，再据此算
  `stack_limit`（`quickjs.c` 的 `JS_NewRuntime2` / `JS_UpdateStackTop` /
  `update_stack_limit`），而 `js_check_stack_overflow` 拿**当前帧地址**比这个界限。
  跨线程建 = 基准来自另一条栈：要么假阳性（碰一下就报栈溢出），要么恒为假（真撞穿宿主
  线程栈，进程直接挂）。native 侧目前 `evaluate` / `jsCall` / `executePendingJob` 三个
  入口都重新 `JS_UpdateStackTop`，基准迟早会被纠正 —— 但那是**没有断言保护的巧合**，
  别据此就把构造挪出 dispatcher。
- **`AutoCloseable` 的形参要收窄。** `QuickJS` 继承 `Pointer`（而 `Pointer` 实现
  `AutoCloseable`）之后，像 `asyncReleasing(vararg values: AutoCloseable?)` 这种
  「用完即还」的形参就会把 runtime 当成可归还的值 —— `invokeOnCompletion` 一触发就是
  整个 runtime 被销毁。收窄成 `JSRef?`。
- `use {}` 调的是 `close()`，而 `QuickJS.close()` 对泄漏只打印不抛；要断言泄漏仍须
  `closeAndCheckLeaks()`（它 `await()` 清算结果）。
- 丢掉 GC 兜底是有代价的：**忘记 `close()` 就真的永久泄漏**，测试与调用方必须自己
  保证关闭路径。这也正是规则 6 存在的理由。

### 规则 5.1 —— `soko.ekibun.Pointer` 基类（2026-09-20 定稿）

定义在 `shared/src/commonMain/kotlin/soko/ekibun/jni.kt`。**所有持有 native 指针的对象
都继承它，或者内部持有它的一个子类**。它继承 `AutoCloseable`，只管三件事：

| 职责 | 成员 |
| --- | --- |
| 保管指针 | 构造参数 `heldPtr`（`private`，`0` = 没有句柄）、**`protected open initPtr()`（句柄来源，默认返回 `heldPtr`）**、`internal suspend withPtr {}`（标准形态）、`internal withPtrSync {}`（非挂起入口）、`suspend ptrValue()`、已弃用的 `protected ptr` |
| 归属 dispatcher | 构造参数 `dispatcher`（`private`）、`internal submit {}`（`dispatcher == null` ⇒ 就地跑完再返回）、`withPtr {}` |
| 只关一次 | `isClosed` / `protected markClosed()` / `protected suspend releaseImpl()` |

承重约定，逐条都有实测代价：

- **指针创建即指定、之后不可更改**（没有 `setPtr`，也别想清零）。按**获得句柄的时机**
  分三条路，都不是惰性：
  1. **构造期就有** —— 当 `heldPtr` 传给 `super`（`AvFrame`、`JSRef` 等）。
  2. **要拿 `this` 去 native 换** —— **继承本类并覆写 `initPtr()`**：构造参数留空，句柄
     在自己的**属性初始化器**里算好、存进一个 `private val`，覆写体只读那个字段
     （`QuickJS.handle` 即此类，2026-09-20 定稿）。塞不进 `super(...)` 实参是因为 `this`
     在 super 调用点还不可引用（Kotlin 报 `cannot access '<this>' before the instance has
     been initialized`；Java 同样禁止，JEP 513 放宽的只是「super 之前可以有语句」）。
     ⚠️ **覆写体不是在构造期被调用的** —— `ptr` 的 getter 到**读句柄时**才调它。理由：基类
     构造期子类字段还没有值，实测 `putfield` 排在 `invokespecial <init>` **之后**，那一刻
     覆写体读到的是 `0`，而且**编译器不报错**（静默错值）。`QuickJS` 的句柄要读
     `stackSize` / `memoryLimit` / `timeout` 三个构造参数，按构造期那个时机算就必然是 `0`
     （`timeout = 0` ⇒ 死循环不再被打断，测试会一直挂着）。相应地，**基类也绝不能在构造期
     读 `ptr`** —— 同一个理由。实测：`PointerTest.initPtrSeesConstructionState`（探针类在
     构造期读 `initPtr()` 只看到 `0`，构造完成后才读到真值）。
  3. **要晚点才有** —— **不继承**，改成**内部持有一个 `Pointer` 子类**
     （`AvFormat.ctx` / `AvCodec.ctx` / `AvPlayback` / `AvPacket.handle`），由那个子类在
     自己的构造期拿到句柄，外层 façade 只转发。
  ⚠️ 曾经有过一版「子类覆写 `initPtr()`、基类**首次读指针时惰性创建**」，已废弃：惰性
  意味着「句柄可能还没建」，于是每个读指针的地方都要问一句「现在建吗」，而建的动作又该
  落在哪条线程上也说不清。现在的 `initPtr()` **不是**那个东西 —— 它是**读时取值**的钩子
  （`ptr` 的 getter 调它），句柄本身早在属性初始化器里就建好了。
- **`heldPtr` / `dispatcher` 都是 `private`，子类读不到**，只剩两扇该用的门 + 一扇弃用的门：
  - `internal suspend fun <T> withPtr(block: suspend (Long) -> T): T` —— **做 native 调用的
    标准形态**（2026-09-20 新增）。把句柄**当参数压进块**、连同块一起投递到归属 dispatcher，
    于是「在归属线程上」与「拿到句柄」合并成一个动作；已在归属 dispatcher 上就**就地执行**
    （零派发），`dispatcher == null` 就地执行且不切线程。首个改用它的调用点是
    `QuickJS.evaluate`（原先是手写 `withContext(jsDispatcher)`）。
    `internal` 而不是 `protected`：还有一类 façade **不继承** `Pointer`、只内部持有它的
    子类（`AvFormat.ctx` 等，见下一条承重约定的第 3 点），外层得拿那个内部子类去调，
    而 `protected` 到不了外层。
    ⚠️ 它能顶掉的只有 `withContext(自己的 dispatcher) { … }` 这**一种**形态。其余投递形态
    语义不同、**不能**换：`withPtrSync`（见下一条）是给**非挂起**入口用的（native 回调链、
    `AutoCloseable.close()` 这类非挂起签名），`CoroutineScope(dispatcher).async/launch`
    是**不等结果**的投递或长驻循环。
    ⚠️ 其中「**发消息、不等结果**」的那一种**不用手抄**：`submit` 也抬成了 `internal`
    （2026-09-20）。`QuickJS` 直接继承 `Pointer`，于是 `closeAndCollect()` 直接
    `submit {}`；不继承的那一类（`AvFormat.ctx` 等）就拿内部子类去调。原先 `QuickJS`
    里那份同名私有 `submit` 已删除。只有 `launch` 那种（长驻循环、fire-and-forget 且
    **不希望异常被 `Deferred` 吞掉**）才继续用 `CoroutineScope(dispatcher).launch`。
    ⚠️ **别用 `MainScope()`**：它底下是 `Dispatchers.Main`，在 JVM 测试这类没有 Android
    主线程的环境里根本没初始化，一调就是 `IllegalStateException`（崩在 `MainDispatchers.kt`）。
    归属线程就是 `jsDispatcher`，要投就往它投。
  - `internal fun <T> withPtrSync(block: (Long) -> T): T` —— `withPtr` 的**同步版**
    （2026-09-20 新增），给**没有协程上下文**的入口用（JNI 直接进来的回调链、
    `AutoCloseable.close()` 这种非挂起签名）。已在归属线程上（跟**构造线程**比）就地执行、
    零派发，否则 `runBlocking(d)` 投过去跑完再返回；`dispatcher == null` 就地执行、不判断
    线程。⚠️ 它会在**调用线程上阻塞**等归属 dispatcher 空出来，所以别在「归属线程正等着你
    返回」的场合调它。
    ⚠️ 判据只能用构造线程快照，是因为 `CoroutineDispatcher` 反查不了自己的线程（见下一条）；
    快照失准的后果**只是多一次投递**、不影响正确性 —— 代价由一条不变量来抵：**带 dispatcher
    的子类都在归属线程上构造**（`QuickJS.create()` 用 `withContext` 强制了这一点，
    `AvFormat` / `AvCodec` 本来就在 `withContext` 里构造）。
    实测：`PointerTest.withPtrSyncDispatchesToOwnerThread`（在别处调会投递、在归属线程上调
    就地执行）。
  - `suspend fun ptrValue()` —— 只要句柄、不打算顺带跑一段代码时用它；任意线程可调，
    实现就是 `withPtr { it }`，判据只有一处。
  - `protected val ptr` —— 同步、**已弃用、且不做任何判断**（`@Deprecated(WARNING)`）。
    还离不开它的只有 JNI 直接进来的**非挂起**回调链 —— `handleJSInvokable` /
    `wrapJSPromiseAsync`，以及 `javaToJsImpl` 一个函数里的几十处读。那些入口没有协程上下文，
    挂起版顶不上。
    ⚠️ **覆写**它要 `@Suppress("OVERRIDE_DEPRECATION")` —— 报的是 "overrides a deprecated
    member but is not marked as deprecated itself"，`@Suppress("DEPRECATION")` **压不住**；
    **使用**它才是 `@Suppress("DEPRECATION")`。`JSRef` 两样都占（它 `get() = super.ptr`）。
    另注：子类覆写出来的 `ptr` 自己**不带** `@Deprecated`（该注解不被继承），于是类内后续
    读它不再报警 —— `JSRef` 里那几处 native 调用点正是靠这一点保持干净的。
    ⚠️ 它是**取值的门**、不是**定值的门**：getter 就是 `initPtr()`。只该用在「已经确定自己
    在归属线程上、只想同步读一下」的场合（`JSRef` 提成 `public` 就是这个用途），其余一律走
    `withPtr` / `ptrValue` / `withPtrSync`。
  - ⚠️ **四扇门（`withPtr` / `ptrValue` / `withPtrSync` / `ptr`）都不看 `isClosed`。**
    关闭是「先标记、后释放」，而归还动作恰好跑在「已标记、未销毁」那个窗口里
    （`releaseImpl` 自己就要读句柄），拿标记当门会把归还一起挡在外边。「标记即拒绝」只适用
    于**新操作**，由子类自己判（`QuickJS.runOnJsThread` / `evaluate`）。
    实测：`PointerTest.releaseImplCanReadHandleAfterCloseIsMarked`（`markClosed()` 之后
    `releaseImpl` 仍读得到句柄）与 `deprecatedSyncReadDoesNotGuardThread`（裸读不做线程校验）。
  - `dispatcher == null` = 「本类不承诺线程归属」：`submit` 就地跑、`withPtr` / `ptrValue`
    就地读 —— 还没注入 dispatcher 的类（`AvFrame` / `AvPacket` / `AvStream` / `JSRef`）即此类。
- **为什么撤掉「归属线程」校验**（2026-09-20 收口，三条都是实测结论，别再往回改）：
  1. `CoroutineDispatcher` **无法反查自己的线程**：`newSingleThreadExecutor()
     .asCoroutineDispatcher()` 返回 `ExecutorCoroutineDispatcherImpl`，它**没有覆写**
     `isDispatchNeeded`，于是继承 `CoroutineDispatcher` 的默认实现 —— 恒为 `true`，
     拿不到「我在不在你的线程上」。要查源码就翻 gradle 缓存里
     `kotlinx-coroutines-core-jvm/<ver>/*-sources.jar` 的 `jvmMain/Executors.kt` 与
     `commonMain/CoroutineDispatcher.kt`（`python -c` 配 `zipfile` 直读即可，不必解包）。
  2. 唯一能反查的手段（构造期 `runBlocking(dispatcher) { currentThread() }`）**必然死锁**：
     带 dispatcher 的子类**全部在归属线程上构造** —— `AvFormat` / `AvCodec` 在
     `withContext` 里、`QuickJS` 在挂起工厂 `create()` 的 `withContext(sharedDispatcher)` 里。
     ⇒ 于是只能退一步，改用**构造线程快照**给 `withPtrSync` 用（见上一条）。
  3. `suspend` 里拿到的是**调用方**的 dispatcher，不是**归属**的 —— 归属是对象自己的
     属性，与谁在调无关；构造器又不能 suspend。所以它只能当「我是否已在归属 dispatcher
     上」的判据用，也就是 `withPtr` / `ptrValue` 那一条。
  ⇒ 跨线程读的正确性改由调用方保证，**读错了不会被拦下** —— 同步门因此撤掉校验。
- ⚠️ **别再写「`withContext(自己的 dispatcher)` 会重派发」**（2026-09-20 实测纠正的旧说法）：
  `isDispatchNeeded` 恒 `true` 只说明它**回答不了**「我在不在你的线程上」，**不**说明
  `withContext` 会往队列里投一次 —— kotlinx 在 `withContext` 里比的是**上下文里的
  interceptor 是否相同**（`newContext[ContinuationInterceptor] == oldContext[…]`，不是
  `isDispatchNeeded`），相同就走 undispatched 快路径。实测见
  `PointerTest.withContextOnSameDispatcherDoesNotRedispatch`：同一个 dispatcher 嵌套调用，
  派发计数为 0。⇒ `withPtr` / `ptrValue` 那条就地分支的价值是**省掉一层调度上下文的构造**、
  并把判据显式摆出来，**不是**为避免死锁或多余派发。
- **默认 `releaseImpl()` 抛 `UnsupportedOperationException`**（`releaseHint` 里写清原因）：
  宁可吵，不要静默 —— 没有归还实现的类被 `close()` 时立刻炸，而不是假装成功地把
  native 资源留在那儿（本项目没有 GC 兜底，静默 = 泄漏）。分三档：
  - **能就地归还**：`override suspend fun releaseImpl()`。基类保证它**跑在归属 dispatcher 上**，
    所以里面直接用 `ptrValue()` 拿句柄即可（此时走的就是就地读那条快路径）；没有
    dispatcher 时 `close()` 会就地跑完（内部一次
    `runBlocking`，块里不挂起 = 等于直接调用）—— `AvFrame`、`AvPacket.Handle`、
    `AvPlayback.Handle`、`AvFormat.Context`、`AvCodec.Context`、`QuickJS`。
    `JSRef` 是另一种：它覆写 `close()` 走自己那套（见规则 5.2）。
  - **要等线程**：释放必须回 dispatcher 线程，于是释放函数只能是 `suspend` 的，而它
    **不能叫 `close`**（Kotlin 不允许 `fun close()` 与 `suspend fun close()` 共存，实测
    `Conflicting overloads` + `Suspend function cannot override non-suspend function`）；
    统一改名 `closeAsync()`，并把同步的 `close()` 覆写成**直接抛** —— `AvFormat` /
    `AvCodec` / `FFPlayer`。`use {}` 对这类会抛异常，必须自己 await。重命名会波及调用方：
    `FFPlayer` 里的 `super.closeAsync()`、`PlayScreen` 里的 `player.closeAsync()`。
  - **只是借用**：指针归别人所有，自己不该也不能释放 —— `AvStream` 的 `AVStream*` 归
    `AvFormat` 的 `AVFormatContext` 所有。保持基类那个抛异常的默认实现。
- ⚠️ 继承 `Pointer` **只是标记，不负责归还**：`use {}`、`invokeOnCompletion` 这类自动
  调用点仍会在末尾调 `close()`，所以「借用型」和「要等线程型」必须保证不会被自动关掉，
  否则抛出的异常会盖掉真正的业务逻辑。
- ⚠️ **`close()` 是「先标记、后释放」且不等结果**：`markClosed()` 先置位，释放动作投进
  dispatcher 队列就返回。不等返回不会 use-after-free —— 同一个单线程 dispatcher 是
  **FIFO** 的，释放消息一定排在「它之前投递的操作」之后、「它之后投递的操作」之前；
  新操作靠 `isClosed` 在入口被拒（**不是**靠「句柄还在不在」）。需要确定性时用
  `closeDeferred()`，`QuickJS` 另给了 `closeAndCollect(): Deferred<List<String>>`。
- ⚠️ **「句柄是不是 0」不能当守卫**：指针不可更改，销毁之后它仍是原值。判「还活着吗」
  看 `isClosed`，或各 façade 自己的一次性标记（`QuickJS.runtimeAlive`）。
- ⚠️ `close()` 的**短路分支必须返回已完成的 `CompletableDeferred`**：写成空的 `async {}`
  没人 complete，调用方 `await()` 会永久挂住。**同一个坑还有一个更隐蔽的形态**：写
  `CompletableDeferred(null)` 会被重载解析挑到 `CompletableDeferred(parent: Job? = null)`
  那一个上去 —— 造出来的同样是**永不完成**的 Deferred，`await()` 永久挂起，而字符上完全
  看不出异常。2026-09-20 实测症状：`QuickJSTest.closeIsIdempotentAcrossEntryPoints` 第二次
  关闭卡死 8 分钟无输出；`jstack` 显示测试线程停在 `runBlocking`、`quickjs` 线程**空转**
  （`LinkedBlockingQueue.take`，队列里没有任何任务）—— 「对端闲着」正是「在等一个永远不会
  完成的 Deferred」的指纹，别误判成 native 卡死。
  ⇒ 一律写 `CompletableDeferred<T?>(value = null)`：带**类型实参 + 命名实参**才会落到
  `value: T` 那个重载上。`Pointer.submit()` 的兜底路径同理。
- ⚠️ 已知毛刺：基类那条默认「抛异常」的 `releaseImpl` 也是先把 `markClosed()` 置位的，
  于是**第二次 `close()` 会静默返回**（第一次已经抛过了）。目前只有 `AvStream` 这类借用
  指针会碰上；要「每次都吵」就得再给基类加一个「本类可否归还」的表态位。

### 规则 5.2 —— `JSRef.close()` 是「还清」，不是 `free()` 的别名

`JSRef` 持有引用计数（构造即一票、`dup()` 加票、`free()` 减票）。`close()` 走
`AutoCloseable`，语义是「Kotlin 侧这个持有者已经不可达了，剩下的票全是垃圾」——
所以它**直接清零计数再 `release()`**，而不是减一票。

若 `close()` 只是 `free()` 的别名，`dup()` 过的值就永远还不掉：`use {}` /
`invokeOnCompletion` 这类自动调用点走的都是 `close()`，而它们只肯减一票，于是每个跑过
`dup` 的对象都会在关闭清算里留一条永久「泄漏」。

- ⚠️ 写 `protected fun finalize()` 会**意外覆写** `java.lang.Object.finalize()`（javap 里
  就是 `protected final void finalize()`，Kotlin 不报错也不要求 `override`）—— 老代码里
  有两处这样的「隐式 finalizer」，都是靠 GC 兜底释放 native 内存的。改这类代码时
  **必须先找到它的显式归还点**，否则一删就变成持续增长的 native 泄漏。

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

- **「归还是否被拒绝」的判据不能复用「关闭是否已发起」。** 2026-09-20 定稿时把这两个
  语义拆成了两个字段：`isClosed`（`markClosed()` 抢占，**新操作**靠它被拒）与
  `runtimeAlive`（**归还**靠它被拒，`JS_FreeRuntime` 之后才置 false）。若两者共用一个
  `closed`，就会出现「先标记 → `collectLeaks()` 那一轮 `releaseValue` 全变空操作 →
  残留撑到 `JS_FreeRuntime` 的 `gc_obj_list` 断言 → `abort()`」。
  换句话说：**先标记后释放是可以的**（`close()` 就该这么做），前提是「归还」另有一个
  判据，而不是去读 `closed`。
- `refs` 必须是**强引用**身份集
  （`Collections.newSetFromMap(IdentityHashMap<JSRef, Boolean>())`）。
  用 `WeakHashMap<Long, JSRef>`（键是 native 指针）会同时踩两个雷：
  指针复用会覆盖条目、条目会被 GC 静默清掉，于是清理时根本找不到泄漏的那一个。

---

## 规则 7 —— native 访问只能在 JS 线程上

QuickJS 是**单线程**的：引用计数是裸 `int`、GC 链表与 Shape 哈希链都无锁。只要有两个线程
同时进同一个 `JSRuntime` 就会丢更新 —— 要么提前释放（`EXCEPTION_ACCESS_VIOLATION`，
崩在 `get_shape_prop` / `list_del` 这类地方），要么漏减（对象/Shape 卡在 `gc_obj_list`，
`JS_FreeRuntime` 的断言 `abort()`）。**症状是偶发崩、重跑就变绿**，因此极易被误判成环境问题。

`QuickJS` 只有一个专用线程（`jsDispatcher`），三类入口分工不同：

| 入口 | 用哪个 | 关闭之后 |
|---|---|---|
| 会**返回**东西的同步调用（`jsCallImpl`、`javaToJsImpl`…） | `runOnJsThread`（= `isClosed` 守卫 + `withPtrSync`） | 抛 `IllegalStateException`（判据 `isClosed`） |
| `evaluate` —— public 且**已 suspend** | `withPtr {}`（内部 `withContext(jsDispatcher)`） | 抛 `IllegalStateException`（判据 `isClosed`） |
| 只**归还 / 撤登记**的收尾动作（`releaseValue`、`releaseRef`） | `onJsThreadQuietly`（= `runtimeAlive` 判据 + `withPtrSync`，**已在 JS 线程上时就地执行**） | **静默跳过**（判据 `runtimeAlive`） |

- **两类入口的判据不一样，别统一。** 新操作用 `isClosed`（标记即拒绝，否则它会排在销毁
  消息**后面** → use-after-free）；归还动作必须用「runtime 还在不在」，否则清算那一轮
  归还全变空操作 → 残留撑到 `JS_FreeRuntime` 的断言 `abort()`。见规则 6 第一条。
- **`evaluate` 已经 suspend 化**（2026-09-20）：构造期**不再有 `runBlocking`** —— 构造
  入口是挂起工厂 `create()`（`withContext(sharedDispatcher)`）。阻塞只剩 `runOnJsThread` /
  `withPtrSync` 那条给 native 回调链用的**同步**出口，以及 `onJsThreadQuietly` 在非 JS
  线程上被调时的那一次。
  native 回调（`@Keep` 的 `loadModule` / `handleJSInvokable` / `wrapJSPromiseAsync`）
  是 native 线程直接调进来的，那里**没有协程上下文**，改 suspend 只能在里面再
  `runBlocking`，反而更糟 —— 所以它们保持同步。
- **返回值与转换结果也要留在 dispatcher 上**：`jsToJava` 要递归遍历整个对象图，全都在碰
  这个 runtime。只把调用收回 dispatcher、把转换丢在调用方线程上，就是历史上那个偶发崩溃的
  根因（调用 → 转换 与 `executePendingJob` / GC 并发）。`JSFunction.invoke` 因此把两步
  包在同一个 `withPtrSync` 块里 —— 调用与转换之间不再有线程切换的缝。
- `releaseValue` / `releaseRef` 的调用方可以是**任意线程**（`close()` 出现在 `finally` 里）；
  非 JS 线程时它们要 `runBlocking` 一次调度 —— **UI 线程 `close()` 会等一次调度，这是明确
  接受的取舍**，别改成「异步投递、投递完就返回」（runtime 可能已经被销毁）。
- ⚠️ **别把 runtime 句柄当值句柄用。** `releaseRef(ref)` 里那句 `releaseValue(ref.ptr)` 是
  唯一的正确写法：`onJsThreadQuietly { handle -> … }` 的块参数是**这个 Pointer 自己的
  runtime 句柄**，而 `releaseValue` 要的是**某个 JS 值**的句柄 —— 两个都是 `Long`，混用
  就是拿 runtime 指针对去 `jsReleaseValue`，当场踩坏 native 堆（实测表现：测试进程
  `0xC0000374` heap corruption 直接死掉，连测试报告都发不出来）。这也是 `JSRef.release()`
  必须走 `ctx.releaseRef(this)` 而不是自己拼 `withPtr { … releaseValue(它) }` 的原因。
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

## 规则 8 —— 改 Kotlin 类结构前，先确认 JNI 符号名会不会变

`Java_<包>_<类>_<方法>` 里的 `<类>` 取决于 `external` 声明**生成在哪个类上**，而 Kotlin 的
生成规则有一条反直觉的地方：

- **`companion object` 里 `@JvmStatic external` 的 native 实现，生成在「外围类」上**，
  不是 `X$Companion`。**决定性的是 `@JvmStatic`，不是 `companion object` 本身** —— JNI 符号
  里的类名取自 native 的**声明类**，而 `@JvmStatic` 的作用正是把 companion 成员提升成外围类的
  static 方法。companion 里**不带** `@JvmStatic` 的 `external` 会声明在 `X$Companion` 上，
  符号相应变成 `Java_..._X_00024Companion_*`（`00024` 就是 `$`）。所以「加 companion 不影响
  符号」这句话必须带 `@JvmStatic` 这个限定词才成立。
  2026-09-20 实测：把 `object QuickJS { class Context }` 改成
  `class QuickJS(...) + companion object` 之后，`QuickJS.class` 里仍是 **25 个 ACC_NATIVE**、
  `QuickJS$Companion.class` 里 **0 个** —— 于是 cpp 侧 `Java_soko_ekibun_quickjs_QuickJS_*`
  那 **25 个符号**一处都不用改（Kotlin 25 ↔ cpp 25，双向差集为空）。
- 判定工具：**`javap`**。本机 `java`/`javap` 都不在 PATH，但**两份 JBR 里 AS 自带的那份带 `javap`**
  （实测 JBR 21.0.10），用绝对路径调：

  ```bash
  "C:\Program Files\Android\Android Studio\jbr\bin\javap.exe" -p -s <class 文件>
  ```

  `-p` 连 private 一起列，`-s` 打出 JVM descriptor（正是 JNI 符号尾部那一段）。
  ⚠️ **别拿 `env.ps1` 里那个 `JAVA_HOME`（`~/.jdks/jbr_dcevm-11.0.16`）去找 `javap` ——
  那份 `bin/` 下没有 `javap.exe`**。两份 JBR 是互补的：11 那份有 `include/jni.h`（编 native 要），
  AS 那份有 `javap`（查符号要）。**别为了 javap 去换 `JAVA_HOME`**，见 `host-env.md`。
  改完结构先跑它复核，不要靠推断。

**但 cpp 里硬编码的类名必须跟着改** —— 这类地方编译器不会提醒，漏改的症状是运行期
`NoSuchMethodError` / `ClassNotFoundException`，不是编译错误：

| 位置 | 形式 | 例子 |
|---|---|---|
| `GetMethodID(clazz, "<init>", ...)` | **嵌套类用 `$` 分隔** | `(JLsoko/ekibun/quickjs/QuickJS$Context;)V` → `(JLsoko/ekibun/quickjs/QuickJS;)V` |
| `FindClass("...")` | 斜杠分隔 | `soko/ekibun/quickjs/JSFunction` |

其余对 Java 方法的查找（`loadModule` / `handleJSInvokable` / `wrapJSPromiseAsync`）都走
`GetObjectClass(opaque->thiz)`，与类名无关。

**判据**：动过类结构（改名、嵌套提顶层、加 companion）之后，
`grep -n 'soko/ekibun/quickjs/' cxx/quickjs/quickjs.cpp` 对一遍，
再跑 `javap` 复核 native 落点（见上）。

**独立复核：Kotlin ↔ cpp 双向对表**。native 声明与实际导出必须一一对应，两个方向都要看：

- 只在 Kotlin 侧 → cpp 缺实现，症状是运行期 `UnsatisfiedLinkError`；
- 只在 cpp 侧 → 导出悬空（改名后的残骸），编译期完全无感。

做法：`javap` 报出的 native 名单，与 cpp 里 `Java_soko_ekibun_quickjs_<类>_<方法>` 求双向差集，
两侧都为空才算过。cpp 侧一行够用：

```bash
grep -o 'Java_soko_ekibun_quickjs_[A-Za-z0-9_]*' cxx/quickjs/quickjs.cpp | sort -u
```

2026-09-20 实测（javap 与手写 class 解析器两法互证，数字一致）：`QuickJS` 25 ↔ 25、
`Highlight` 2 ↔ 2，两向差集皆空；`QuickJS$Companion` 里 **0 个** native。

**JNI 面不止 QuickJS 一个**：`soko/ekibun/quickjs/Highlight.class` 也声明了 2 个 native
（`isIdentFirst` / `isIdentNext`）。cpp 侧 `Java_soko_ekibun_quickjs_*` 共 **27** 个符号
= QuickJS 25 + Highlight 2。查符号、改类结构时别只盯 QuickJS。

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
5. 销毁只走显式 `close()`（实现 `AutoCloseable` 只为语法契约）；一次性守卫是**取走式**
   原子动作（`Pointer.markClosed()` / `AtomicBoolean.getAndSet`）；指针**不可更改**，
   所以别拿「句柄是否为 0」当守卫；**不用 `Cleaner`**（Android API 33 才有 + 动作捕获 `this`）。
6. `collectLeaks()` 先记录再归还；`refs` 用强引用身份集。
7. 验证方式：把**上游**的 `assert` 还原（删掉你临时加的 `fprintf` / `abort` 诊断）后
   套件仍然全绿 —— 这才说明 bug 是真修好了，而不是被诊断代码掩盖了。
