package soko.ekibun

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor

expect fun jniLoadLibrary(name: String)

/**
 * 持有 native 指针的对象的统一基类 —— 继承它就是**标记**「我持有一个 native 资源」。
 *
 * 这个标记不是装饰：本项目**不给 native 资源设 GC 兜底**，所以每一个持有 native
 * 指针的类都必须能被机械地找出来、并且有明确的归还契约。不设兜底的两条理由：
 *
 * - `java.lang.ref.Cleaner` 在 Android 上是 **API 33** 才有的类，而本工程
 *   `minSdk = 24` 且没开 core library desugaring —— 用到就是 `NoClassDefFoundError`；
 * - `Object.finalize()` 跑在 JVM 的 finalizer 线程上、时机不可控，自 JDK 18 起已被
 *   标记为待移除（本工程曾有两处 `protected fun finalize()` 意外覆写了它，已移除）。
 *
 * 于是**忘记归还就真的永久泄漏**，归还必须是显式动作 —— 这正是本基类存在的意义。
 *
 * ## 基类管三件事
 *
 * | 职责 | 成员 | 说明 |
 * | --- | --- | --- |
 * | 保管指针 | 构造参数 `heldPtr`（`private`）/ [withPtr] / [ptrValue] | **创建即指定**，之后不可更改；`0` = 没有句柄 |
 * | 归属 dispatcher | `dispatcher`（`private`）/ [submit] / [withPtr] | 有就把动作**投递**过去，没有就就地执行 |
 * | 只关一次 | [isClosed] / [markClosed] | 抢占式标记，**先标记**再释放 |
 *
 * ## 指针从哪来：三条路，都是「构造完成那一刻就定」
 *
 * 曾经有过「子类覆写 `initPtr()`、基类**首次读指针时**惰性创建」的版本，已废弃：惰性
 * 意味着「句柄可能还没建」，于是每个读指针的地方都要问一句「现在建吗」，而建的动作又该
 * 落在哪条线程上也说不清。现在按**获得句柄的时机**分三条路：
 *
 * 1. **构造期就有句柄**（[soko.ekibun.ffmpeg.AvFrame]、[soko.ekibun.quickjs.JSRef] 等）
 *    —— 把句柄当 `heldPtr` 传给 `super`，最省事的一条；
 * 2. **句柄要拿 `this` 去 native 换**（[soko.ekibun.quickjs.QuickJS]）—— **继承本类并
 *    覆写 [initPtr]**：构造参数留空（默认 `0`），句柄在自己的**属性初始化器**里算好、
 *    存进一个 `private val`，覆写体只读那个字段。不能直接把 `initContext(this, …)` 塞进
 *    `super(...)` 的实参，因为 `this` 在 super 调用点还不可引用（Kotlin 报
 *    `cannot access '<this>' before the instance has been initialized`，Java 同样禁止；
 *    JEP 513 放宽的只是「super 之前可以有语句」）。
 *    ⚠️ **覆写体不是在构造期被调用的**（[ptr] 的 getter 到读句柄时才调它）。理由：基类
 *    构造期子类字段还没有值 —— 实测 `putfield` 排在 `invokespecial <init>` **之后**，
 *    那一刻覆写体读到的是 `0`，而且**编译器不报错**，是静默错值。`QuickJS` 的句柄要读
 *    `stackSize` / `memoryLimit` / `timeout` 三个构造参数，按那个时机算就必然是 0
 *    （`timeout = 0` ⇒ 死循环不再被打断，测试会一直挂着）；
 * 3. **句柄要晚点才有的**（[soko.ekibun.ffmpeg.AvFormat] 的 `pctx`）—— 既不属于前两条，
 *    就**不要继承本类**，改为**内部持有一个本类的子类** —— 那个子类在自己的构造期拿到
 *    句柄，外层 façade 只负责转发。这样「什么时候建」被关在内部类里，外层无需惰性语义。
 *
 * 指针**不可更改**（没有 `setPtr`）：归还之后句柄就作废了，留一个能改的槽位只会
 * 招来 use-after-free。「是否已经归还」看 [isClosed]，不看指针是否为 0。
 *
 * ## 读指针：两扇挂起门 + 一扇同步门 + 一扇裸读
 *
 * 句柄由 [initPtr] 给出（默认就是构造参数 `heldPtr`，它是 `private`），子类只能走这四个
 * 入口 —— 四个入口读的都是同一个 [initPtr]：
 *
 * - [withPtr] —— `suspend`，**做 native 调用的标准形态**：把句柄压进块、连同块一起
 *   投递到归属 dispatcher，于是「在归属线程上」与「拿到句柄」合并成一个动作。
 * - [ptrValue] —— `suspend`、对外，只要句柄、不打算顺带跑一段代码时用它；内部就是
 *   `withPtr { it }`。
 * - [withPtrSync] —— 同步版 [withPtr]，给**没有协程上下文**的入口用（JNI 直接进来的
 *   回调链）。已在归属线程上就地执行，否则 `runBlocking` 投过去。
 * - [ptr] —— `protected`、同步、**已弃用**：不判断也不投递，就地裸读；[soko.ekibun.quickjs.JSRef]
 *   还把它提成了 `public`，好让 `QuickJS` 那一侧读一个**已经确定在归属线程上**的句柄。
 *
 * ⚠️ 四扇门**都不看 [isClosed]**。关闭是「先标记、后释放」，而归还动作恰好跑在
 * 「已标记、未销毁」那个窗口里（[releaseImpl] 自己就要读句柄），拿标记当门会把归还一起
 * 挡在外面。「标记即拒绝」只适用于**新操作**，由子类自己判（见 [soko.ekibun.quickjs.QuickJS]
 * 的 `evaluate` / `jsCallImpl`）。
 *
 * ## 归属 dispatcher + 构造线程
 *
 * 「我此刻在不在归属线程上」这件事，两套判据各管一半：
 *
 * - **挂起侧**（[withPtr] / [ptrValue]）看**协程上下文里的 dispatcher**：
 *   `coroutineContext[ContinuationInterceptor] === dispatcher` 成立就不用切。这条不需要
 *   线程对象，也不会误判。
 * - **非挂起侧**（[withPtrSync]、给 JNI 回调链用）没有协程上下文可看，只能跟一个 [Thread]
 *   对象比 —— 那就是**构造线程**。
 *
 * 线程对象为什么只能是构造期的快照：`CoroutineDispatcher` **无法反查自己的线程** ——
 * `newSingleThreadExecutor().asCoroutineDispatcher()` 返回的
 * `ExecutorCoroutineDispatcherImpl` 没覆写 `isDispatchNeeded`（恒为 `true`），问不出
 * 「我在不在你的线程上」；而唯一能反查的手段（构造期投递一次取 `currentThread()`）在
 * 归属线程上构造时就是自己等自己。**构造期快照是唯一拿得到的**，于是配一条不变量来让它
 * 准确的代价变得可接受：**带 dispatcher 的子类都在归属线程上构造** —— [soko.ekibun.quickjs.QuickJS]
 * 用挂起工厂 `create()` 强制了这一点，`AvFormat` / `AvCodec` 本来就在 `withContext` 里构造。
 *
 * ⚠️ 快照失准的后果**只是多一次投递**（[withPtrSync] 会白跑一趟 `runBlocking` 投到归属
 * 线程上），**不影响正确性**。这与曾经那扇「拿构造线程做跨线程读校验」的弃用门不同：那扇
 * 门的快照失准会**放过真正的误用**。
 *
 * `dispatcher` 为 `null` 表示「本类不承诺线程归属」：[submit] 就地执行、[withPtr]
 * 直接读、[withPtrSync] 不做线程判断。那些还没注入 dispatcher 的子类（`AvFrame` /
 * `AvPacket` / `AvStream` / `JSRef`…）即此类，行为与改造前一致。
 *
 * ## 关闭是「先标记、后释放」的异步动作
 *
 * [close] **不等释放完成**：它只把释放动作投进 `dispatcher` 的队列就返回，
 * [markClosed] 先一步置位 —— 标记了就当作已经删除，之后的访问一律先查 [isClosed]。
 * 不等返回不会导致 use-after-free：同一个单线程 dispatcher 是 **FIFO** 的，
 * 释放消息一定排在「它之前投递的所有操作」之后、「它之后投递的所有操作」之前。
 *
 * 需要确定性的场合（例如关门前清算泄漏清单）改用 [closeDeferred] 拿 [Deferred] 去 await。
 * ⚠️ [isClosed] **不参与**读句柄与投递的判据（见「读指针」一节）：归还动作恰恰跑在
 * 「已标记、未销毁」这个窗口里。
 *
 * ## 不设 dispatcher 时是什么行为
 *
 * [submit] 就地**跑完**再返回（内部是一次 `runBlocking`，块里不挂起时等于直接调用），
 * 于是暂未改造的子类零改动即可接入：它们的 `close()` 仍是同步的，抛的异常照旧直接
 * 传给调用方（投递路径的异常会被收进 [Deferred]，只有 `await()` 才拿得到）。
 *
 * ## 默认 [releaseImpl] 为什么抛异常
 *
 * 宁可吵，不要静默：子类若没有真正实现归还，调用它的 `close()` 会立刻抛
 * [UnsupportedOperationException]，而不是假装成功地把资源留在那儿。归还不了的两类
 * 典型原因：
 *
 * - **要等线程**：释放必须回到创建它的那条 native 线程上执行，于是释放函数是
 *   `suspend` 的，没法塞进 `close()`。这类子类要改写 [releaseHint] 指明
 *   该调哪个函数（见 [soko.ekibun.ffmpeg.AvFormat] 的 `closeAsync`）；
 * - **只是借用**：指针归别人所有，自己不该也不能释放（见 [soko.ekibun.ffmpeg.AvStream]）。
 *
 * ⚠️ 继承它只是标记，**不负责归还**：`use {}` / `invokeOnCompletion` 这类自动调用点
 * 仍然会在末尾调 `close()`，所以「借用」和「要等线程」的两类必须约定好不会被
 * 自动关掉，否则抛出来的异常会盖掉真正的业务逻辑。
 */
abstract class Pointer protected constructor(
  private val heldPtr: Long = 0,
  private val dispatcher: CoroutineDispatcher? = null,
) : AutoCloseable {
  /**
   * **构造线程** —— [withPtrSync] 判断「要不要投递」用的快照。
   *
   * 它只在「带 dispatcher 的子类都在归属线程上构造」这条不变量下才等于归属线程；即便
   * 不成立也不会错，只是 [withPtrSync] 会多投递一次（见类文档「归属 dispatcher + 构造
   * 线程」）。想要一条绝对可靠的判据就得问 dispatcher 自己，而它答不了 —— 同上。
   */
  private val thread: Thread = Thread.currentThread()

  /**
   * 句柄来源。默认就是构造参数 [heldPtr]，**句柄要拿 `this` 去 native 换的子类覆写它**
   * （见类文档「指针从哪来」第 2 条）。
   *
   * ⚠️ 两条硬要求：
   * 1. **无副作用、每次返回同一个值** —— [ptr] 的 getter 到读句柄时才调它，读几次就调几次。
   *    不能在这里直接调 native 新建句柄（那成了每次读都新建），正确写法是在子类的属性
   *    初始化器里算好、存进一个 `private val`，这里只读那个字段；
   * 2. **看不到子类的构造参数属性** —— 它**不是**在基类构造期被调用的（那样子类字段还是
   *    0，且编译器不报错）。
   */
  protected open fun initPtr(): Long = heldPtr

  /**
   * 同步读句柄 —— **不做任何线程校验**，已弃用。
   *
   * 它曾经是「归属线程」那扇带校验的门，判据是构造期快照下来的线程。那条判据站不住：
   * 快照只在「构造恰好发生在归属线程上」时才等于归属线程，而本工程里带 dispatcher 的
   * 子类全是这么构造的 —— 于是校验看起来一直有效，实则只是巧合。更要紧的是
   * `CoroutineDispatcher` 根本无法反查自己的线程（见类文档第 1 条），这条路补不齐。
   *
   * 现在它**不判断也不投递**：读得到就读。跨线程读的正确性由调用方自己保证。
   *
   * 还离不开它的只剩一种场合：**已经确定自己在归属线程上**、又不想背 [withPtr] 那层
   * 调度语义的地方（[soko.ekibun.quickjs.JSRef] 把它提成 `public` 就是这个用途 ——
   * `QuickJS` 侧要读一个 native 交出来的、此刻必然在 JS 线程上的句柄）。剩下的调用点
   * 一律改用 [withPtr]（要在归属线程上跑一段代码）、[ptrValue]（只要句柄）或
   * [withPtrSync]（非挂起入口要跑一段代码）。
   */
  @Deprecated(
    "同步读句柄不做线程校验，跨线程误用不会被拦下；挂起上下文里改用 ptrValue()",
    level = DeprecationLevel.WARNING,
  )
  protected open val ptr: Long get() = initPtr()

  /**
   * 在**归属 dispatcher 上**、带着句柄跑一段挂起代码 —— native 调用的标准形态。
   *
   * 它把「拿到句柄」与「在归属线程上」合成一个动作：`dispatcher` 非空时，块被投递过去
   * 并以**参数形式**收到句柄，于是块里不必再各自去读一遍指针。
   *
   * - 已在归属 dispatcher 上：**就地执行**，零派发（判据与 [ptrValue] 相同）；
   * - `dispatcher` 为 `null`：就地执行、不切线程（本类不承诺线程归属）。
   *
   * 与手写 `withContext(dispatcher) { … }` 的关系：**语义相同，是它的收口版** ——
   * 块一样在归属 dispatcher 上跑，只是句柄由基类压进来，不必在块里再读一次。
   *
   * ⚠️ `internal` 而不是 `protected`：[soko.ekibun.quickjs.QuickJS] 的调用点遍布
   * native 回调链与 façade 内部，`protected` 到不了那些地方。
   *
   * ⚠️ 它**不查 [isClosed]**（理由见类文档「读指针」一节）：归还动作跑在「已标记、
   * 未销毁」的窗口里，把标记当门会把归还一起挡掉。「标记即拒绝」由子类对新操作自己判。
   */
  internal suspend fun <T> withPtr(block: suspend (Long) -> T): T {
    val d = dispatcher ?: return block(initPtr())
    if (currentCoroutineContext()[ContinuationInterceptor] === d) return block(initPtr())
    return withContext(d) { block(initPtr()) }
  }

  /**
   * [withPtr] 的**同步版** —— 给没有协程上下文的入口用（JNI 直接进来的回调链、
   * `AutoCloseable.close()` 这类非挂起签名）。
   *
   * - 已在归属线程上（跟构造线程比）：**就地执行**，零派发；
   * - 否则 `runBlocking` 投到归属 dispatcher 上跑完再返回；
   * - `dispatcher` 为 `null`：就地执行、不判断线程（本类不承诺线程归属）。
   *
   * ⚠️ 它会在**调用线程上阻塞**等归属 dispatcher 空出来，所以在归属线程自己身上调它
   * 最划算（就地）。同理，别在「归属线程正等着你返回」的场合调它。
   * 同样**不查 [isClosed]**。
   */
  internal fun <T> withPtrSync(block: (Long) -> T): T {
    val d = dispatcher ?: return block(initPtr())
    if (Thread.currentThread() == thread) return block(initPtr())
    return runBlocking(d) { block(initPtr()) }
  }

  /**
   * 读指针：**挂起**，任何线程都能调。
   *
   * 判据是**协程上下文里的 dispatcher**，不是线程快照：`dispatcher` 非空时，若当前
   * 正跑在它上面（`coroutineContext[ContinuationInterceptor] === dispatcher`）就
   * **就地读**，否则 `withContext` 过去读。实现就是 `withPtr { it }`，判据只有一处。
   *
   * 就地读那条快路径顺带把「已经在归属线程上」这件事保住了：读句柄的动作因此总在归属
   * 线程上发生（跨上下文时读的是 `withContext(d) { … }` 里的那一读）。
   *
   * `dispatcher` 为 `null` 时直接读、不切线程（本类不承诺线程归属）。
   */
  suspend fun ptrValue(): Long = withPtr { it }

  /**
   * 把动作**投递**到归属 dispatcher。
   *
   * 返回的 [Deferred] 完成时动作已落地；不 `await()` 就是「发消息、不等结果」。
   * `dispatcher` 为 `null` 时就地跑完再返回 —— 此时 `block` 抛出的异常直接传给调用方，
   * 不会被收进 [Deferred]。
   *
   * ⚠️ `internal` 而不是 `protected`，理由与 [withPtr] 相同：[soko.ekibun.quickjs.QuickJS]
   * 的内部调用点也要用它，`protected` 到不了那些地方。别自己抄一份
   * `CoroutineScope(dispatcher).async { }` —— 投递语义（`dispatcher` 为 `null` 时就地跑完
   * 再返回）只在这里维护一份。
   *
   * ⚠️ 兜底那条 `CompletableDeferred(value = …)` 必须写**命名实参**：省掉它就成了
   * `CompletableDeferred(result)`，一旦 `T` 被实例化成 `Job` 之类，字面量式的重载解析会
   * 挑中 `CompletableDeferred(parent: Job?)`，造出一个**永不完成**的 Deferred 而 await 挂死
   * —— 同一个坑已在 [soko.ekibun.quickjs.QuickJS.closeAndCollect] 踩过一次。
   */
  internal fun <T> submit(block: suspend () -> T?): Deferred<T?> =
    dispatcher?.let { CoroutineScope(it).async { block() } }
      ?: CompletableDeferred(value = runBlocking { block() })

  private val closedFlag = AtomicBoolean(false)

  /**
   * 是否已经[关闭][close]。
   *
   * 关闭是**先标记**的：标记了就当作已经删除，哪怕释放动作还排在队列里没跑。
   * [close] 可能从任何线程发起，所以底层是原子的。
   */
  val isClosed: Boolean get() = closedFlag.get()

  /** 抢占「关闭」这唯一的一个名额。抢到返回 `true`，已经关过返回 `false`。 */
  protected fun markClosed(): Boolean = closedFlag.compareAndSet(false, true)

  /** [close] 的可等待版本：[Deferred] 完成时 [releaseImpl] 已经跑完。 */
  protected fun closeDeferred() = if (!markClosed()) CompletableDeferred(Unit) else submit { releaseImpl() }

  /** 投递一次释放动作并立即返回，**不等**它跑完。 */
  override fun close() {
    closeDeferred().invokeOnCompletion { e ->
      e?.printStackTrace()
    }
  }

  /** [releaseImpl] 抛异常时说明「到底该怎么归还」的一句话。子类按自己的情况改写。 */
  protected open val releaseHint: String
    get() =
      "它自己归还不了：要么释放必须挂起（要等 native 线程），" +
        "要么它只是借用别人的指针、压根不拥有它"

  /**
   * 真正把 native 资源还回去。基类保证**只跑一次**（[markClosed] 抢到才跑），
   * 且**跑在归属 dispatcher 上**（`dispatcher` 为 `null` 时跑在调用线程上）——
   * 所以里面可以直接用 [ptrValue] 读句柄，它此时走的就是那条就地读的快路径。
   *
   * ⚠️ 它跑在 [markClosed] **之后**，所以读句柄那几扇门**都不能**拿 [isClosed] 当门
   * ——否则归还自己就被挡在门外，`close()` 会「返回成功、其实什么都没释放」。
   *
   * 默认实现抛 [UnsupportedOperationException] —— 见 [releaseHint]。
   */
  protected open suspend fun releaseImpl(): Unit =
    throw UnsupportedOperationException(
      "${javaClass.simpleName} 没有可阻塞的归还实现：$releaseHint。" +
        "native 资源不会被 GC 回收，用完后必须显式归还。",
    )
}
