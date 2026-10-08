package soko.ekibun

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

expect fun loadLibrary(name: String)

/**
 * 起一条归属线程调度器 —— [ThreadDispatcher] 的**唯一** `expect`。
 *
 * [ThreadDispatcher] 本身是 `abstract`：本类只管归属线程那套语义，**怎么起一条独占线程、
 * 怎么认出「当前就在这条线程上」** 全部交给平台 —— 具体子类在各自的「平台族」层
 * （当前是 `jvmShared` 的 `ThreadDispatcherImpl`，Android 与桌面 JVM 共用一份）。
 *
 * 所以要加一族平台：写一个 [createThreadDispatcher] + 一个实现 `isOnCurrentThread` 的子类
 * 即可，[ThreadDispatcher] 与 [Pointer] 一个字不动。
 */
expect fun createThreadDispatcher(threadName: String): ThreadDispatcher

/**
 * 归属线程的调度器 —— **它自己就是一条 [CoroutineDispatcher]**，外加一份归属线程的身份。
 *
 * 为什么继承而不是包一层 `val dispatcher: CoroutineDispatcher`：
 *
 * - 它可以直接当上下文元素用（`withContext(d)`、`CoroutineScope(d)`），调用点不必再
 *   `.dispatcher` 拐一层；
 * - 可以覆写 [isDispatchNeeded]，把「已经在归属线程上就别派发」交给 kotlinx 的判据 ——
 *   包一层做不到：底层那条执行器是不可改写的黑盒，它的 `isDispatchNeeded` 恒为 `true`。
 *
 * ## 平台差异只有两条，其余全在本类
 *
 * [createThreadDispatcher]（怎么起一条独占线程）与 [isOnCurrentThread]（怎么认出「当前就是
 * 这条线程」）。抽象掉的**只是平台差异**，不是**归属线程这个约定** ——「所有 native 调用都在
 * 归属线程上、关掉后当场抛、不静默改投 IO」那套语义是本类自己的，两族必须一模一样。
 *
 * ## 构造
 *
 * [threadName] 是归属线程的名字，[delegate] 是**已经起好的那条执行器**（本类自己不起 ——
 * 名字与执行器都从构造点显式进来）。对外的构造形态是 `ThreadDispatcher("avformat")`，
 * 由伴生对象的 `invoke` 经 [createThreadDispatcher] 包一层。
 *
 * 关它是**显式动作**（[close]）：那条线程活到被关或进程退出。共享实例
 * （`QuickJS.sharedDispatcher`、`AvFormat` 的伴生对象）**不该关** —— 全进程就那一条；
 * 「每个实例各起一条」的用法（`AvCodec`）由 [Pointer] 的 `closeDispatcherOnClose` 交给基类代收。
 *
 * ## 跨平台时要重新定的一条
 *
 * **「线程还在不在」不进本类的抽象面**：`Thread.isAlive` / `Thread.join` 在 Kotlin/Native 上
 * 没有对应物（worker 有 `join` 但没有「活着没」）。而「关闭真的收掉了线程」必须在测试期可验 ——
 * 所以由平台族自己在子类上带（如 `ThreadDispatcherImpl` 那两条），`jvmTest` 直接断言它们。
 *
 * 另见 [Pointer.withPtrSync]：它跨平台最贵（JVM 上只是阻塞等队列，worker 模型下是一次真实的
 * worker 切换 + 栈复制 + 内存序屏障），改动它要连着调用点一起看。
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
abstract class ThreadDispatcher(
  /** 归属线程的名字 —— 构造期起的那条单线程就叫它。 */
  val threadName: String = "thread-dispatcher",
  private val delegate: CloseableCoroutineDispatcher,
) : CoroutineDispatcher(),
  AutoCloseable {
  companion object {
    operator fun invoke(threadName: String): ThreadDispatcher = createThreadDispatcher(threadName)
  }

  private val closedFlag = AtomicBoolean(false)

  /** 是否已经[关闭][close]；关掉之后**任何新投递都会被挡在门外**（见 [dispatch]）。 */
  val isClosed: Boolean get() = closedFlag.load()

  /**
   * 往归属线程上投递。
   *
   * **关掉之后一律抛 [IllegalStateException]**，就在 `delegate.dispatch` **之前**拦下。
   * 这一手必须在这里：kotlinx 自己会兜住「投递被拒」并**静默改投 `Dispatchers.IO`**，
   * 「归属线程」这个承诺于是被悄悄打破 —— 本该在归属线程上跑的 native 调用落到 IO 线程池上，
   * 那比当场抛异常糟得多。拦截必须发生在 `delegate.dispatch` **之前**：一旦放进去，kotlinx
   * 就把异常换成「改投」了，外面再也吵不起来（逐条表现见 [close] 的 KDoc）。
   *
   * 拦的是**新投递**，拦不住已经在队列里的：[close] 不等待在途任务，已提交的照跑完。
   */
  override fun dispatch(
    context: CoroutineContext,
    block: Runnable,
  ) {
    check(!isClosed) {
      "ThreadDispatcher($threadName) is closed"
    }
    delegate.dispatch(context, block)
  }

  /** 「此刻是不是那条归属线程」—— 平台原语。 */
  abstract fun isOnCurrentThread(): Boolean

  /** 已经在归属线程上就地跑，别的线程才派发。 */
  override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isOnCurrentThread()

  /**
   * 关掉归属线程：**不等待**在途任务（已提交的会跑完、线程随后退出）。
   *
   * **关掉之后再投递拿不到「投递被拒」这个异常** —— kotlinx 会兜住它并把块**静默改投到
   * `Dispatchers.IO`**（三种调用形态的实测表现见 skill `project-traps` 的 `silent-failures.md`
   * 「以为调度器关掉之后投递会抛 `RejectedExecutionException`」那条），于是「归属线程」这个
   * 承诺被悄悄打破。所以本类在 [dispatch] 上自己拦了一手。
   *
   * **只有独占者才该调**：`QuickJS.sharedDispatcher` / `AvFormat` 的伴生实例是全进程共享的，
   * 关它等于给后面所有调用者埋雷。拦一手只是把「埋雷」换成「当场炸」—— 炸点离肇事点仍然远，
   * 传 `closeDispatcherOnClose = true` 之前该确认的还得确认。
   *
   * 关闭是**幂等**的：[isClosed] 的置位与转发给底层执行器都只认第一次。
   */
  override fun close() {
    closedFlag.store(true)
    delegate.close()
  }

  override fun toString(): String = "ThreadDispatcher($threadName)"
}

/**
 * 持有 native 指针的对象的统一基类 —— 继承它就是**标记**「我持有一个 native 资源」。
 *
 * 这个标记不是装饰：本项目**不给 native 资源设 GC 兜底**（`Cleaner` 是 API 33、
 * `finalize()` 已待移除），所以忘记归还就是真的永久泄漏，归还必须是显式动作。
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
 * 1. **构造参数里就有句柄**（绝大多数）—— 当**基类构造参数的第一个实参**传进来
 *    （`Pointer(nativePtr, dispatcher)`），一个成员都不用覆写；
 * 2. **句柄要拿 `this` 去 native 换**（[soko.ekibun.quickjs.QuickJS]）—— 第一个实参留
 *    `null`、**覆写 [initPtr]**。不能把 `initContext(this, …)` 塞进 `super(...)` 的实参：
 *    `this` 在 super 调用点还不可引用。
 *
 * 覆写那条路的时机由基类担保 —— 两个静默错值各记一句：
 *
 * - **不在构造期调**（推迟到**首次读句柄**那一刻）：挪回构造期则子类字段全是 `0` 且
 *   **编译器不报错**，`QuickJS` 的 `timeout` 会把死循环中断整个关掉。
 *   `PointerTest.initPtrSeesConstructionState` 钉住它；
 * - **首次读发生在归属线程上**：[withPtr] / [withPtrSync] 都把读放在投递之后，所以
 *   「建句柄要挑线程」的子类不必自己安排 —— `JS_NewRuntime` 正是靠这条让 `stack_top`
 *   基准落在归属线程上。
 *
 * 第一个实参**不拿 `0` 当「没交」的哨兵**：`0` 是合法的空句柄，拿它当哨兵会把「真的交了
 * `0`」误判成「没交」。要表示「没交」就留 `null`；覆写路径返回 `0` 则是**建句柄失败**，
 * 基类会 `check` 出来。指针**不可更改**（没有 `setPtr`）。
 *
 * ## 读指针：一扇挂起门 + 一扇同步门 + 一个公开的裸读
 *
 * 三个入口取的都是同一份句柄：
 *
 * - [withPtr] —— `suspend`，**做 native 调用的标准形态**：把句柄压进块、连同块一起投递到
 *   归属 dispatcher，于是「在归属线程上」与「拿到句柄」合并成一个动作。要挑线程的地方一律走它；
 * - [withPtrSync] —— 同步版，给**没有协程上下文**的入口用（JNI 直接进来的回调链）；
 * - [ptr] —— `public`、同步、**不投递也不设 isClosed 门**的裸读（形态是 `frame.ptr`）。
 *   带一条**同线程断言**（JVM `-ea`）：跑错线程在测试期就炸，而不是偶发崩在 native 里。
 *
 * 前两个**先查 [isClosed]**（标记即抛）：关闭是「先标记、后释放」，标记了就当作已经删除。
 * 归还动作恰恰跑在「已标记、未销毁」那个窗口里，所以**不经过这两扇门** —— [releaseImpl] 的句柄
 * 以参数交进来，收尾直读 [ptr]。
 *
 * ## 归属线程
 *
 * 「我此刻在不在归属线程上」由 [ThreadDispatcher.isOnCurrentThread] 回答，两扇门都先问它。
 * 不能像 `withContext` 那样只看协程上下文：那条只说明「上下文里挂着同一个 dispatcher」，
 * 不说明「此刻真的在那条线程上」，而 [withPtrSync] 更是根本没有上下文可看。
 *
 * 归属线程由平台层记：`CoroutineDispatcher` **没有反查自己线程的接口**，而底层那条执行器
 * 是不可改写的黑盒、连 `isDispatchNeeded` 都恒为 `true`，没地方挂这份快照。
 *
 * 就算快照失准（唯一情形是「在归属线程上建自己」），后果**只是多一次投递**，不影响正确性 ——
 * 所以这里不设「跨线程读」的校验门。
 *
 * `dispatcher` 为 `null` 表示「本类不承诺线程归属」：[submit] 就地执行、[withPtr] 直接读、
 * [withPtrSync] 不做线程判断。那些还没注入 dispatcher 的子类即此类，行为与改造前一致。
 *
 * ## 关闭是「先标记、后释放」的异步动作
 *
 * [close] **不等释放完成**：它只把释放动作投进队列就返回。不等返回不会导致 use-after-free：
 * 同一个单线程 dispatcher 是 **FIFO** 的，释放消息一定排在「它之前投递的所有操作」之后。
 * 需要确定性的场合（例如关门前清算泄漏清单）改用 [closeDeferred] 拿 [Deferred] 去 await。
 *
 * `closeDispatcherOnClose` 为真时，**归还落地之后**才关掉那条独占的 dispatcher —— 关早了会把
 * [releaseImpl] 一起拒在门外（那条线程的回收本身也是「一次归还」）。
 *
 * ## [releaseImpl] 为什么是 abstract
 *
 * 宁可吵，不要静默：**没有默认实现** —— 子类不写就编译不过，而不是继承一个空实现、
 * 让 `close()` 假装成功地把 native 资源留在那儿。入口由基类收口，子类只回答「怎么还」。
 *
 * 两类「还不了」的资源各有出路：**要等线程**的（释放必须回到创建它的那条 native 线程上 ——
 * 归属 dispatcher 已经投过去了）照常实现；**只是借用**的（指针归别人所有）就**不要继承本类**
 * （见 [soko.ekibun.ffmpeg.AvStream]），免得被 `use {}` 之类在末尾把别人的资源关掉。
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class Pointer protected constructor(
  /**
   * native 句柄。**能在构造参数里交句柄的子类都走这里**（绝大多数）—— 把句柄当本构造
   * 函数的第一个实参传进来即可，什么都不用覆写。
   *
   * 留 `null`（默认）表示「本类自己拿不到句柄，得问覆写的 [initPtr] 要」—— 只有
   * [soko.ekibun.quickjs.QuickJS] 是这种：它要拿 `this` 去 `initContext` 换，而 `this`
   * 在 `super(...)` 的实参里还不可引用。
   *
   * 别拿 `0` 表示「没交」：`0` 是**合法的空句柄**（借用型的空壳就这么传），哨兵会把
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
   * 传 `true` 的前提是「这条 dispatcher 是本对象自己建的、没有别人用」—— 基类查不了
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
        val who = simpleClassName
        assert(dispatcher == null || dispatcher.isOnCurrentThread()) {
          "$who.initPtr() must run on the dispatcher thread"
        }
        initPtr().also { check(it != 0L) { "$who.initPtr() returned 0" } }
      }
    }

  /**
   * 本类的简单名，诊断消息用。
   *
   * 取名走 `this::class.simpleName` —— `KClass` 是 stdlib 的 **common** 声明，所以它不引入
   * 平台符号。（`javaClass` / `::class.java` 才需要 JVM，故不用那两个。）
   *
   * `open` 是为了给需要改名或补上下文的子类留口子（`Pointer` 的子类不少，各包的诊断前缀
   * 不同）；没有子类覆写时它就是个纯 getter。
   *
   * `simpleName` 是 `String?`（匿名对象没有名字）—— 而本工程的诊断点全在有名字的类上，
   * 所以拿不到名字时退回基类名，而不是在消息里印一个 `null`。
   */
  internal open val simpleClassName: String get() = this::class.simpleName ?: "Pointer"

  /**
   * 裸读带**同线程断言**（走 JVM `-ea`：测试任务默认开、desktopApp 的 run / hotRun 由
   * 构建里的 `jvmArgs("-ea:soko.ekibun...")` 开、生产打包的启动器不开 ⇒ 断言关闭，每次
   * 读只剩一次静态标志判断）。跨线程读 ptr 是 use-after-free 类错误，让它**在测试期就炸**
   * 而不是偶发崩在 native 里。
   */
  val ptr: Long
    get() {
      val d = dispatcher
      val who = simpleClassName
      assert(d == null || d.isOnCurrentThread()) { "direct call $who.ptr off the dispatcher thread" }
      return ptrLazy.value
    }

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
    throw IllegalStateException("$simpleClassName 既没在构造参数里交出句柄，也没覆写 Pointer.initPtr()")

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
   * `internal` 而不是 `protected`：[soko.ekibun.quickjs.QuickJS] 的调用点遍布
   * native 回调链与 façade 内部，`protected` 到不了那些地方。
   *
   * 它**先查 [isClosed]**（标记即抛）：关闭是「先标记、后释放」，标记了就当作已经
   * 删除。归还动作跑在标记窗口里，所以**不走这扇门** —— [releaseImpl] 的句柄以参数
   * 交进来，收尾直读 [ptr]。
   */
  internal suspend fun <T> withPtr(block: suspend (Long) -> T): T {
    if (isClosed) throw IllegalStateException("$simpleClassName is closed")
    val d = dispatcher ?: return block(ptr)
    if (d.isOnCurrentThread()) return block(ptr)
    return withContext(d) { block(ptr) }
  }

  /**
   * [withPtr] 的**同步版** —— 给没有协程上下文的入口用（JNI 直接进来的回调链、
   * `AutoCloseable.close()` 这类非挂起签名）。
   *
   * - 已在归属线程上（问 [ThreadDispatcher.isOnCurrentThread]）：**就地执行**，零派发；
   * - 否则 `runBlocking` 投到归属 dispatcher 上跑完再返回；
   * - `dispatcher` 为 `null`：就地执行、不判断线程（本类不承诺线程归属）。
   *
   * 它会在**调用线程上阻塞**等归属 dispatcher 空出来，所以在归属线程自己身上调它
   * 最划算（就地）。同理，别在「归属线程正等着你返回」的场合调它。
   * 与 [withPtr] 一样**先查 [isClosed]**（标记即抛）。
   */
  internal fun <T> withPtrSync(block: (Long) -> T): T {
    if (isClosed) throw IllegalStateException("$simpleClassName is closed")
    val d = dispatcher ?: return block(ptr)
    if (d.isOnCurrentThread()) return block(ptr)
    return runBlocking(d) { block(ptr) }
  }

  /**
   * 把动作**投递**到归属 dispatcher。
   *
   * 返回的 [Job] 完成时动作已落地；不 `join()` 就是「发消息、不等结果」。
   * `dispatcher` 为 `null` 时就地跑完再返回 —— 此时 `block` 抛出的异常直接传给调用方，
   * 不会被收进 [Job]。
   *
   * `internal` 而不是 `protected`，理由与 [withPtr] 相同：[soko.ekibun.quickjs.QuickJS]
   * 的内部调用点也要用它，`protected` 到不了那些地方。别自己抄一份
   * `CoroutineScope(dispatcher).launch { }` —— 投递语义（`dispatcher` 为 `null` 时就地跑完
   * 再返回）只在这里维护一份。
   *
   * 兜底那条 `CompletableDeferred(value = …)` 必须写**命名实参**：省掉它就成了
   * `CompletableDeferred(Unit)`（字面量式重载），一旦泛型被实例化成别的类型，就会挑中
   * `CompletableDeferred(parent: Job?)`，造出一个**永不完成**的 Deferred 而 join 挂死
   * —— 同一个坑已在 QuickJS 的关闭流程里踩过一次。
   */
  internal fun submit(block: suspend () -> Unit): Job =
    dispatcher?.let { CoroutineScope(it).launch { block() } }
      ?: CompletableDeferred(value = runBlocking { block() })

  private val closedFlag = AtomicBoolean(false)

  /**
   * 是否已经[关闭][close]。
   *
   * 关闭是**先标记**的：标记了就当作已经删除，哪怕释放动作还排在队列里没跑。
   * [close] 可能从任何线程发起，所以底层是原子的。
   */
  val isClosed: Boolean get() = closedFlag.load()

  /** 抢占「关闭」这唯一的一个名额。抢到返回 `true`，已经关过返回 `false`。 */
  protected fun markClosed(): Boolean = closedFlag.compareAndSet(false, true)

  /**
   * [close] 的可等待版本：[Job] 完成时 [releaseImpl] 已经跑完。
   *
   * 三种情形：
   * - **已经关过**（[markClosed] 没抢到名额）：返回一个已完成的 [Job]，什么也不做；
   * - **句柄从没兑现过**、而且它本来要靠 [initPtr] 现算：说明 native 资源**压根没建**，
   *   于是不为了「还」去把它建出来 —— 但**照样先标记**（[isClosed] 立刻为真），否则关闭
   *   之后的新操作会白白建出一个没人释放的上下文；
   * - 其余：投递 [releaseImpl]，句柄以参数交给它。
   *
   * `nativePtr == null` 这半个判据不能省：句柄是**构造参数交进来**的子类（[soko.ekibun.quickjs.JSRef]
   * / [soko.ekibun.ffmpeg.AvFrame]）手上**已经有**资源了，`ptr` 读没读过都欠着一次归还。
   *
   * `closeDispatcherOnClose` 为真时，**这条 [Job] 完成之后**才关那条独占的 dispatcher。
   * 挂在这里而不是 [close] 里，是为了让两条路都覆盖到：「发完不管」的 [close]，以及
   * `FFPlayer.closeAsync()` 那种 `join()` 的。短路分支返回的是**已完成**的 Job，挂在它
   * 上面的关闭动作当场执行 —— 这正是「句柄都没建过也得收线程」的场合（线程在建
   * [ThreadDispatcher] 时就起了）。
   */
  fun closeDeferred(): Job {
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
   * 与之相对，**投递本身**被拒（dispatcher 已经关了）时异常是**同步**抛出的，走不到
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
   * 它跑在 [markClosed] **之后** —— 所以归还**不经过**读句柄那两扇门（它们查
   * [isClosed]、标记即抛），句柄以参数交进来；收尾还需要句柄的（如 `releaseRef` 类）
   * 直读 [ptr]，否则归还自己就被挡在门外，`close()` 会「返回成功、其实什么都没释放」。
   *
   * 没有默认实现是有意的：`abstract` 让「忘了写归还」在**编译期**就吵出来（见类文档）。
   */
  abstract suspend fun releaseImpl(ptr: Long)
}
