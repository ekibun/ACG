package soko.ekibun.quickjs

import soko.ekibun.Pointer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一个 JS 引用的持有者：**所有权账本 + native 句柄**。
 *
 * 「账本」与「句柄」原先是两个类型（`JSRef` 接口 + 嵌在 `QuickJS` 里的 `JSValue`），
 * 现在并成一个顶层类 —— 因为账本在本工程**只有一个实现者**：
 *
 * - 上游 flutter_qjs 分两层，是因为它有**两个形态不同**的计数子类：`_DartObject`
 *   （包 Dart 对象，**没有** `Pointer<JSValue>`，只有 `_obj`）与 `_JSObject`（包的是
 *   JS 值，有 `_val`/`_ctx`）。纯账本那一层是给前者用的。
 * - 本工程**没有 `_DartObject` 那条线**：Java 对象由 native 的
 *   `JS_SetOpaque(jsObj, NewGlobalRef(obj))` 直接挂在 JS 对象上、用
 *   `JS_GetOpaque(obj, javaClassID)` 取回，Kotlin 侧不留包装。上游之所以要建
 *   `identityHashCode` → `_ref` 的查找表，正是因为它没有 JNI `jobject` 这种稳定句柄。
 *
 * 引用计数模型（移植自 flutter_qjs 的 `_JSObject`）：
 * - 构造即持有 **一票**（native 已经在构造前把引用交出来了）；
 * - [dup] 增票、[free] 减票，减到 0 才真正调 `jsReleaseValue` + `jsDestroyHandle`；
 * - [free] 之后 [ptr] 失效，任何访问都是 use-after-free。
 *
 * 之所以要显式计数：native 把「释放 JS 引用」和「销毁包装」拆成了两个动作，而包装
 * 可能被多个 JS 操作复用（每次配对一次 `jsDupValue`）。只靠 GC 时机归还会让 QuickJS
 * 在 `JS_FreeRuntime` 时断言 `gc_obj_list` 非空并 abort 整个进程，所以泄漏必须能被
 * 发现 —— [describe] 就是给关闭前清算用的。
 *
 * 普通 JS 对象**不走这条路径** —— `jsToJava` 把它们整图展开成 `Map`（对齐
 * flutter_qjs 的 `_jsToDart`），所以本类目前唯一的子类是 [JSFunction]。
 *
 * 句柄由 native 侧交出来（构造点遍布 JNI 回调链），直接作为基类 [Pointer] 的构造参数。
 * 本类**不注入 dispatcher**：没有可切的归属线程，调用点（`jsCall` / `jsDupValue` /
 * `jsReleaseValue`，以及 [JSFunction] 的 `invoke`）也全在 [QuickJS] 的 native 回调链里，
 * 没有协程上下文可挂起。线程归属由 [QuickJS] 保证；要同步读句柄一律走
 * [Pointer.withPtrSync] —— 没有 dispatcher 时它就地在调用线程上返回。
 */
open class JSRef internal constructor(
  nativePtr: Long,
  internal val ctx: QuickJS,
) : Pointer(nativePtr, ctx.dispatcher) {
  // 计数与「已销毁」标记都是原子的：`free()` / `close()` 可能来自任意线程
  // （测试线程、WebView 回调线程），而归还动作被投递到 JS 线程上。
  // 普通 Int / Boolean 会丢更新 —— 少一次计量就是提前释放（use-after-free），
  // 少一次归还就是 `JS_FreeRuntime` 的 gc_obj_list 断言 abort。
  private val _refCount = AtomicInteger(1)

  /** 当前持有者数量。仅用于泄漏诊断。 */
  val refCount: Int get() = _refCount.get()

  init {
    @Suppress("LeakingThis")
    ctx.register(this)
  }

  /**
   * 增加一个持有者。返回自身以便链式调用。
   *
   * 必须在 JS 线程上下文内调用（只改计数，不碰 native）。
   */
  fun dup(): JSRef {
    if (isClosed) throw IllegalStateException("JSRef already released")
    _refCount.incrementAndGet()
    return this
  }

  /**
   * 减少一个持有者。归零时销毁。
   *
   * 重复调用是安全的：[free] 与 [release] 都先看 `destroyedFlag`，归零之后再调直接
   * 返回（**不抛异常**），不会重复释放 native 句柄 —— 这是此前 `delete` 与
   * `jsFreeValue` 混用导致双重释放的地方。
   * 会抛 `IllegalStateException` 的是 [dup]：已经还完了还想要一票，没得给。
   */
  fun free() {
    if (isClosed) return
    if (_refCount.decrementAndGet() <= 0) close()
  }

  override suspend fun releaseImpl(ptr: Long) {
    ctx.releaseRef(this)
  }

  /**
   * 泄漏诊断用的描述。
   *
   * 关闭时若仍有未归零的引用，这里的内容会进入抛出的异常，形如
   * flutter_qjs 的 `"  ADDR\tREF\tTYPE\tPROP"` 行。
   */
  suspend fun describe(): String = withPtr { ptr -> "${javaClass.simpleName}(refs=$refCount, ptr=$ptr)" }
}

/**
 * 对任意值做递归操作：穿透 List / Map / Array，遇到 [JSRef] 就执行 [action]。
 *
 * 与 flutter_qjs 的 `_callRecursive` 行为一致 —— 它同样只认 List、Map 和 JSRef。
 */
private fun Any?.walkRefs(
  seen: MutableSet<Any>,
  action: (JSRef) -> Unit,
) {
  if (this == null) return
  if (!seen.add(this)) return
  when (this) {
    is JSRef -> action(this)
    is Array<*> -> forEach { it.walkRefs(seen, action) }
    is Iterable<*> -> forEach { it.walkRefs(seen, action) }
    is Map<*, *> -> values.forEach { it.walkRefs(seen, action) }
  }
}

/** 递归加票：`listOf(obj).dupRecursive()` 的简写入口。 */
fun Any?.dupRecursive(): Unit = walkRefs(mutableSetOf()) { it.dup() }

/** 递归释放：`listOf(obj).freeRecursive()` 的简写入口。 */
fun Any?.freeRecursive(): Unit = walkRefs(mutableSetOf()) { it.free() }

/*
 * 上游还有一个 `mixin JSRefLeakable`，本工程不设这个标记 —— 但它的语义值得记下来，
 * 否则将来对照上游时会再困惑一次。
 *
 * flutter_qjs 的 `jsFreeRuntime`（ffi.dart）是**两段式**清算：第一段**无条件**销毁
 * 所有 `is JSRefLeakable` 的引用、且不计入泄漏；第二段才把剩下的逐个记成
 * `reference leak` 再销毁。而 `_ref` 表里两类都登记：
 * - `_DartObject` 实现它 —— 那是**内部代理账**，随 JS 对象的生命周期走，不该由调用方
 *   显式归还，报它泄漏就是误报；
 * - `_JSObject` 不实现 —— 那是**调用方欠的票**，漏 `free()` 就是真泄漏。
 *
 * 本工程不建 Kotlin 侧的对象包装（理由见 [JSRef] 的文档），登记表里的全是「欠的票」，
 * 于是 `QuickJS.collectLeaks()` 相当于上游的**第二段**。没有第一段那类要区分，
 * 这个标记也就没有分流作用了。
 */
