package soko.ekibun

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvStream
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `Binding.kt` 里 `Pointer` 那一半：句柄取得、读取门与归还语义（`architecture.md`「基座」）。
 */
class BindingTest {
  /** 探针：句柄在构造参数里交给基类，归还收到的句柄也记下来。 */
  private class Probe(
    nativePtr: Long,
    dispatcher: ThreadDispatcher? = null,
    private val onRelease: ((Long) -> Unit)? = null,
  ) : Pointer(nativePtr, dispatcher) {
    override suspend fun releaseImpl(ptr: Long) {
      onRelease?.invoke(ptr)
    }
  }

  /** 可数派发次数的归属 dispatcher。 */
  private class CountingDispatcher :
    ThreadDispatcherImpl(
      "counting-owner",
      Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    ) {
    var dispatches: Int = 0
      private set

    override fun dispatch(
      context: CoroutineContext,
      block: Runnable,
    ) {
      dispatches++
      super.dispatch(context, block)
    }
  }

  /** 起一条可数派发的归属 dispatcher 跑 [block]，跑完关掉。 */
  private fun withCountingOwner(block: (CountingDispatcher) -> Unit) {
    val counting = CountingDispatcher()
    try {
      block(counting)
    } finally {
      counting.close()
    }
  }

  /** 归属线程。 */
  private fun ThreadDispatcher.ownerThread(): Thread = (this as ThreadDispatcherImpl).thread

  private fun ThreadDispatcher.isOwnerAlive(): Boolean = ownerThread().isAlive

  /** 等归属线程退出，上界 [timeoutMs] 毫秒；返回是否在期限内退出。 */
  private fun ThreadDispatcher.joinOwner(timeoutMs: Long): Boolean =
    run {
      ownerThread().join(timeoutMs)
      !ownerThread().isAlive
    }

  /** 覆写 [Pointer.initPtr] 提供句柄的子类。 */
  private class ComputedHandle(
    private val stackSize: Long,
    dispatcher: ThreadDispatcher? = null,
  ) : Pointer(dispatcher = dispatcher) {
    /** 覆写体跑在哪条线程上、跑了几次。 */
    @Volatile var initPtrThread: Thread? = null
      private set

    @Volatile var initPtrCalls: Int = 0
      private set

    @Volatile var releaseCalls: Int = 0
      private set

    override fun initPtr(): Long {
      initPtrCalls++
      initPtrThread = Thread.currentThread()
      return stackSize * 10
    }

    override suspend fun releaseImpl(ptr: Long) {
      releaseCalls++
    }
  }

  /** 借用型的指针：`AVStream*` 不归本类所有。 */
  private fun borrowedStream() = AvStream(0L, 0, 0, 0, 0, 0, 0, 0f, 0L, emptyMap())

  /** 句柄求值懒、只算一次、落在归属线程；构造时已交句柄则不再解析，但归还照旧欠着。 */
  @Test
  fun lazyHandleResolvesOnceOnTheOwnerThread() =
    withCountingOwner { counting ->
      val owner = counting.thread

      // 首次读句柄才求值，读到的就是覆写体算出来的值
      val lazyGate = runBlocking(counting) { ComputedHandle(256L, counting) }
      assertEquals(2560L, runBlocking { lazyGate.withPtr { it } })
      assertTrue(
        owner !== Thread.currentThread(),
        "this case only makes sense when the owner thread differs from the caller",
      )
      assertTrue(lazyGate.initPtrThread === owner, "initPtr must run on the owner thread")
      // 再读一次句柄，计数不能涨
      assertEquals(2560L, runBlocking { lazyGate.withPtr { it } })
      assertEquals(1, lazyGate.initPtrCalls, "initPtr must be evaluated exactly once")

      // 同步门同理
      val syncGate = runBlocking(counting) { ComputedHandle(7L, counting) }
      assertEquals(70L, syncGate.withPtrSync { it })
      assertEquals(70L, syncGate.withPtrSync { it })
      assertTrue(syncGate.initPtrThread === owner, "sync gate must also land on the owner thread")
      assertEquals(1, syncGate.initPtrCalls, "sync gate must evaluate initPtr exactly once")
    }

  @Test
  fun explicitZeroHandleSkipsInitButStillOwesOneRelease() {
    var releaseCalls = 0
    val probe =
      object : Pointer(0L) {
        override fun initPtr(): Long = error("initPtr must not be called when a handle was given")

        override suspend fun releaseImpl(ptr: Long) {
          releaseCalls++
        }
      }
    assertEquals(0L, probe.ptr, "0 is a legal empty handle, not a missing one")
    assertEquals(0L, probe.withPtrSync { it })
    runBlocking { probe.closeDeferred().join() }
    assertEquals(1, releaseCalls, "a 0 handle still owes one release")
  }

  /** 句柄拿不到时必须当场喊：覆写体返回 `0`、或既没交句柄也没覆写。 */
  @Test
  fun unresolvableHandleFailsLoudly() {
    val returnedZero =
      object : Pointer() {
        override fun initPtr(): Long = 0L

        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    val firstError = assertFailsWith<IllegalStateException> { runBlocking { returnedZero.withPtr { it } } }
    assertTrue(firstError.message!!.contains("initPtr"), firstError.message)

    val neverGiven =
      object : Pointer() {
        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    val secondError = assertFailsWith<IllegalStateException> { runBlocking { neverGiven.withPtr { it } } }
    assertTrue(secondError.message!!.contains("initPtr"), secondError.message)
  }

  /** 两扇门只在跨线程时投递一次；已在归属线程就地执行，裸读 `ptr` 永不投递。 */
  @Test
  fun gatesDispatchOnceToTheOwnerThread() =
    withCountingOwner { counting ->
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }

      // 裸读：跨线程断言失败且零投递
      val base = counting.dispatches
      assertFailsWith<AssertionError> { probe.ptr }
      assertEquals(0, counting.dispatches - base, "the assertion guard itself must not dispatch")

      // 裸读：已在归属 dispatcher 上就地返回、零投递
      val (naked, nakedExtra) =
        runBlocking {
          withContext(counting) {
            val b = counting.dispatches
            probe.ptr to (counting.dispatches - b)
          }
        }
      assertEquals(0x1234L, naked)
      assertEquals(0, nakedExtra)

      // 挂起门：已在归属 dispatcher 上不重复投递
      val (handle, extra) =
        runBlocking {
          withContext(counting) {
            val b = counting.dispatches
            probe.withPtr { it } to (counting.dispatches - b)
          }
        }
      assertEquals(0x1234L, handle)
      assertEquals(0, extra)

      // 同步门：跨线程恰好投递一次并跑在归属线程；已在归属线程则就地执行
      val syncBase = counting.dispatches
      val (syncHandle, ranOn) = probe.withPtrSync { it to Thread.currentThread() }
      assertEquals(0x1234L, syncHandle)
      assertEquals(counting.thread, ranOn)
      assertEquals(1, counting.dispatches - syncBase, "a cross-thread call must dispatch exactly once")

      val (inner, syncExtra) =
        runBlocking {
          withContext(counting) {
            val b = counting.dispatches
            probe.withPtrSync { it } to (counting.dispatches - b)
          }
        }
      assertEquals(0x1234L, inner)
      assertEquals(0, syncExtra)
    }

  /** 没有 dispatcher：两扇门都不判断线程、就地读。 */
  @Test
  fun withoutDispatcherBothGatesRunInPlace() {
    val probe = Probe(0x4321L)
    val caller = Thread.currentThread()
    val (handle, ranOn) = runBlocking { probe.withPtr { it to Thread.currentThread() } }
    assertEquals(0x4321L, handle)
    assertEquals(caller, ranOn)
    assertEquals(0x4321L, probe.withPtrSync { it })
  }

  /** `close()` 收掉归属线程，此后三种投递形态都当场抛 [IllegalStateException]。 */
  @Test
  fun closingShutsTheOwnerAndRejectsFurtherDispatch() {
    val owner = ThreadDispatcher("closing-owner")
    val probe = Probe(0x7AL, owner)
    assertEquals(0x7AL, runBlocking { probe.withPtr { it } }, "read works before close")
    assertTrue(owner.isOwnerAlive(), "the constructor-time dispatch must have started the thread")

    owner.close()
    assertTrue(owner.isClosed, "close() must mark closed first")
    owner.joinOwner(5_000)
    assertFalse(owner.isOwnerAlive(), "the owner thread should exit after close()")

    // 1) 挂起门
    val viaSuspend = assertFailsWith<IllegalStateException> { runBlocking { probe.withPtr { it } } }
    assertTrue(viaSuspend.message!!.contains(owner.threadName), viaSuspend.message)

    // 2) 同步门
    assertFailsWith<IllegalStateException> { probe.withPtrSync { it } }

    // 3) 裸 dispatch
    assertFailsWith<IllegalStateException> { owner.dispatch(EmptyCoroutineContext, Runnable { }) }
  }

  /** 归还落在归属线程、只跑一次，且跑的时候 `isClosed` 已经置位、拿到的是构造时那个句柄。 */
  @Test
  fun releaseLandsOnOwnerOnceAfterTheClosedMark() =
    withCountingOwner { counting ->
      var releasedOn: Thread? = null
      var releaseCount = 0
      var markedWhenRead = false
      var seen = -1L
      val probe =
        runBlocking(counting) {
          object : Pointer(0xBEEFL, counting) {
            override suspend fun releaseImpl(ptr: Long) {
              releasedOn = Thread.currentThread()
              releaseCount++
              markedWhenRead = isClosed
              seen = ptr
            }
          }
        }

      assertFalse(probe.isClosed)
      // close() 只投递，等落地要拿 closeDeferred() 去 await
      runBlocking { probe.closeDeferred().join() }
      assertSame(releasedOn, counting.thread, "release must land on the owner thread")
      assertTrue(probe.isClosed)
      assertTrue(markedWhenRead, "release must run after the closed mark")
      assertEquals(0xBEEFL, seen, "release must receive the handle given at construction")

      // 只跑一次：归还动作不可重入
      runBlocking { probe.closeDeferred().join() }
      assertEquals(1, releaseCount)
    }

  /** `closeDispatcherOnClose` 声明的独占线程在归还落地后收掉；共享的那条不许被碰。 */
  @Test
  fun dispatcherOwnershipDecidesTheThreadShutdown() {
    val owned = ThreadDispatcher("owned-owner")
    val ownedProbe =
      object : Pointer(0x77L, owned, closeDispatcherOnClose = true) {
        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    assertTrue(owned.isOwnerAlive(), "not closed yet, the thread should be alive")
    runBlocking { ownedProbe.closeDeferred().join() }
    owned.joinOwner(5_000)
    assertFalse(owned.isOwnerAlive(), "with closeDispatcherOnClose the thread must be collected once release lands")

    val shared = ThreadDispatcher("shared-owner")
    try {
      val sharedProbe =
        object : Pointer(0x78L, shared) {
          override suspend fun releaseImpl(ptr: Long) = Unit
        }
      runBlocking { sharedProbe.closeDeferred().join() }
      assertTrue(
        shared.isOwnerAlive(),
        "without closeDispatcherOnClose the shared dispatcher must not be touched",
      )
    } finally {
      shared.close()
    }

    // 句柄从未建过，独占线程同样被收掉
    val neverBuilt = ThreadDispatcher("never-built-owner")
    val lazyProbe =
      object : Pointer(dispatcher = neverBuilt, closeDispatcherOnClose = true) {
        override fun initPtr(): Long = 0x79L

        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    lazyProbe.close()
    neverBuilt.joinOwner(5_000)
    assertFalse(neverBuilt.isOwnerAlive(), "an uninitialized handle still owes a thread shutdown")
  }

  /** 共享 dispatcher 被别处关掉时，归还必须喊出来：抛 [IllegalStateException]，归还块一次都没跑。 */
  @Test
  fun releaseIsRejectedLoudlyWhenTheOwnerIsAlreadyClosed() {
    val shared = ThreadDispatcher("already-closed-owner")
    var released = 0
    val probe =
      object : Pointer(0x7BL, shared) {
        override suspend fun releaseImpl(ptr: Long) {
          released++
        }
      }
    shared.close() // 共享的 dispatcher 被别处关掉
    assertFailsWith<IllegalStateException> { probe.close() }
    assertEquals(0, released, "release block never ran; a missed release must be loud")
  }

  /** 句柄没兑现过：先标记关闭，但不为归还而现建（也不调归还）。 */
  @Test
  fun closeWithoutAHandleMarksClosedWithoutBuildingIt() {
    val probe = ComputedHandle(256L)
    probe.close()
    assertTrue(probe.isClosed, "close must mark first")
    assertEquals(0, probe.initPtrCalls, "close must not build an uninitialized handle just to release it")
    assertEquals(0, probe.releaseCalls)

    // 线程绑定的子类同理：归还走基类投递路径，句柄没兑现过也不现建
    val codec = AvCodec(borrowedStream())
    codec.close()
    assertTrue(codec.isClosed, "close must mark first, even when the handle was never built")
  }

  /** 借用型不在 [AutoCloseable] 家族里：[AvStream] 没有 `close()`。 */
  @Test
  fun borrowedPointerDoesNotJoinPointerFamily() {
    assertFalse(
      AutoCloseable::class.java.isAssignableFrom(AvStream::class.java),
      "a borrowed AvStream must not inherit Pointer (i.e. AutoCloseable)",
    )
  }
}
