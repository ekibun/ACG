package soko.ekibun

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvStream
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Pointer] 的契约：继承它只是**标记**「这里有个 native 指针」，**不保证能关**。
 *
 * 默认 [Pointer.close] 抛异常，是为了让「没有归还实现的类」在被调用时**吵出来**，
 * 而不是静默地把 native 资源留在那儿 —— 本项目没有 GC 兜底，静默就是泄漏。
 * 这两个用例把这条行为钉死，免得日后有人给 [Pointer] 加个空实现把它抹平。
 *
 * 后面几个用例钉的是**读句柄的规则**：`heldPtr` 是 `private`，子类只有三扇门 ——
 * 挂起的 [Pointer.withPtr]（在归属 dispatcher 上带着句柄跑一段代码，native 调用的
 * 标准形态）、挂起的 [Pointer.ptrValue]（只要句柄，内部就是 `withPtr { it }`），
 * 以及已弃用的同步 `ptr`（**不做任何判断**，跨线程读不会报错，正确性归调用方保证），
 * 外加同步版 [Pointer.withPtrSync]（判据是构造线程快照，给非挂起入口用）。
 *
 * 四扇门都**不看** [Pointer.isClosed] —— 归还动作恰好跑在「已标记、未销毁」那个窗口里。
 */
class PointerTest {
  /**
   * 探针：把基类两个受保护的入口暴露出来。
   *
   * [dispatcher] 传了就声明归属 dispatcher —— [Pointer.ptrValue] 会在它上面读句柄；
   * 不传就表示不承诺线程归属。
   */
  private class Probe(
    ptr: Long,
    dispatcher: CoroutineDispatcher? = null,
    private val onRelease: (() -> Unit)? = null,
  ) : Pointer(ptr, dispatcher) {
    @Suppress("DEPRECATION")
    fun readSync(): Long = ptr

    fun requestClose() = closeDeferred()

    override suspend fun releaseImpl() {
      onRelease?.invoke()
    }
  }

  /** 数派发次数的包装 dispatcher：用来验证 [Pointer.ptrValue] 不会做多余的调度。 */
  private class CountingDispatcher(
    private val delegate: CoroutineDispatcher,
  ) : CoroutineDispatcher() {
    var dispatches: Int = 0
      private set

    override fun dispatch(
      context: CoroutineContext,
      block: Runnable,
    ) {
      dispatches++
      delegate.dispatch(context, block)
    }
  }

  /** 借用型的指针：`AVStream*` 归 `AvFormat` 的上下文所有。 */
  private fun borrowedStream() = AvStream(0L, 0, 0, 0, 0, 0, 0, 0L, emptyMap())

  @Test
  fun borrowedPointerRefusesToClose() {
    val err = assertFailsWith<UnsupportedOperationException> { borrowedStream().close() }
    // 提示语要能说清「为什么不能关」，而不只是抛个空壳异常
    assertTrue(err.message!!.contains("借用"), err.message)
  }

  @Test
  fun threadBoundPointerTellsYouWhatToCall() {
    val err = assertFailsWith<UnsupportedOperationException> { AvCodec(borrowedStream()).close() }
    // 要等线程的那类必须指名该调哪个函数，否则调用方无从下手
    assertTrue(err.message!!.contains("closeAsync"), err.message)
  }

  @Test
  fun suspendReadDoesNotRedispatchOnOwner() {
    val exec = Executors.newSingleThreadExecutor()
    val counting = CountingDispatcher(exec.asCoroutineDispatcher())
    try {
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }

      // 已经在归属 dispatcher 上：就地读，不再往队列里投一次
      val (value, extra) =
        runBlocking {
          withContext(counting) {
            val base = counting.dispatches
            probe.ptrValue() to (counting.dispatches - base)
          }
        }
      assertEquals(0x1234L, value)
      assertEquals(0, extra)

      // 反证：从别的上下文读，必须切回归属 dispatcher
      val base = counting.dispatches
      assertEquals(0x1234L, runBlocking { probe.ptrValue() })
      assertTrue(counting.dispatches > base, "跨上下文读应当走一次调度")
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun deprecatedSyncReadDoesNotGuardThread() {
    val exec = Executors.newSingleThreadExecutor()
    val dispatcher = exec.asCoroutineDispatcher()
    try {
      // 在归属 dispatcher 上构造，却从测试线程同步读 —— 撤掉校验之后不再抛
      val probe = runBlocking(dispatcher) { Probe(0x1234L, dispatcher) }
      assertEquals(0x1234L, probe.readSync())
      // 没有 dispatcher 时行为不变：不承诺线程归属
      assertEquals(0x1234L, Probe(0x1234L).readSync())
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun releaseRunsOnOwnerThread() {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
      val owner = runBlocking(dispatcher) { Thread.currentThread() }
      var releasedOn: Thread? = null
      var releaseCount = 0
      val probe =
        runBlocking(dispatcher) {
          Probe(7L, dispatcher) {
            releasedOn = Thread.currentThread()
            releaseCount++
          }
        }

      // close 只投递；等它落地要拿 closeDeferred 去 await
      runBlocking { probe.requestClose().await() }
      assertEquals(owner, releasedOn)
      assertTrue(probe.isClosed)
      // 只跑一次：归还动作不可重入
      runBlocking { probe.requestClose().await() }
      assertEquals(1, releaseCount)
    } finally {
      dispatcher.close()
    }
  }

  @Test
  fun withPtrDispatchesToOwnerAndHandsOverHandle() {
    val exec = Executors.newSingleThreadExecutor()
    val counting = CountingDispatcher(exec.asCoroutineDispatcher())
    try {
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }
      val owner = runBlocking(counting) { Thread.currentThread() }

      // 从别的上下文调用：块要被搬到归属 dispatcher 上，句柄当参数交到手里
      val base = counting.dispatches
      val (handle, ranOn) = runBlocking { probe.withPtr { it to Thread.currentThread() } }

      assertEquals(0x1234L, handle)
      assertEquals(owner, ranOn)
      assertEquals(1, counting.dispatches - base, "跨上下文调用应当只投递一次")
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun withPtrDoesNotRedispatchOnOwner() {
    val exec = Executors.newSingleThreadExecutor()
    val counting = CountingDispatcher(exec.asCoroutineDispatcher())
    try {
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }

      // 已经在归属 dispatcher 上：就地执行，不再往队列里投一次
      val (handle, extra) =
        runBlocking {
          withContext(counting) {
            val base = counting.dispatches
            val value = probe.withPtr { it }
            value to (counting.dispatches - base)
          }
        }
      assertEquals(0x1234L, handle)
      assertEquals(0, extra)
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun withPtrWithoutDispatcherRunsInPlace() {
    val probe = Probe(0x4321L)
    val caller = Thread.currentThread()
    val (handle, ranOn) = runBlocking { probe.withPtr { it to Thread.currentThread() } }
    assertEquals(0x4321L, handle)
    assertEquals(caller, ranOn)
  }

  /**
   * 对照基准：`withContext` 对**同一个** dispatcher 不会重新派发（kotlinx 的快路径）。
   *
   * 它支撑「用 [Pointer.withPtr] 顶掉手写的 `withContext(dispatcher)` 是行为保持的」——
   * 两者在「已在归属 dispatcher 上」时的派发行为一致。若哪天 kotlinx 改了这条，本用例
   * 会先红，好过在 native 调用点上演成时序问题。
   */
  @Test
  fun withContextOnSameDispatcherDoesNotRedispatch() {
    val exec = Executors.newSingleThreadExecutor()
    val counting = CountingDispatcher(exec.asCoroutineDispatcher())
    try {
      val extra =
        runBlocking {
          withContext(counting) {
            val base = counting.dispatches
            withContext(counting) {}
            counting.dispatches - base
          }
        }
      assertEquals(0, extra)
    } finally {
      exec.shutdownNow()
    }
  }

  /**
   * 覆写 [Pointer.initPtr] 提供句柄的子类 —— 「句柄要拿 `this` 去 native 换」那条路
   * 的形态（`QuickJS` 就是它）。句柄在自己的属性初始化器里算，覆写体只读那个字段。
   */
  private class ComputedHandle(
    private val stackSize: Long,
    dispatcher: CoroutineDispatcher? = null,
  ) : Pointer(dispatcher = dispatcher) {
    private val handle: Long = stackSize * 10

    override fun initPtr(): Long = handle
  }

  /**
   * [Pointer.initPtr] 的**调用时机**：读句柄时才调，不是基类构造期调。
   *
   * 这一条钉的是一个静默错值：基类构造期子类字段还没有值（实测 `putfield` 排在
   * `invokespecial <init>` 之后），那时覆写体读到的是 `0`，而且**编译器不报错**。
   * 若哪天有人把那个调用挪回构造期，本用例会先红。
   */
  @Test
  fun initPtrSeesConstructionState() {
    assertEquals(2560L, runBlocking { ComputedHandle(256L).ptrValue() })
  }

  /**
   * 归还是跑在「**已标记、未销毁**」那个窗口里的，所以读句柄那几扇门都不能拿
   * [Pointer.isClosed] 当门 —— 挡下去就是「close() 返回成功、其实什么都没释放」。
   */
  @Test
  fun releaseImplCanReadHandleAfterCloseIsMarked() {
    var seen = -1L
    val probe =
      object : Pointer(0xBEEFL) {
        fun requestClose() = closeDeferred()

        override suspend fun releaseImpl() {
          seen = ptrValue()
        }
      }
    assertEquals(false, probe.isClosed)
    runBlocking { probe.requestClose().await() }
    assertTrue(probe.isClosed)
    assertEquals(0xBEEFL, seen)
  }

  /**
   * [Pointer.withPtrSync]：同步版 [Pointer.withPtr]，判据是**构造线程**快照。
   *
   * 两句都要成立：从别的线程调要投递到归属线程上跑；已经在归属线程上就**就地执行**。
   * 快照只在「带 dispatcher 的子类都在归属线程上构造」这条不变量下才等于归属线程，
   * 而失准的后果只是多投一次（见 [Pointer] 类文档），所以这里就按那条不变量构造。
   */
  @Test
  fun withPtrSyncDispatchesToOwnerThread() {
    val exec = Executors.newSingleThreadExecutor()
    val counting = CountingDispatcher(exec.asCoroutineDispatcher())
    try {
      val probe = runBlocking(counting) { Probe(0x5151L, counting) }
      val owner = runBlocking(counting) { Thread.currentThread() }

      val (handle, ranOn) = probe.withPtrSync { it to Thread.currentThread() }
      assertEquals(0x5151L, handle)
      assertEquals(owner, ranOn)

      val (inner, extra) =
        runBlocking(counting) {
          val base = counting.dispatches
          probe.withPtrSync { it } to (counting.dispatches - base)
        }
      assertEquals(0x5151L, inner)
      assertEquals(0, extra)
    } finally {
      exec.shutdownNow()
    }
  }
}
