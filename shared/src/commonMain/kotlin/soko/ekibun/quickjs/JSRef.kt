package soko.ekibun.quickjs

/**
 * 每个 JS 引用的所有权账本。
 *
 * 这是从 flutter_qjs 移植过来的核心机制（见其 `lib/src/ffi.dart` 的 `JSRef`）：
 * native 侧的 `jsReleaseValue` / `jsDestroyHandle` 是两件独立的事，本类负责决定
 * 「什么时候该调哪一个」。
 *
 * 规则：
 * - 一个包装对象初始持有 **一票**（构造时 native 已经把引用交出来了）。
 * - 每多一个持有者就 [dup]，每少一个就 [free]。
 * - 归零时才真正释放 JS 引用（[destroy]）。
 *
 * 只 `dup` 不 `free` 会让 QuickJS 在 `JS_FreeRuntime` 时断言失败并 abort 整个进程，
 * 所以泄漏必须能被发现 —— [leaked] 就是给关闭前清算用的。
 */
interface JSRef {
  /**
   * 增加一个持有者。返回自身以便链式调用。
   *
   * 必须在 JS 线程上下文内调用（只改计数，不碰 native）。
   */
  fun dup(): JSRef

  /**
   * 减少一个持有者。归零时释放 JS 引用。
   *
   * 只改计数；真正的 native 释放由持有者自己安排的时机执行（见 `Context.JSValue`）。
   */
  fun free()

  /** 当前持有者数量。仅用于泄漏诊断。 */
  val refCount: Int

  /** 是否已经归零并释放。 */
  val released: Boolean

  /**
   * 泄漏诊断用的描述。
   *
   * 关闭时若仍有未归零的引用，这里的内容会进入抛出的异常，形如
   * flutter_qjs 的 `"  ADDR\tREF\tTYPE\tPROP"` 行。
   */
  fun describe(): String
}

/**
 * 递归地给一组容器/引用加票。
 *
 * 递归对象（`a['a'] = a`）会重复访问同一个实例，用 [seen] 去重，否则环形结构会
 * 无限递归 —— flutter_qjs 的 `_callRecursive` 出于同样原因也带一个 `cache` Set。
 */
fun List<JSRef?>.dupRecursive(seen: MutableSet<Any> = mutableSetOf()): Unit = forEach { it?.dupOrSkip(seen) }

/** [dupRecursive] 的释放对应体。 */
fun List<JSRef?>.freeRecursive(seen: MutableSet<Any> = mutableSetOf()): Unit = forEach { it?.freeOrSkip(seen) }

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

internal fun JSRef.dupOrSkip(seen: MutableSet<Any>) {
  if (!seen.add(this)) return
  dup()
}

internal fun JSRef.freeOrSkip(seen: MutableSet<Any>) {
  if (!seen.add(this)) return
  free()
}

/** 递归加票：`listOf(obj).dupRecursive()` 的简写入口。 */
fun Any?.dupRecursive(): Unit = walkRefs(mutableSetOf()) { it.dup() }

/** 递归释放：`listOf(obj).freeRecursive()` 的简写入口。 */
fun Any?.freeRecursive(): Unit = walkRefs(mutableSetOf()) { it.free() }

/**
 * 标记「需要被关闭前清算兜底」的引用。
 *
 * flutter_qjs 用 `JSRefLeakable` 区分两类引用：一类在 runtime 关闭前必须被主动
 * 销毁（它对应 `_DartObject`），另一类可以交给 Dart GC。移植后语义简化为：
 * 凡是包了 native 句柄、且最后要归还给 QuickJS 的，都实现本接口。
 */
interface JSRefLeakable
