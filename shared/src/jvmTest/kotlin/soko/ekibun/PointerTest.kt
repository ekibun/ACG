package soko.ekibun

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Pointer] 的契约。分割线是**句柄怎么到手**，三条路各有用例钉住：
 *
 * 1. **构造参数里交**（`Pointer(nativePtr)`）—— 绝大多数（`AvFrame` / `AvPacket` / `JSRef`…）：
 *    一个成员都不用覆写，句柄当场就在手上；
 * 2. **覆写 [Pointer.initPtr] 就地算** —— 要拿 `this` 去 native 换的那类（`QuickJS`）：
 *    第一个实参留 `null`，覆写体里现算，基类保证「首次读句柄」时才调它；
 * 3. **借用别人的**（`AvStream`）—— **不继承 [Pointer]**：借来的东西不该有 `close()`，
 *    于是 `use {}` / `invokeOnCompletion` 这类自动调用点也关不到它（旧版靠「继承 + 覆写
 *    `close()` 抛异常」，那要跑到运行期才拦得住）。
 *
 * 读句柄有三个入口，一个都**不看** [Pointer.isClosed]（归还动作恰好跑在「已标记、
 * 未销毁」那个窗口里）：
 * - [Pointer.ptr] —— 公开的裸读：不判断也不投递，给「已经确定在归属线程上」的场合；
 * - [Pointer.withPtr] —— 挂起：把「在归属线程上」与「拿到句柄」合成一个动作；
 * - [Pointer.withPtrSync] —— 同步版，给 JNI 回调链这种没有协程上下文的入口。
 *
 * 归还由基类收口（投递、只跑一次、句柄**以参数交进来**），子类只回答 [Pointer.releaseImpl]。
 */
class PointerTest {
  /**
   * 探针：句柄在**构造参数**里交给基类（最常见的一条路），并把归还动作记下来 ——
   * 记的是**交到手上的句柄**，用来钉「归还拿到的是参数，不是自己去读」。
   *
   * [dispatcher] 传了就声明归属 dispatcher；不传就表示不承诺线程归属。
   */
  private class Probe(
    nativePtr: Long,
    dispatcher: ThreadDispatcher? = null,
    private val onRelease: ((Long) -> Unit)? = null,
  ) : Pointer(nativePtr, dispatcher) {
    override suspend fun releaseImpl(ptr: Long) {
      onRelease?.invoke(ptr)
    }
  }

  /**
   * 可数派发次数的归属 dispatcher —— **继承 [ThreadDispatcher]**（而不是自己实现一个
   * `CoroutineDispatcher` 再包一层）正是因为归属线程由它记：[Pointer.withPtrSync] 判断
   * 「要不要投递」靠的就是 [ThreadDispatcher.thread]，包一层就拿不到了。
   */
  private class CountingDispatcher : ThreadDispatcher("counting-owner") {
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

  /**
   * 起一条归属 dispatcher（可数派发）跑 [block]，跑完 [ThreadDispatcher.close] 掉它 ——
   * 每条用例各起一条、各关一条，不攒线程（[CountingDispatcher.dispatches] 是实例级计数，
   * 也不该跨用例共享）。
   */
  private fun withCountingOwner(block: (CountingDispatcher) -> Unit) {
    val counting = CountingDispatcher()
    try {
      block(counting)
    } finally {
      counting.close()
    }
  }

  /**
   * 覆写 [Pointer.initPtr] 提供句柄的子类 —— 「句柄要拿 `this` 去 native 换」那条路的
   * 形态（`QuickJS` 就是它）：基类那边**不传**第一个实参（默认 `null`），覆写体里直接读
   * 构造参数算出句柄 —— `QuickJS` 的 `initContext(this, stackSize, …)` 就是这个形状。
   */
  private class ComputedHandle(
    private val stackSize: Long,
    dispatcher: ThreadDispatcher? = null,
  ) : Pointer(dispatcher = dispatcher) {
    /** 覆写体跑在哪条线程上、跑了几次 —— 钉「求值被搬到归属线程」「只算一次」用。 */
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

  /** 借用型的指针：`AVStream*` 归 `AvFormat` 的上下文所有，本类不拥有它。 */
  private fun borrowedStream() = AvStream(0L, 0, 0, 0, 0, 0, 0, 0L, emptyMap())

  /**
   * [ThreadDispatcher.close] 真的把那条线程收掉 —— 它转发的就是 `ExecutorService.shutdown()`：
   * **不等待**在途任务，空闲 worker 被打断、线程随后退出（`join` 拿不到就是没关掉）。
   */
  @Test
  fun closeShutsDownOwnerThread() {
    val owner = ThreadDispatcher("closing-owner")
    assertTrue(owner.thread.isAlive, "构造期那次投递已经把线程起起来了")
    owner.close()
    owner.thread.join(5_000)
    assertFalse(owner.thread.isAlive, "close() 之后归属线程应当退出")
  }

  /**
   * 关掉之后再投递 —— **当场抛 [IllegalStateException]**，不是 kotlinx 的兜底。
   *
   * [ThreadDispatcher] 在 `dispatch` 上自己拦了一手，理由是 kotlinx 的兜底会把「归属线程
   * 已经没了」这件事**藏起来**：裸 `dispatch` 静默改投 `Dispatchers.IO`（native 调用就跑在
   * IO 线程池上了），`withContext` 那条抛的是 `CancellationException` —— 看着像协程被取消，
   * 其实是线程没了。三种投递形态各钉一遍。
   */
  @Test
  fun closedDispatcherRejectsFreshDispatch() {
    val owner = ThreadDispatcher("rejecting-owner")
    val probe = Probe(0x7AL, owner)
    assertEquals(0x7AL, runBlocking { probe.withPtr { it } }, "关之前照常")

    owner.close()
    assertTrue(owner.isClosed, "close() 要先把标记立起来")

    // 1) 挂起门：走的是 withContext(d)
    val viaSuspend = assertFailsWith<IllegalStateException> { runBlocking { probe.withPtr { it } } }
    assertTrue(viaSuspend.message!!.contains(owner.thread.name), viaSuspend.message)

    // 2) 同步门：走的是 runBlocking(d)，JNI 回调链那条
    assertFailsWith<IllegalStateException> { probe.withPtrSync { it } }

    // 3) 裸 dispatch —— 原本这条最阴：块被静默改投到 Dispatchers.IO 上照跑不误
    assertFailsWith<IllegalStateException> { owner.dispatch(EmptyCoroutineContext, Runnable { }) }
  }

  /**
   * **归还被拒也当场吵**：共享 dispatcher 被（误）关掉之后，本对象再 `close()` —— 归还
   * 投递在 `dispatch` 上被拦，异常同步冒到 [Pointer.close] 的调用方。
   *
   * 硬碰硬是有意留下的：不加拦截这条路**静默得彻底**（`submit {}` 起的 async 直接被取消，
   * 归还块根本不跑），native 资源就这么漏掉，而调用方拿到的是一个「成功」的 `close()`。
   * 现在至少炸在肇事现场附近。
   */
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
    shared.close() // 别家把共享的这条关了
    assertFailsWith<IllegalStateException> { probe.close() }
    assertEquals(0, released, "归还块没机会跑，漏了就得吵出来")
  }

  /**
   * 「自己建的 dispatcher 自己关」：在构造参数里声明独占，归还落地之后那条线程就收掉。
   *
   * 关闭挂在 [Pointer.closeDeferred] 的完成上而不是 [Pointer.close] 里 —— `await()` 回来时
   * 线程已经在退出路上了（[ThreadDispatcher.close] 是 `shutdown()`，不等待在途任务，所以
   * 这里仍 `join` 一下再断言）。**这一条正是 `AvCodec` 的形态**。
   */
  @Test
  fun ownedDispatcherIsClosedAfterReleaseLands() {
    val owner = ThreadDispatcher("owned-owner")
    val probe =
      object : Pointer(0x77L, owner, closeDispatcherOnClose = true) {
        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    assertTrue(owner.thread.isAlive, "还没关，线程应当活着")
    runBlocking { probe.closeDeferred().await() }
    owner.thread.join(5_000)
    assertFalse(owner.thread.isAlive, "声明独占之后，归还落地就该把线程收掉")
  }

  /**
   * 反过来的对照：**没**声明独占，跑器照旧活着。
   *
   * 共享实例（`QuickJS.sharedDispatcher`、`AvFormat` 的伴生对象）全靠这条默认值活下来 ——
   * 只要有一个关掉的对象顺手把它关了，全进程往后的投递都会被 `ThreadDispatcher.dispatch`
   * 拦下抛 `IllegalStateException`（拦不住的话见其 KDoc 里那张表：静默改投 IO / 伪装成
   * `CancellationException`）。
   */
  @Test
  fun sharedDispatcherOutlivesCloseWithoutOwnershipFlag() {
    val shared = ThreadDispatcher("shared-owner")
    try {
      val probe =
        object : Pointer(0x78L, shared) {
          override suspend fun releaseImpl(ptr: Long) = Unit
        }
      runBlocking { probe.closeDeferred().await() }
      assertTrue(shared.thread.isAlive, "没声明独占就不该动那条 dispatcher")
    } finally {
      shared.close()
    }
  }

  /**
   * 句柄**从没建过**也得收线程：`AvCodec` 那条线程是构造期起的（[ThreadDispatcher] 的构造里
   * 就投递过一次），所以「一次没用就 `close()`」同样欠着一条线程。
   *
   * 这条走的是 [Pointer.closeDeferred] 的短路分支：它返回**已完成**的 Deferred，挂在它上面的
   * 关闭动作当场执行 —— 连「发完不管」的 [Pointer.close]（从来不 `await`）也能收干净。
   */
  @Test
  fun ownedDispatcherIsClosedEvenWhenHandleWasNeverBuilt() {
    val owner = ThreadDispatcher("never-built-owner")
    val probe =
      object : Pointer(dispatcher = owner, closeDispatcherOnClose = true) {
        override fun initPtr(): Long = 0x79L

        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    probe.close()
    owner.thread.join(5_000)
    assertFalse(owner.thread.isAlive, "句柄没建过不代表没欠线程")
  }

  /**
   * 「不拥有」写在**类型**上：[AvStream] 不是 [AutoCloseable]，于是根本没有 `close()`
   * 可调 —— 借用别人的指针却能被 `use {}` 自动关掉，是比「关的时候才抛异常」更早的拦截。
   */
  @Test
  fun borrowedPointerDoesNotJoinPointerFamily() {
    assertFalse(
      AutoCloseable::class.java.isAssignableFrom(AvStream::class.java),
      "借用型的 AvStream 不该继承 Pointer（即 AutoCloseable）",
    )
  }

  /**
   * [Pointer.ptr]：**不判断也不投递**的裸读门。
   *
   * 与 [Pointer.withPtr] 分工互补 —— 要挑线程就走挂起门，已经确定在归属线程上（`withPtr`
   * 块里、native 回调链里）就直接读 `ptr`，别为了读一次句柄白投递一次。
   */
  @Test
  fun nakedReadNeverDispatches() =
    withCountingOwner { counting ->
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }

      // 从别的线程读（此刻不在归属 dispatcher 上）：照样就地返回、不投递
      val base = counting.dispatches
      assertEquals(0x1234L, probe.ptr)
      assertEquals(0, counting.dispatches - base, "裸读不该产生任何调度")

      // 已经在归属 dispatcher 上同理
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

  @Test
  fun withPtrDispatchesToOwnerAndHandsOverHandle() =
    withCountingOwner { counting ->
      val probe = runBlocking(counting) { Probe(0x1234L, counting) }

      // 从别的上下文调用：块要被搬到归属 dispatcher 上，句柄当参数交到手里
      val base = counting.dispatches
      val (handle, ranOn) = runBlocking { probe.withPtr { it to Thread.currentThread() } }

      assertEquals(0x1234L, handle)
      assertEquals(counting.thread, ranOn)
      assertEquals(1, counting.dispatches - base, "跨上下文调用应当只投递一次")
    }

  @Test
  fun withPtrDoesNotRedispatchOnOwner() =
    withCountingOwner { counting ->
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
    }

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

  /**
   * 对照基准：`withContext` 对**同一个** dispatcher 不会重新派发。
   *
   * kotlinx 在 `withContext` 里比的是**上下文里那个 interceptor 是不是同一个**（不是
   * `isDispatchNeeded`），相同就走 undispatched 快路径。它支撑「用 [Pointer.withPtr]
   * 顶掉手写的 `withContext(dispatcher)` 是行为保持的」—— 两者在「已在归属 dispatcher 上」
   * 时的派发行为一致。若哪天 kotlinx 改了这条，本用例会先红，好过在 native 调用点上演成
   * 时序问题。
   *
   * [ThreadDispatcher.isDispatchNeeded]（「已在归属线程上就别派发」）管的是**另一条**：
   * `submit {}` / `CoroutineScope(d).async` 这类直接投递不看上下文，只看它。
   */
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

  /**
   * [Pointer.withPtrSync]：同步版 [Pointer.withPtr]，判据是 [ThreadDispatcher.thread]。
   *
   * 两句都要成立：从别的线程调要投递到归属线程上跑；已经在归属线程上就**就地执行**。
   */
  @Test
  fun withPtrSyncDispatchesToOwnerThread() =
    withCountingOwner { counting ->
      val probe = runBlocking(counting) { Probe(0x5151L, counting) }

      val (handle, ranOn) = probe.withPtrSync { it to Thread.currentThread() }
      assertEquals(0x5151L, handle)
      assertEquals(counting.thread, ranOn)

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

      // close() 只投递；等它落地要拿 closeDeferred() 去 await
      runBlocking { probe.closeDeferred().await() }
      assertEquals(counting.thread, releasedOn)
      assertTrue(probe.isClosed)
      // 只跑一次：归还动作不可重入
      runBlocking { probe.closeDeferred().await() }
      assertEquals(1, releaseCount)
    }

  /**
   * 归还动作拿到的句柄是**参数**，而且此刻 [Pointer.isClosed] 已经置位 —— 读句柄的入口
   * 都不能拿它当门，否则归还自己就被挡在门外，「`close()` 返回成功、其实什么都没释放」。
   */
  @Test
  fun releaseImplReceivesHandleAfterCloseIsMarked() {
    var seen = -1L
    var markedWhenRead = false
    val probe =
      object : Pointer(0xBEEFL) {
        override suspend fun releaseImpl(ptr: Long) {
          markedWhenRead = isClosed
          seen = ptr
          // 门也不挡：归还动作跑在「已标记、未销毁」的窗口里
          assertEquals(0xBEEFL, withPtrSync { it })
        }
      }
    assertFalse(probe.isClosed)
    runBlocking { probe.closeDeferred().await() }
    assertTrue(probe.isClosed)
    assertTrue(markedWhenRead, "归还动作必须跑在标记之后")
    assertEquals(0xBEEFL, seen, "归还拿到的应当就是构造参数交的那个句柄")
  }

  /**
   * 「要等线程」那类子类**不再**靠抛异常拒绝归还：基类把归还投递到归属 dispatcher 上，
   * 所以 `close()` 就是正常路径（旧版把同步 `close()` 覆写成直接抛、另立 `closeAsync()`，
   * 那套 `releaseHint` 机制已删）。
   *
   * 这里顺带钉住：句柄从没兑现过时，`close()` 不会为了「还」去现建一个 native 上下文
   * ——[AvCodec] 的 `initPtr()` 就是一次真 native 调用，真建出来反而多一次白建白毁。
   */
  @Test
  fun threadBoundSubclassClosesThroughBaseDispatcher() {
    val codec = AvCodec(borrowedStream())
    codec.close()
    assertTrue(codec.isClosed, "关闭必须先标记，哪怕句柄还没建")
  }

  /**
   * 句柄**没兑现过**的 [Pointer.initPtr] 型子类：不建、不还，但**必须**先标记 ——
   * 否则关闭之后的新操作会白白建出一个没人释放的 native 上下文（`AvPlayback.postFrame`
   * 就是靠 [Pointer.isClosed] 拦住这一手）。
   */
  @Test
  fun closeSkipsReleaseWhenHandleWasNeverBuilt() {
    val probe = ComputedHandle(256L)
    probe.close()
    assertTrue(probe.isClosed, "关闭要先标记")
    assertEquals(0, probe.initPtrCalls, "没建过就别为了归还去建")
    assertEquals(0, probe.releaseCalls)
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
    assertEquals(2560L, runBlocking { ComputedHandle(256L).withPtr { it } })
  }

  /**
   * [Pointer.initPtr] 的**求值线程**：首次读句柄那一下被搬到归属线程上，所以覆写体
   * （`QuickJS` 里就是 `initContext`，含 `JS_NewRuntime` / `JS_SetMaxStackSize` 记下的
   * `stack_top` 基准）一定跑在归属线程上 —— 挂起门与同步门两条路都钉。
   *
   * ⚠️ 这条性质**不是 `by lazy` 给的**：惰性只管「首次读才求值、只算一次」，线程归属是
   * 另外几条凑出来的 —— [Pointer.withPtr] 把 `ptr` 读在 `withContext(d) { … }` 的**块内**、
   * [Pointer.withPtrSync] 读在 `runBlocking(d) { … }` 的**块内**。改动任一条，本用例先红。
   */
  @Test
  fun initPtrRunsOnOwnerThread() =
    withCountingOwner { counting ->
      val owner = counting.thread

      // 挂起门：从**别的**线程（测试线程）首次读，求值必须被搬到归属线程上
      val viaSuspend = runBlocking(counting) { ComputedHandle(256L, counting) }
      assertEquals(2560L, runBlocking { viaSuspend.withPtr { it } })
      assertTrue(owner != Thread.currentThread(), "本用例要有意义，归属线程必须不是调用线程")
      assertEquals(owner, viaSuspend.initPtrThread)
      assertEquals(1, viaSuspend.initPtrCalls)

      // 同步门同理（JNI 回调链走的那条）
      val viaSync = runBlocking(counting) { ComputedHandle(7L, counting) }
      assertEquals(70L, viaSync.withPtrSync { it })
      assertEquals(owner, viaSync.initPtrThread)
    }

  /**
   * [Pointer.initPtr] **只被调一次** —— 基类把结果记在 [Pointer.ptr] 里（`by lazy`）。
   *
   * 对 `QuickJS` 这是硬要求：它的覆写体就是 `initContext`，多调一次就是多建一个 runtime
   * （前一个既没有句柄、也没人销毁）。
   */
  @Test
  fun initPtrIsCalledOnce() {
    var calls = 0
    val probe =
      object : Pointer() {
        override fun initPtr(): Long {
          calls++
          return 0xC0FFEEL
        }

        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    repeat(3) { assertEquals(0xC0FFEEL, probe.withPtrSync { it }) }
    assertEquals(1, calls)
  }

  /**
   * 覆写路径返回 `0` = **建句柄失败**，基类当场 `check` 出来。`0` 不是合法的 native
   * 句柄，放它过去就是一路把这个值当指针用到 native 里。
   */
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

  /**
   * 既没在构造参数里交句柄、也没覆写 [Pointer.initPtr] 的子类，一读句柄就抛错 ——
   * `0` 不是合法 native 句柄，把它静默当成有效指针用下去才是真的危险。
   */
  @Test
  fun missingHandleFailsLoudly() {
    val probe =
      object : Pointer() {
        override suspend fun releaseImpl(ptr: Long) = Unit
      }
    val err = assertFailsWith<IllegalStateException> { runBlocking { probe.withPtr { it } } }
    assertTrue(err.message!!.contains("initPtr"), err.message)
  }

  /**
   * `0` 是**合法的空句柄**（借用型的空壳传的就是 `0L`），所以它不能兼任「没交句柄」的
   * 哨兵：真的交了 `0`，就**不该**再去问 [Pointer.initPtr]，也不该因为 `ptr == 0` 就把
   * 归还省掉 ——「没交」只由 `null` 表示。
   */
  @Test
  fun explicitZeroHandleIsNotTreatedAsMissing() {
    var releaseCalls = 0
    val probe =
      object : Pointer(0L) {
        override fun initPtr(): Long = error("交了句柄就不该再问 initPtr")

        override suspend fun releaseImpl(ptr: Long) {
          releaseCalls++
        }
      }
    assertEquals(0L, probe.ptr)
    assertEquals(0L, probe.withPtrSync { it })
    runBlocking { probe.closeDeferred().await() }
    assertEquals(1, releaseCalls, "拿着 0 也一样欠着一次归还")
  }
}
