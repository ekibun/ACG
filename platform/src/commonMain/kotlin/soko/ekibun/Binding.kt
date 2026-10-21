package soko.ekibun

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

expect fun loadLibrary(name: String)

/** 起一条归属线程调度器 —— [ThreadDispatcher] 唯一的 `expect`。 */
expect fun createThreadDispatcher(threadName: String): ThreadDispatcher

/**
 * 归属线程的调度器，本身是 [CoroutineDispatcher]。
 *
 * 设计与平台差异见 `docs/architecture.md` 的「基座：`Pointer` 与 `ThreadDispatcher`」。
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
abstract class ThreadDispatcher(
  /** 归属线程的名字。 */
  val threadName: String = "thread-dispatcher",
  private val delegate: CloseableCoroutineDispatcher,
) : CoroutineDispatcher(),
  AutoCloseable {
  companion object {
    operator fun invoke(threadName: String): ThreadDispatcher = createThreadDispatcher(threadName)
  }

  private val closedFlag = AtomicBoolean(false)

  /** 是否已[关闭][close]。 */
  val isClosed: Boolean get() = closedFlag.load()

  /** 把任务投递到归属线程；已关闭时抛 [IllegalStateException]。 */
  override fun dispatch(
    context: CoroutineContext,
    block: Runnable,
  ) {
    check(!isClosed) {
      "ThreadDispatcher($threadName) is closed"
    }
    delegate.dispatch(context, block)
  }

  /** 当前线程是否就是归属线程。 */
  abstract fun isOnCurrentThread(): Boolean

  /** 已在归属线程上时就地执行，不派发。 */
  override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isOnCurrentThread()

  /** 关闭归属线程：幂等，不等在途任务。只由独占者调用。 */
  override fun close() {
    closedFlag.store(true)
    delegate.close()
  }

  override fun toString(): String = "ThreadDispatcher($threadName)"
}

/**
 * 持有 native 句柄的对象的基类。
 *
 * 生命周期契约见 `docs/architecture.md` 的「基座：`Pointer` 与 `ThreadDispatcher`」。
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class Pointer protected constructor(
  /** native 句柄；`null` 表示由 [initPtr] 现算。 */
  private val nativePtr: Long? = null,
  private val dispatcher: ThreadDispatcher? = null,
  /** 是否在归还落地后关闭 [dispatcher]（只在独占这条线程时为 `true`）。 */
  private val closeDispatcherOnClose: Boolean = false,
) : AutoCloseable {
  /** 句柄：构造参数优先，否则调 [initPtr]。 */
  private val ptrLazy =
    lazy {
      nativePtr ?: run {
        val who = simpleClassName
        assert(dispatcher == null || dispatcher.isOnCurrentThread()) {
          "$who.initPtr() must run on the dispatcher thread"
        }
        initPtr().also { check(it != 0L) { "$who.initPtr() returned 0" } }
      }
    }

  /** 诊断消息用的简单类名，拿不到时退回 `Pointer`。 */
  internal open val simpleClassName: String get() = this::class.simpleName ?: "Pointer"

  /** 直接读句柄；带同线程断言（JVM `-ea`）。 */
  internal val ptr: Long
    get() {
      val d = dispatcher
      val who = simpleClassName
      assert(d == null || d.isOnCurrentThread()) { "direct call $who.ptr off the dispatcher thread" }
      return ptrLazy.value
    }

  /** 现算句柄；构造参数留 `null` 的子类覆写。 */
  internal open fun initPtr(): Long =
    throw IllegalStateException("$simpleClassName has no handle and does not override Pointer.initPtr()")

  /** 在归属线程上带句柄执行 [block]；已在归属线程则就地执行。 */
  internal suspend fun <T> withPtr(block: suspend (Long) -> T): T {
    if (isClosed) throw IllegalStateException("$simpleClassName is closed")
    val d = dispatcher ?: return block(ptr)
    if (d.isOnCurrentThread()) return block(ptr)
    return withContext(d) { block(ptr) }
  }

  /** [withPtr] 的同步版，会阻塞调用线程。 */
  internal fun <T> withPtrSync(block: (Long) -> T): T {
    if (isClosed) throw IllegalStateException("$simpleClassName is closed")
    val d = dispatcher ?: return block(ptr)
    if (d.isOnCurrentThread()) return block(ptr)
    return runBlocking(d) { block(ptr) }
  }

  /** 把动作投递到归属 dispatcher，返回其 [Job]。 */
  internal fun submit(block: suspend () -> Unit): Job =
    dispatcher?.let { CoroutineScope(it).launch { block() } }
      ?: CompletableDeferred(value = runBlocking { block() })

  private val closedFlag = AtomicBoolean(false)

  /** 是否已[关闭][close]。 */
  val isClosed: Boolean get() = closedFlag.load()

  /** 抢占「关闭」名额；抢到返回 `true`，已关过返回 `false`。 */
  protected fun markClosed(): Boolean = closedFlag.compareAndSet(false, true)

  /** [close] 的可等待版：返回的 [Job] 完成时 [releaseImpl] 已跑完。 */
  fun closeDeferred(): Job {
    val done =
      if (!markClosed() || (nativePtr == null && !ptrLazy.isInitialized())) {
        CompletableDeferred(value = Unit)
      } else {
        submit { releaseImpl(ptr) }
      }
    // 归还落地之后再关线程。
    if (closeDispatcherOnClose) {
      done.invokeOnCompletion { dispatcher?.close() }
    }
    return done
  }

  /** 投递一次释放并立即返回；异常只打印（要接住用 [closeDeferred]）。 */
  override fun close() {
    closeDeferred().invokeOnCompletion { e -> e?.printStackTrace() }
  }

  /** 归还 native 资源；句柄以参数交进来，在归属 dispatcher 上跑，只跑一次。 */
  abstract suspend fun releaseImpl(ptr: Long)
}
