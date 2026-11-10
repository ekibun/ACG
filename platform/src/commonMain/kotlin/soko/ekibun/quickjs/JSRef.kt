package soko.ekibun.quickjs

import soko.ekibun.Pointer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 一个 JS 引用的持有者：所有权账本 + native 句柄。
 *
 * 计数规则见 `docs/development/quickjs-lifetime.md` 的「票」。
 */
@OptIn(ExperimentalAtomicApi::class)
open class JSRef internal constructor(
  nativePtr: Long,
  internal val ctx: QuickJS,
) : Pointer(nativePtr, ctx.dispatcher) {
  // 计数用原子操作。
  private val _refCount = AtomicInt(1)

  /** 当前持有者数量。 */
  val refCount: Int get() = _refCount.load()

  init {
    @Suppress("LeakingThis")
    ctx.register(this)
  }

  /** 增加一个持有者，不碰 native。 */
  fun dup(): JSRef {
    if (isClosed) throw IllegalStateException("JSRef already released")
    _refCount.addAndFetch(1)
    return this
  }

  /** 减少一个持有者，归零时销毁；重复调用安全。 */
  fun free() {
    if (isClosed) return
    if (_refCount.addAndFetch(-1) <= 0) close()
  }

  override suspend fun releaseImpl(ptr: Long) {
    ctx.releaseRef(this)
  }

  /** 泄漏诊断描述。 */
  fun describe(): String = "${this::class.simpleName}(refs=$refCount, ptr=$ptr)"
}

/** 对任意值递归操作：穿透 Array / Iterable / Map，遇到 [JSRef] 就执行 [action]。 */
private fun Any?.walkRefs(
  seen: MutableList<Any>,
  action: (JSRef) -> Unit,
) {
  if (this == null) return
  if (this !is JSRef && this !is Array<*> && this !is Iterable<*> && this !is Map<*, *>) return
  // 按对象身份去重：普通 Set 的 hashCode 会在自引用对象上无限递归
  if (seen.any { it === this }) return
  seen.add(this)
  when (this) {
    is JSRef -> action(this)
    is Array<*> -> forEach { it.walkRefs(seen, action) }
    is Iterable<*> -> forEach { it.walkRefs(seen, action) }
    is Map<*, *> -> values.forEach { it.walkRefs(seen, action) }
  }
}

/** 递归加票：`listOf(obj).dupRecursive()` 的简写入口。 */
fun Any?.dupRecursive(): Unit = walkRefs(mutableListOf()) { it.dup() }

/** 递归释放：`listOf(obj).freeRecursive()` 的简写入口。 */
fun Any?.freeRecursive(): Unit = walkRefs(mutableListOf()) { it.free() }
