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
 * [Pointer] 的句柄取得方式、读取门与归还语义。见 `docs/architecture.md` 的「基座」一节。
 */
class PointerTest {
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

  /** [ThreadDispatcher.close] 真的把归属线程收掉。 */
  @Test
  fun closeShutsDownOwnerThread() {
    val owner = ThreadDispatcher("closing-owner")
    assertTrue(owner.isOwnerAlive(), "the constructor-time dispatch must have started the thread")
    owner.close()
    owner.joinOwner(5_000)
    assertFalse(owner.isOwnerAlive(), "the owner thread should exit after close()")
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
  private fun borrowedStream() = AvStream(0L, 0, 0, 0, 0, 0, 0, 1, 1, 0L, emptyMap())

  /** 关掉之后再投递，三种形态都当场抛 [IllegalStateException]。 */
  @Test
  fun closedDispatcherRejectsFreshDispatch() {
    val owner = ThreadDispatcher("rejecting-owner")
    val probe = Probe(0x7AL, owner)
    assertEquals(0x7AL, runBlocking { probe.withPtr { it } }, "read works before close")

    owner.close()
    assertTrue(owner.isClosed, "close() must mark closed first")

    // 1) 挂起门
    val viaSuspend = assertFailsWith<IllegalStateException> { runBlocking { probe.withPtr { it } } }
    assertTrue(viaSuspend.message!!.contains(owner.threadName), viaSuspend.message)

    // 2) 同步门
    assertFailsWith<IllegalStateException> { probe.withPtrSync { it } }

    // 3) 裸 dispatch
    assertFailsWith<IllegalStateException> { owner.dispatch(EmptyCoroutineContext, Runnable { }) }
  }

  /** 共享 dispatcher 已关时归还被拒：抛 [IllegalStateException]，归还块一次都没跑。 */
  @Test
  fun releaseIsRejectedLoudlyWhenDispatcherAlreadyClosed() {
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

  /** `closeDispatcherOnClose` 声明的独占线程，在归还落地之后被收掉。 */
  @Test
  fun ownedDispatcherIsClosedAfterReleaseLands() {
    val owner = ThreadDispatcher("owned-owner")
    val probe =
      object : Pointer(0x77L, owner, closeDispatcherOnClose = true) {
        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    assertTrue(owner.isOwnerAlive(), "not closed yet, the thread should be alive")
    runBlocking { probe.closeDeferred().join() }
    owner.joinOwner(5_000)
    assertFalse(owner.isOwnerAlive(), "with closeDispatcherOnClose the thread must be collected once release lands")
  }

  /** 对照：没声明独占，归还后共享 dispatcher 的线程照旧存活。 */
  @Test
  fun sharedDispatcherOutlivesCloseWithoutOwnershipFlag() {
    val shared = ThreadDispatcher("shared-owner")
    try {
      val probe =
        object : Pointer(0x78L, shared) {
          override suspend fun releaseImpl(ptr: Long) = Unit
        }
      runBlocking { probe.closeDeferred().join() }
      assertTrue(
        shared.isOwnerAlive(),
        "without closeDispatcherOnClose the shared dispatcher must not be touched",
      )
    } finally {
      shared.close()
    }
  }

  /** 句柄从未建过，独占线程同样被收掉。 */
  @Test
  fun ownedDispatcherIsClosedEvenWhenHandleWasNeverBuilt() {
    val owner = ThreadDispatcher("never-built-owner")
    val probe =
      object : Pointer(dispatcher = owner, closeDispatcherOnClose = true) {
        override fun initPtr(): Long = 0x79L

        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    probe.close()
    owner.joinOwner(5_000)
    assertFalse(owner.isOwnerAlive(), "an uninitialized handle still owes a thread shutdown")
  }

  /** 借用型不在 [AutoCloseable] 家族里：[AvStream] 没有 `close()`。 */
  @Test
  fun borrowedPointerDoesNotJoinPointerFamily() {
    assertFalse(
      AutoCloseable::class.java.isAssignableFrom(AvStream::class.java),
      "a borrowed AvStream must not inherit Pointer (i.e. AutoCloseable)",
    )
  }

  /** [Pointer.ptr] 裸读：跨线程断言失败且零投递；归属线程上就地返回。 */
  @Test
  fun nakedReadNeverDispatches() =
    withCountingOwner { counting ->
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }

      // 从别的线程裸读：断言失败，且零投递
      val base = counting.dispatches
      assertFailsWith<AssertionError> { probe.ptr }
      assertEquals(0, counting.dispatches - base, "the assertion guard itself must not dispatch")

      // 已经在归属 dispatcher 上：就地返回、零投递
      val (value, extra) =
        runBlocking {
          withContext(counting) {
            val b = counting.dispatches
            probe.ptr to (counting.dispatches - b)
          }
        }
      assertEquals(0x1234L, value)
      assertEquals(0, extra)
    }

  /** 已在归属 dispatcher 上：就地执行，不重复投递。 */
  @Test
  fun withPtrDoesNotRedispatchOnOwner() =
    withCountingOwner { counting ->
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }

      // 已经在归属 dispatcher 上：就地执行，不重复投递
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
    }

  /** 没有 dispatcher：两扇门都不判断线程、就地读。 */
  @Test
  fun withPtrWithoutDispatcherRunsInPlace() {
    val probe = Probe(0x4321L)
    val caller = Thread.currentThread()
    val (handle, ranOn) = runBlocking { probe.withPtr { it to Thread.currentThread() } }
    assertEquals(0x4321L, handle)
    assertEquals(caller, ranOn)
    // 同步门同理：没有 dispatcher 就不判断线程、就地读
    assertEquals(0x4321L, probe.withPtrSync { it })
  }

  /** 基准：`withContext` 对同一个 dispatcher 不重复派发。 */
  @Test
  fun withContextOnSameDispatcherDoesNotRedispatch() =
    withCountingOwner { counting ->
      val extra =
        runBlocking {
          withContext(counting) {
            val base = counting.dispatches
            withContext(counting) {}
            counting.dispatches - base
          }
        }
      assertEquals(0, extra)
    }

  /** [Pointer.withPtrSync]：跨线程恰好投递一次并跑在归属线程；已在归属线程则就地执行。 */
  @Test
  fun withPtrSyncDispatchesToOwnerThread() =
    withCountingOwner { counting ->
      val probe = runBlocking(counting) { Probe(0x5151L, counting) }

      val base = counting.dispatches
      val (handle, ranOn) = probe.withPtrSync { it to Thread.currentThread() }
      assertEquals(0x5151L, handle)
      assertEquals(counting.thread, ranOn)
      assertEquals(1, counting.dispatches - base, "a cross-thread call must dispatch exactly once")

      val (inner, extra) =
        runBlocking {
          withContext(counting) {
            val base = counting.dispatches
            probe.withPtrSync { it } to (counting.dispatches - base)
          }
        }
      assertEquals(0x5151L, inner)
      assertEquals(0, extra)
    }

  /** 归还落在归属线程上，且只跑一次。 */
  @Test
  fun releaseRunsOnOwnerThreadAndOnlyOnce() =
    withCountingOwner { counting ->
      var releasedOn: Thread? = null
      var releaseCount = 0
      val probe =
        runBlocking(counting) {
          Probe(7L, counting) { _ ->
            releasedOn = Thread.currentThread()
            releaseCount++
          }
        }

      // close() 只投递，等落地要拿 closeDeferred() 去 await
      runBlocking { probe.closeDeferred().join() }
      assertSame(releasedOn, counting.thread, "release must land on the owner thread")
      assertTrue(probe.isClosed)
      // 只跑一次：归还动作不可重入
      runBlocking { probe.closeDeferred().join() }
      assertEquals(1, releaseCount)
    }

  /** 归还拿到的句柄是构造参数，且此刻 [Pointer.isClosed] 已置位。 */
  @Test
  fun releaseImplReceivesHandleAfterCloseIsMarked() {
    var seen = -1L
    var markedWhenRead = false
    val probe =
      object : Pointer(0xBEEFL) {
        override suspend fun releaseImpl(ptr: Long) {
          markedWhenRead = isClosed
          seen = ptr
          // 归还不经查 isClosed 的两扇门：直读 ptr
          assertEquals(0xBEEFL, ptr)
        }
      }
    assertFalse(probe.isClosed)
    runBlocking { probe.closeDeferred().join() }
    assertTrue(probe.isClosed)
    assertTrue(markedWhenRead, "release must run after the closed mark")
    assertEquals(0xBEEFL, seen, "release must receive the handle given at construction")
  }

  /** 线程绑定的子类归还走基类投递路径；句柄没兑现过则不现建，但仍先标记关闭。 */
  @Test
  fun threadBoundSubclassClosesThroughBaseDispatcher() {
    val codec = AvCodec(borrowedStream())
    codec.close()
    assertTrue(codec.isClosed, "close must mark first, even when the handle was never built")
  }

  /** 句柄没兑现过：不建、不还，但先标记关闭。 */
  @Test
  fun closeSkipsReleaseWhenHandleWasNeverBuilt() {
    val probe = ComputedHandle(256L)
    probe.close()
    assertTrue(probe.isClosed, "close must mark first")
    assertEquals(0, probe.initPtrCalls, "close must not build an uninitialized handle just to release it")
    assertEquals(0, probe.releaseCalls)
  }

  /** [Pointer.initPtr] 在读句柄时才调，不是在基类构造期。 */
  @Test
  fun initPtrSeesConstructionState() {
    assertEquals(2560L, runBlocking { ComputedHandle(256L).withPtr { it } })
  }

  /** 求值线程与次数：首次读句柄被搬到归属线程，且只算一次；两扇门同理。 */
  @Test
  fun initPtrRunsOnOwnerThread() =
    withCountingOwner { counting ->
      val owner = counting.thread

      // 挂起门：从别的线程首次读，求值被搬到归属线程
      val viaSuspend = runBlocking(counting) { ComputedHandle(256L, counting) }
      assertEquals(2560L, runBlocking { viaSuspend.withPtr { it } })
      assertTrue(
        owner !== Thread.currentThread(),
        "this case only makes sense when the owner thread differs from the caller",
      )
      assertTrue(viaSuspend.initPtrThread === owner, "initPtr must run on the owner thread")
      // 再读一次句柄，计数不能涨
      assertEquals(2560L, runBlocking { viaSuspend.withPtr { it } })
      assertEquals(1, viaSuspend.initPtrCalls, "initPtr must be evaluated exactly once")

      // 同步门同理
      val viaSync = runBlocking(counting) { ComputedHandle(7L, counting) }
      assertEquals(70L, viaSync.withPtrSync { it })
      assertEquals(70L, viaSync.withPtrSync { it })
      assertTrue(viaSync.initPtrThread === owner, "sync gate must also land on the owner thread")
      assertEquals(1, viaSync.initPtrCalls, "sync gate must evaluate initPtr exactly once")
    }

  /** 覆写路径返回 `0`：基类当场抛错。 */
  @Test
  fun initPtrReturningZeroFailsLoudly() {
    val probe =
      object : Pointer() {
        override fun initPtr(): Long = 0L

        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    val err = assertFailsWith<IllegalStateException> { runBlocking { probe.withPtr { it } } }
    assertTrue(err.message!!.contains("initPtr"), err.message)
  }

  /** 既没交句柄、也没覆写 [Pointer.initPtr]：一读句柄就抛。 */
  @Test
  fun missingHandleFailsLoudly() {
    val probe =
      object : Pointer() {
        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    val err = assertFailsWith<IllegalStateException> { runBlocking { probe.withPtr { it } } }
    assertTrue(err.message!!.contains("initPtr"), err.message)
  }

  /** `0` 是合法空句柄：不去问 [Pointer.initPtr]，也不省掉归还。 */
  @Test
  fun explicitZeroHandleIsNotTreatedAsMissing() {
    var releaseCalls = 0
    val probe =
      object : Pointer(0L) {
        override fun initPtr(): Long = error("initPtr must not be called when a handle was given")

        override suspend fun releaseImpl(ptr: Long) {
          releaseCalls++
        }
      }
    assertEquals(0L, probe.ptr)
    assertEquals(0L, probe.withPtrSync { it })
    runBlocking { probe.closeDeferred().join() }
    assertEquals(1, releaseCalls, "a 0 handle still owes one release")
  }
}
