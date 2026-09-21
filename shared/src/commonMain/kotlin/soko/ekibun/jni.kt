package soko.ekibun

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.invoke
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

expect fun jniLoadLibrary(name: String)

/**
 * 归属线程的调度器 —— **它自己就是一条 [CoroutineDispatcher]**，外加一份归属线程的快照。
 *
 * 为什么继承而不是包一层 `val dispatcher: CoroutineDispatcher`：
 *
 * - 它可以直接当上下文元素用（`withContext(d)`、`CoroutineScope(d)`），调用点不必再
 *   `.dispatcher` 拐一层；
 * - 可以覆写 [isDispatchNeeded]，把「已经在归属线程上就别派发」交给 kotlinx 的判据 ——
 *   包一层做不到：`asCoroutineDispatcher()` 返回的 `ExecutorCoroutineDispatcherImpl`
 *   是不可改写的黑盒，它的 `isDispatchNeeded` 恒为 `true`。
 *
 * **构造函数就是唯一入口**：[name] 就是归属线程的名字（`ThreadDispatcher("avformat")`），
 * 留空则用 `thread-dispatcher`。[thread] 是**构造期快照**：`CoroutineDispatcher` 没有反查
 * 自己线程的接口，唯一的取法就是「往它上面投递一次、取 `currentThread()`」。也正因为要在
 * 构造期投递，**别在归属线程上构造自己** —— 那是自己等自己。
 *
 * ⚠️ 关它是**显式动作**（[close]，转发 `ExecutorService.shutdown()`）：那条线程活到被关或进程
 * 退出。共享实例（`QuickJS.sharedDispatcher`、`AvFormat` 的伴生对象）**不该关** —— 全进程就那一
 * 条；「每个实例各起一条」的用法（`AvCodec`）由 `Pointer` 的 `closeDispatcherOnClose`
 * 交给基类代收 —— 独占者不必自己攥着 dispatcher 才能关它。
 */
open class ThreadDispatcher(
  /** 归属线程的名字 —— 构造期起的那条单线程就叫它。 */
  name: String = "thread-dispatcher",
) : CoroutineDispatcher(),
  AutoCloseable {
  /**
   * 真正把动作落到那条线程上的执行器。类型**必须**写 [ExecutorCoroutineDispatcher] ——
   * `asCoroutineDispatcher()` 有两个重载：收 `Executor` 的那个返回 [CoroutineDispatcher]
   * （**没有** `close()`），收 `ExecutorService` 的才返回可关的 `ExecutorCoroutineDispatcher`。
   * 把类型削平成 `CoroutineDispatcher`，这条线程就再也关不掉了。
   */
  private val delegate: ExecutorCoroutineDispatcher =
    Executors
      .newSingleThreadExecutor { runnable ->
        Thread(runnable, name)
      }.asCoroutineDispatcher()

  /** 归属线程 —— 构造期快照，此后不变。 */
  val thread: Thread = runBlocking(delegate) { Thread.currentThread() }

  private val closedFlag = AtomicBoolean(false)

  /** 是否已经[关闭][close]；关掉之后**任何新投递都会被挡在门外**（见 [dispatch]）。 */
  val isClosed: Boolean get() = closedFlag.get()

  /**
   * 往归属线程上投递。
   *
   * ⚠️ **关掉之后一律抛 [IllegalStateException]**，就在 `delegate.dispatch` **之前**拦下。
   * 这一手是补上去的：kotlinx 自己会兜住 `RejectedExecutionException` 并**静默改投
   * `Dispatchers.IO`**（原文见 [close] 的 KDoc）——「归属线程」这个承诺于是被悄悄打破，
   * 本该在归属线程上跑的 native 调用落到 IO 线程池上。对一个拿线程归属当正确性前提的桥来说，
   * 那比当场抛异常糟得多。拦截必须发生在 `delegate.dispatch` **之前**：一旦放进去，
   * kotlinx 就把异常换成「改投」了，外面再也吵不起来。
   *
   * ⚠️ 拦的是**新投递**，拦不住已经在队列里的：`shutdown()` 不等待在途任务，已提交的照跑完。
   */
  override fun dispatch(
    context: CoroutineContext,
    block: Runnable,
  ) {
    check(!closedFlag.get()) {
      "ThreadDispatcher(${thread.name}) 已经关掉，别再往它上面投递 —— " +
        "放开的话 kotlinx 会把被拒的块改投到 Dispatchers.IO 上，native 调用就跑在 IO 线程池上了"
    }
    delegate.dispatch(context, block)
  }

  /** 已经在归属线程上就地跑，别的线程才派发。 */
  override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== thread

  /**
   * 关掉归属线程 —— 就是 `ExecutorService.shutdown()`：**不等待**在途任务（已提交的会跑完、
   * 线程随后退出）。
   *
   * ⚠️ **关掉之后再投递，拿不到 `RejectedExecutionException`** —— 2026-09-21 实测：
   * kotlinx 在 `ExecutorCoroutineDispatcherImpl.dispatch` 里把 `RejectedExecutionException`
   * **兜住了**（`jvmMain/Executors.kt:133-141`）：先取消上下文里的 Job，再把块
   * **改投到 `Dispatchers.IO`**。于是同一个「关掉」有三种表现，**没有一种是「吵着拒绝」**：
   *
   * | 调用 | 表现 |
   * | --- | --- |
   * | 裸 `dispatch`（上下文里没 Job） | **静默**：块真的跑了，跑在 `DefaultDispatcher-worker-N` 上 |
   * | `withContext(d)` / `runBlocking(d)` / `submit { }.await()` | 抛 `CancellationException("The task was rejected")`，`RejectedExecutionException` 只是它的 `cause` |
   * | `launch` / 不 `await` 的 `submit { }` | **完全静默**：Job 被取消，块恢复时被丢弃，`CoroutineExceptionHandler` 收不到 |
   *
   * ⇒ 对「归属线程」这个承诺来说，这是**被悄悄打破**：本该在归属线程上跑的 native 调用可能
   * 落在 IO 线程池上。所以本类**自己拦了一手**（见 [dispatch]）：关掉之后的投递当场抛
   * [IllegalStateException]，别指望能从 kotlinx 那边听见动静。
   *
   * ⚠️ **只有独占者才该调**：`QuickJS.sharedDispatcher` / `AvFormat` 的伴生实例是全进程共享的，
   * 关它等于给后面所有调用者埋雷。拦一手只是把「埋雷」换成「当场炸」—— 炸点离肇事点仍然远，
   * 传 `closeDispatcherOnClose = true` 之前该确认的还得确认。
   *
   * 关闭是**幂等**的：[isClosed] 的置位与 `shutdown()` 都只认第一次。
   */
  override fun close() {
    closedFlag.set(true)
    delegate.close()
  }

  override fun toString(): String = "ThreadDispatcher(${thread.name})"
}

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
 * | 保管指针 | `nativePtr`（构造参数的第一个，留 `null` 则问 [initPtr]）/ [ptr] / [withPtr] / [withPtrSync] | **创建即指定**，之后不可更改 |
 * | 归属 dispatcher | `dispatcher`（`private`）/ [submit] / [withPtr] | 有就把动作**投递**过去，没有就就地执行 |
 * | 只关一次 | [isClosed] / [markClosed] | 抢占式标记，**先标记**再释放 |
 *
 * ## 指针从哪来：两条路，基类都保证「首次读句柄时已经就位」
 *
 * 按**句柄是怎么到手的**分两条路：
 *
 * 1. **构造参数里就有句柄**（[soko.ekibun.ffmpeg.AvFrame]、[soko.ekibun.ffmpeg.AvPacket]、
 *    [soko.ekibun.ffmpeg.AvStream]、[soko.ekibun.quickjs.JSRef]，以及
 *    [soko.ekibun.ffmpeg.AvCodec] / [soko.ekibun.ffmpeg.AvFormat] /
 *    [soko.ekibun.ffmpeg.AvPlayback] 各自的内部子类…）—— **最常见、也最省事的一条**：
 *    把句柄当**基类构造参数的第一个实参**传进来（`Pointer(nativePtr, dispatcher)`），
 *    一个成员都不用覆写；
 * 2. **句柄要拿 `this` 去 native 换**（[soko.ekibun.quickjs.QuickJS]）—— 第一个实参留
 *    `null`、**覆写 [initPtr]**，由它就地算出来。不能直接把 `initContext(this, …)` 塞进
 *    `super(...)` 的实参 —— `this` 在 super 调用点还不可引用（Kotlin 报
 *    `cannot access '<this>' before the instance has been initialized`，Java 同样
 *    禁止；JEP 513 放宽的只是「super 之前可以有语句」）。
 *
 * 覆写那条路（唯一有坑的一条）的时机由基类担保 —— 两个静默错值各记一句：
 *
 * - **不在构造期调**：基类把这次调用推迟到**首次读句柄**那一刻才发生，于是覆写体里读到的
 *   子类字段/构造参数都已经就位。挪回构造期就必错：实测 `putfield` 排在
 *   `invokespecial <init>` **之后**，基类构造期子类字段全是 `0`，而且**编译器不报错**。
 *   `QuickJS` 的 `timeout` 按那个时机算就是 `0`，而 native 侧 `JS_SetInterruptHandler` 里
 *   `timeoutMs <= 0` 是**直接放行** —— 死循环中断整个关掉，表现就是「测试跑着跑着不动
 *   了」。`PointerTest.initPtrSeesConstructionState` 钉住它；
 * - **首次读发生在归属线程上**（[withPtr] / [withPtrSync] 都把读放在投递之后），所以
 *   「建句柄要挑线程」的子类不必自己安排 —— `JS_NewRuntime` 正是靠这条让 `stack_top`
 *   基准落在归属线程上（见 [soko.ekibun.quickjs.QuickJS.initPtr]）。
 *
 * ⚠️ 第一个实参**不拿 `0` 当「没交」的哨兵**：`0` 是合法的空句柄（借用型的空壳就传
 * `0L`），拿它当哨兵会把「真的交了 `0`」误判成「没交」、转头去调 [initPtr]。要表示「没
 * 交」就留 `null`；覆写路径返回 `0` 则是**建句柄失败**，基类会 `check` 出来。
 *
 * 还有一条**不属于上面两条**的路：**句柄要晚点才有的**（[soko.ekibun.ffmpeg.AvFormat] /
 * [soko.ekibun.ffmpeg.AvCodec] 的 `ctx`）—— 那就不要继承本类，改为**内部持有一个本类的
 * 子类**：那个子类在自己的构造期拿到句柄，外层 façade 只负责转发。这样「什么时候建」被
 * 关在内部类里，外层无需惰性语义。
 *
 * 指针**不可更改**（没有 `setPtr`）：归还之后句柄就作废了，留一个能改的槽位只会
 * 招来 use-after-free。「是否已经归还」看 [isClosed]，不看指针是否为 0。
 *
 * ## 读指针：一扇挂起门 + 一扇同步门 + 一个公开的裸读
 *
 * 句柄存在基类里（来自构造参数，或由覆写的 [initPtr] 算出；**首次读时才求值**），子类读
 * 它只有这三个入口 —— 三个入口取的都是同一份句柄：
 *
 * - [withPtr] —— `suspend`，**做 native 调用的标准形态**：把句柄压进块、连同块一起
 *   投递到归属 dispatcher，于是「在归属线程上」与「拿到句柄」合并成一个动作。要挑线程
 *   的地方一律走它。
 * - [withPtrSync] —— 同步版 [withPtr]，给**没有协程上下文**的入口用（JNI 直接进来的
 *   回调链）。已在归属线程上就地执行，否则 `runBlocking` 投过去。
 * - [ptr] —— `public`、同步、**不判断也不投递**的裸读：读得到就读。给「已经确定在归属
 *   线程上」的场合用（[withPtr] 块里、native 回调链里），形态就是 `frame.ptr` /
 *   `packet.ptr` 这类直接取值。它对应旧版那扇挂起门 `ptrValue()`（= `withPtr { it }`），
 *   后者已删掉 —— 那些调用点本来就都在 [withPtr] 块里，多一层挂起没有意义。
 *
 * ⚠️ 三个入口**都不看 [isClosed]**。关闭是「先标记、后释放」，而归还动作恰好跑在
 * 「已标记、未销毁」那个窗口里（[releaseImpl] 自己就要读句柄），拿标记当门会把归还一起
 * 挡在外面。「标记即拒绝」只适用于**新操作**，由子类自己判（见 [soko.ekibun.quickjs.QuickJS]
 * 的 `evaluate` / `jsCallImpl`）。
 *
 * ## 归属 dispatcher + 归属线程
 *
 * 「我此刻在不在归属线程上」这件事，两套判据各管一半：
 *
 * - **挂起侧**（[withPtr]）看**协程上下文里的 dispatcher**：
 *   `coroutineContext[ContinuationInterceptor] === dispatcher` 成立就不用切。这条不需要
 *   线程对象，也不会误判；
 * - **非挂起侧**（[withPtrSync]、给 JNI 回调链用）没有协程上下文可看，只能跟一个 [Thread]
 *   对象比 —— 那就是 [ThreadDispatcher.thread]。
 *
 * 归属线程为什么由 [ThreadDispatcher] 自己记：`CoroutineDispatcher` **没有反查自己线程的
 * 接口**，唯一的取法就是「往它上面投递一次、取 `currentThread()`」；而 `asCoroutineDispatcher()`
 * 返回的 `ExecutorCoroutineDispatcherImpl` 是个不可改写的黑盒，连 `isDispatchNeeded` 都
 * 恒为 `true`，没地方挂这份快照。于是本工程自带 [ThreadDispatcher]：它**自己就是**
 * [CoroutineDispatcher]（能直接当上下文元素用），外加快照下来的 [Thread]，并把
 * `isDispatchNeeded` 改成「已在归属线程上就别派发」。
 *
 * ⚠️ 那份快照在「**不在归属线程上建自己**」时准确 —— 在归属线程上调 [ThreadDispatcher]
 * 是自己等自己。当前三个调用点都不在归属线程上（伴生对象初始化 / `withContext` 里）。
 *
 * ⚠️ 就算快照失准，后果**只是多一次投递**（[withPtrSync] 会白跑一趟 `runBlocking` 投到归属
 * 线程上），**不影响正确性**。这与曾经那扇「拿构造线程做跨线程读校验」的弃用门不同：那扇
 * 门的快照失准会**放过真正的误用**。
 *
 * 顺带一说：**「构造线程」不再是任何判据** —— 归属线程由 [ThreadDispatcher] 记，不再由
 * 构造现场推。所以「带 dispatcher 的子类都在归属线程上构造」这条旧不变量已经不需要了。
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
 * `closeDispatcherOnClose` 为真时，**归还落地之后**才关掉那条独占的 dispatcher —— 关早了会把
 * [releaseImpl] 一起拒在门外（那条线程的回收本身也是「一次归还」）。
 *
 * ## 不设 dispatcher 时是什么行为
 *
 * [submit] 就地**跑完**再返回（内部是一次 `runBlocking`，块里不挂起时等于直接调用），
 * 于是暂未改造的子类零改动即可接入：它们的 `close()` 仍是同步的，抛的异常照旧直接
 * 传给调用方（投递路径的异常会被收进 [Deferred]，只有 `await()` 才拿得到）。
 *
 * ## [releaseImpl] 为什么是 abstract
 *
 * 宁可吵，不要静默：**没有默认实现** —— 子类不写就编译不过，而不是继承一个空实现、
 * 让 `close()` 假装成功地把 native 资源留在那儿。入口由基类收口（投递到归属 dispatcher、
 * 只跑一次、句柄以参数交进来），子类只回答「怎么还」。
 *
 * 两类「还不了」的资源各有出路：
 *
 * - **要等线程**：释放必须回到创建它的那条 native 线程上执行 —— 那正是归属 dispatcher，
 *   基类 [submit] 已经投过去了，照常实现 [releaseImpl] 即可（见
 *   [soko.ekibun.ffmpeg.AvFormat] / [soko.ekibun.ffmpeg.AvCodec]）；
 * - **只是借用**：指针归别人所有，自己不该也不能释放 —— 那就**不要继承本类**
 *   （见 [soko.ekibun.ffmpeg.AvStream]），免得被 `use {}` / `invokeOnCompletion`
 *   这类自动调用点在末尾把别人的资源关掉。
 */
abstract class Pointer protected constructor(
  /**
   * native 句柄。**能在构造参数里交句柄的子类都走这里**（绝大多数）—— 把句柄当本构造
   * 函数的第一个实参传进来即可，什么都不用覆写。
   *
   * 留 `null`（默认）表示「本类自己拿不到句柄，得问覆写的 [initPtr] 要」—— 只有
   * [soko.ekibun.quickjs.QuickJS] 是这种：它要拿 `this` 去 `initContext` 换，而 `this`
   * 在 `super(...)` 的实参里还不可引用。
   *
   * ⚠️ 别拿 `0` 表示「没交」：`0` 是**合法的空句柄**（借用型的空壳就这么传），哨兵会把
   * 「真的交了 `0`」误判成「没交」而转头去问 [initPtr]。要表示「没交」就留 `null`。
   *
   * 它还有第二个用途：[closeDeferred] 借它区分「句柄本来就在手上」与「得靠 [initPtr]
   * 现算」—— 后者若从没兑现过，说明 native 资源压根没建，`close()` 就不必为了「还」去
   * 把它建出来。
   */
  private val nativePtr: Long? = null,
  private val dispatcher: ThreadDispatcher? = null,
  /**
   * 本对象**独占** `dispatcher` 吗 —— 独占的才在归还落地之后把它关掉。
   *
   * 默认 `false`。共享实例（`QuickJS.sharedDispatcher`、`AvFormat` 的伴生对象）全进程就一条，
   * 谁都没权关：关掉之后别的持有者一投递，当场拿到 `IllegalStateException` —— 那是
   * [ThreadDispatcher] 自己拦的一手；不加它就会退化回 kotlinx 的兜底（`CancellationException`，
   * 裸 `dispatch` 那条甚至静默改投 `Dispatchers.IO`，见 [ThreadDispatcher.close] 的 KDoc）。
   *
   * ⚠️ 传 `true` 的前提是「这条 dispatcher 是本对象自己建的、没有别人用」—— 基类查不了
   * 这一点（[ThreadDispatcher] 不知道有几家引用它）。传错的后果是**延迟爆**：本对象关掉之后，
   * 别的持有者才在别处炸，报错点离肇事点很远。
   *
   * 关闭时机是**归还落地之后**（挂在 [closeDeferred] 的完成上）—— 不能提前到「投递完就关」：
   * [releaseImpl] 还没跑就被 shutdown 拒之门外，native 资源静默留下。
   */
  private val closeDispatcherOnClose: Boolean = false,
) : AutoCloseable {
  /**
   * 句柄 —— 构造参数给了就用它，没给（`null`）就问 [initPtr] 要，**要到就记住**。
   *
   * `by lazy` 在这里不是为了省几次虚调用，而是**时机**：它把求值推迟到**首次读句柄**那
   * 一刻 —— 那时构造早已完成、子类字段与构造参数全都就位，而读又落在归属线程上（三扇门
   * 都把读放在投递之后）。这两条恰好是「建句柄」需要的全部前提。
   *
   * 用 `by lazy` 的默认模式（`SYNCHRONIZED`）：句柄可能被两侧同时首读（[withPtrSync] 的
   * JNI 回调链和 [withPtr] 的协程侧），而 [initPtr] **只准跑一次**。
   */
  private val ptrLazy =
    lazy {
      nativePtr ?: run {
        assert(dispatcher == null || dispatcher.thread === Thread.currentThread()) {
          "${javaClass.simpleName}.initPtr() 必须在归属线程上跑，" +
            "现在却在 ${Thread.currentThread().name}（归属线程是 ${dispatcher?.thread?.name}）"
        }
        initPtr().also {
          check(it != 0L) {
            "${javaClass.simpleName}.initPtr() 返回 0 —— 句柄没建起来；" +
              "`0` 不是合法 native 句柄，别把它当有效指针用下去"
          }
        }
      }
    }
  val ptr: Long by ptrLazy

  /**
   * 句柄来源 —— **只有**构造参数留 `null` 的子类才需要覆写它
   * （[soko.ekibun.quickjs.QuickJS] 是当前唯一一个）；「构造参数里就有句柄」的子类什么
   * 都不用写。
   *
   * 覆写时的三条：
   * 1. **在本函数里直接算**（`QuickJS` 就是直接 `initContext(this, …)`）—— 基类保证它
   *    **只被调一次**、结果存进 [ptr]，不必再自己备一个 `private val` 中转；
   * 2. **不在构造期被调** —— 首次读句柄时才调，那时子类字段与构造参数已就位。挪回构造期
   *    必错：实测 `putfield` 排在 `invokespecial <init>` **之后**，构造期子类字段全是
   *    `0` 而且**编译器不报错**；`QuickJS` 的 `timeout = 0` 会把死循环中断整个关掉
   *    （见类文档）；
   * 3. **跑在归属线程上** —— 首次读发生在 [withPtr] / [withPtrSync] 的块里，也就是投递
   *    之后。建句柄要挑线程的子类（`JS_NewRuntime` 要记 `stack_top`）靠的正是这条。
   *
   * 默认实现抛 [IllegalStateException]：既没交句柄、也没覆写本函数的子类，一读句柄就吵
   * 出来 —— 总好过把 `0` 静默当合法句柄一路用到 native 里。
   */
  internal open fun initPtr(): Long =
    throw IllegalStateException(
      "${javaClass.simpleName} 既没在构造参数里交出句柄，也没覆写 Pointer.initPtr()",
    )

  /**
   * 在**归属 dispatcher 上**、带着句柄跑一段挂起代码 —— native 调用的标准形态。
   *
   * 它把「拿到句柄」与「在归属线程上」合成一个动作：`dispatcher` 非空时，块被投递过去
   * 并以**参数形式**收到句柄，于是块里不必再各自去读一遍指针。
   *
   * - 已在归属 dispatcher 上：**就地执行**，零派发（判据就是当前协程上下文里那个
   *   dispatcher 是不是归属那个）；
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
    val d = dispatcher ?: return block(ptr)
    if (currentCoroutineContext()[ContinuationInterceptor] === d) return block(ptr)
    return withContext(d) { block(ptr) }
  }

  /**
   * [withPtr] 的**同步版** —— 给没有协程上下文的入口用（JNI 直接进来的回调链、
   * `AutoCloseable.close()` 这类非挂起签名）。
   *
   * - 已在归属线程上（跟 [ThreadDispatcher.thread] 比）：**就地执行**，零派发；
   * - 否则 `runBlocking` 投到归属 dispatcher 上跑完再返回；
   * - `dispatcher` 为 `null`：就地执行、不判断线程（本类不承诺线程归属）。
   *
   * ⚠️ 它会在**调用线程上阻塞**等归属 dispatcher 空出来，所以在归属线程自己身上调它
   * 最划算（就地）。同理，别在「归属线程正等着你返回」的场合调它。
   * 同样**不查 [isClosed]**。
   */
  internal fun <T> withPtrSync(block: (Long) -> T): T {
    val d = dispatcher ?: return block(ptr)
    if (Thread.currentThread() == d.thread) return block(ptr)
    return runBlocking(d) { block(ptr) }
  }

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

  /**
   * [close] 的可等待版本：[Deferred] 完成时 [releaseImpl] 已经跑完。
   *
   * 三种情形：
   * - **已经关过**（[markClosed] 没抢到名额）：返回一个已完成的 [Deferred]，什么也不做；
   * - **句柄从没兑现过**、而且它本来要靠 [initPtr] 现算：说明 native 资源**压根没建**，
   *   于是不为了「还」去把它建出来 —— 但**照样先标记**（[isClosed] 立刻为真），否则关闭
   *   之后的新操作会白白建出一个没人释放的上下文；
   * - 其余：投递 [releaseImpl]，句柄以参数交给它。
   *
   * `nativePtr == null` 这半个判据不能省：句柄是**构造参数交进来**的子类（[soko.ekibun.quickjs.JSRef]
   * / [soko.ekibun.ffmpeg.AvFrame]）手上**已经有**资源了，`ptr` 读没读过都欠着一次归还。
   *
   * 返回的是 `Deferred<Unit?>` 而不是 `Deferred<Unit>`：归还路径经 [submit]，而它的返回
   * 类型是 `Deferred<T?>`（块允许交回 `null`）—— 这里只是跟着它走，别再套一层转换。
   *
   * `closeDispatcherOnClose` 为真时，**这条 [Deferred] 完成之后**才关那条独占的 dispatcher。
   * 挂在这里而不是 `close()` 里，是为了让两条路都覆盖到：「发完不管」的 [close]，以及
   * `FFPlayer.closeAsync()` 那种 `await()` 的。短路分支返回的是**已完成**的 Deferred，挂在它
   * 上面的关闭动作当场执行 —— 这正是「句柄都没建过也得收线程」的场合（线程在建
   * [ThreadDispatcher] 时就起了）。
   */
  fun closeDeferred(): Deferred<Unit?> {
    val done =
      if (!markClosed() || (nativePtr == null && !ptrLazy.isInitialized())) {
        CompletableDeferred(value = Unit)
      } else {
        submit { releaseImpl(ptr) }
      }
    // 独占的那条 dispatcher 在**归还落地之后**才关。顺序不能反：先 shutdown 的话，
    // releaseImpl 还没跑就被拒之门外，native 资源静默留下。
    if (closeDispatcherOnClose) {
      done.invokeOnCompletion { dispatcher?.close() }
    }
    return done
  }

  /**
   * 投递一次释放动作并立即返回，**不等**它跑完。
   *
   * 归还过程抛出的异常在这里只打印 —— 投递成功之后它进了 [Deferred]，没有任何调用者能
   * 接住；要拿到改用 [closeDeferred] 并 `await()`。
   *
   * ⚠️ 与之相对，**投递本身**被拒（dispatcher 已经关了）时异常是**同步**抛出的，走不到
   * 「只打印」那一步 —— 见 [ThreadDispatcher.dispatch]。
   */
  override fun close() {
    closeDeferred().invokeOnCompletion { e -> e?.printStackTrace() }
  }

  /**
   * 真正把 native 资源还回去 —— **子类只回答这一件事**，入口与时机由基类收口。
   *
   * 三条保证：
   * - 句柄**以参数交进来**（就是 [ptr] 那个值），不必自己去读、也就不会绕回读门；
   * - **只跑一次**（[markClosed] 抢到才跑）；
   * - **跑在归属 dispatcher 上**（`dispatcher` 为 `null` 时跑在调用线程上）。
   *
   * ⚠️ 它跑在 [markClosed] **之后**，所以读句柄那几个入口**都不能**拿 [isClosed] 当门
   * ——否则归还自己就被挡在门外，`close()` 会「返回成功、其实什么都没释放」。
   *
   * 没有默认实现是有意的：`abstract` 让「忘了写归还」在**编译期**就吵出来（见类文档）。
   */
  abstract suspend fun releaseImpl(ptr: Long)
}
