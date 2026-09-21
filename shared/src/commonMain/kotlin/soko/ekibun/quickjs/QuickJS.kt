package soko.ekibun.quickjs

import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.jniLoadLibrary
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个独立的 QuickJS runtime。
 *
 * **本类直接继承 [Pointer]**：句柄要拿 `this` 去 `initContext` 换，而换的动作只能在
 * super 之后做 —— super 实参求值时 `this` 还不完整（Kotlin 与 Java 都禁止在 super 实参里
 * 引用 `this`）。解法是**基类第一个实参留 `null`、覆写 [Pointer.initPtr]**：基类把这次
 * 调用推迟到**首次读句柄**那一刻，那时本类的构造参数与字段全都就位、而且读发生在归属
 * 线程上，于是不必设中转字段，直接在覆写体里
 * `initContext(this, stackSize, memoryLimit, timeout)`。
 *
 * 本类因此是**唯一**不往基类构造参数里交句柄的子类 —— 其余子类（ffmpeg 那批、[JSRef]）
 * 都写成 `Pointer(nativePtr, dispatcher)`，一个成员都不用覆写。
 *
 * 构造入口就是**主构造器**：`QuickJS(…)`。**句柄的建立时机与线程由 [Pointer] 收口**
 * （首次读句柄时才建、且总在归属线程上），所以构造本身发生在哪条线程上都不影响
 * 「句柄必须建在归属线程上」这条 —— 详见 [initPtr]。
 *
 * @param moduleHandler 模块加载器，返回 null 表示模块不存在
 * @param stackSize JS 层最大栈空间（字节），<=0 用默认 256KB。必须显著小于
 *   宿主线程栈（JVM 默认约 1MB），否则深递归会撞穿宿主栈导致整个进程崩溃
 * @param memoryLimit 堆内存上限（字节），<=0 表示不限制
 * @param timeout 单次 evaluate/executePendingJob 的超时（毫秒），<=0 表示不限制
 */
class QuickJS(
  val moduleHandler: ((String) -> String?)? = null,
  val stackSize: Long = 256 * 1024,
  val memoryLimit: Long = -1,
  val timeout: Long = -1,
  /**
   * 归属线程的执行器：所有 native 调用都投递到这里串行执行，因此多个 [Pointer]
   * 完全可以共用同一条（ffmpeg 侧的打算）。默认就是共享：[sharedDispatcher]。
   *
   * 它同时是**构造参数属性**：`super` 实参里读到的是**参数**，而字段要到 super 返回
   * 之后才赋值 —— 实参位置上解析到的正是参数本身，所以两种身份都成立。
   */
  val dispatcher: ThreadDispatcher = sharedDispatcher,
) : Pointer(dispatcher = dispatcher) {
  companion object {
    /**
     * [dispatcher] 的默认值：**全进程共享一条线程**，线程名 `quickjs`。
     *
     * 共享的代价：所有 runtime 在这条线程上**串行** —— 一个 runtime 跑长任务（或卡在
     * [timeout] 边界上）会拖住别的 runtime 的操作。要独占就显式传一条
     * `ThreadDispatcher("quickjs-xxx")`（2026-09-21 订正：原先这里写着「`create()` 没有传入自己
     * dispatcher 的入口、照做做不出来」—— 挂起工厂已删，主构造器上那个参数真的能传了）。
     *
     * 构造本身**不切线程**（就是一次 `new`），所以在 JS 回调里再建一个 runtime **不会**
     * 死锁；但新 runtime 的操作会和外面那些一起排在这条单线程上 —— 在回调里**等**它的
     * 结果就是自己等自己。
     *
     * ⚠️ 它**从不 shutdown**：共享的东西没有哪一方有权关掉它，所以这条线程活到进程结束。
     * 反过来的好处是反复建 runtime 不再攒线程（改造前是每个 runtime 一条、同样不关）。
     */
    val sharedDispatcher = ThreadDispatcher("quickjs")

    init {
      jniLoadLibrary("quickjs")
    }

    @JvmStatic
    private external fun initContext(
      ctx: QuickJS,
      stackSize: Long,
      memoryLimit: Long,
      timeout: Long,
    ): Long

    @JvmStatic
    private external fun destroyContext(ctx: Long)

    @JvmStatic
    private external fun jsNewError(ctx: Long): Long

    @JvmStatic
    private external fun jsNewString(
      ctx: Long,
      obj: String,
    ): Long

    @JvmStatic
    private external fun jsNewBool(
      ctx: Long,
      obj: Boolean,
    ): Long

    @JvmStatic
    private external fun jsNewInt64(
      ctx: Long,
      obj: Long,
    ): Long

    @JvmStatic
    private external fun jsNewFloat64(
      ctx: Long,
      obj: Double,
    ): Long

    @JvmStatic
    private external fun jsNewArrayBuffer(
      ctx: Long,
      obj: ByteArray,
    ): Long

    @JvmStatic
    private external fun jsNewObject(ctx: Long): Long

    @JvmStatic
    private external fun jsNewArray(ctx: Long): Long

    @JvmStatic
    private external fun jsNULL(): Long

    @JvmStatic
    private external fun definePropertyValue(
      ctx: Long,
      obj: Long,
      k: Long,
      v: Long,
    ): Int

    @JvmStatic
    private external fun jsDupValue(
      ctx: Long,
      obj: Long,
    ): Long

    @JvmStatic
    private external fun jsNewCFunction(
      ctx: Long,
      obj: Long,
    ): Long

    @JvmStatic
    private external fun jsWrapObject(
      ctx: Long,
      obj: Any,
    ): Long

    @JvmStatic
    private external fun isException(obj: Long): Boolean

    /** 只归还一个 JS 引用（对应 native 的 `JS_FreeValue`），不销毁包装。 */
    @JvmStatic
    private external fun jsReleaseValue(
      ctx: Long,
      obj: Long,
    )

    /** 只销毁堆上的包装（对应 native 的 `delete`）。调用前必须先 [jsReleaseValue]。 */
    @JvmStatic
    private external fun jsDestroyHandle(obj: Long)

    @JvmStatic
    private external fun evaluate(
      ctx: Long,
      cmd: String,
      name: String,
      flag: Int,
    ): Long

    @JvmStatic
    private external fun getException(ctx: Long): JSError

    @JvmStatic
    private external fun jsThrowError(
      ctx: Long,
      err: Long,
    ): Long

    @JvmStatic
    private external fun jsToJava(
      ctx: Long,
      obj: Long,
    ): Any?

    @JvmStatic
    private external fun executePendingJob(ctx: Long): Int

    @JvmStatic
    private external fun jsCall(
      ctx: Long,
      obj: Long,
      thisVal: Long,
      argc: Int,
      argv: LongArray,
    ): Long

    @JvmStatic
    private external fun jsNewPromise(ctx: Long): LongArray
  }

  /**
   * 所有仍持有 native 引用的对象，**强引用**登记。
   *
   * 用身份登记表而不是原来的 `WeakHashMap<Long, JSRef>`：
   * - 原来是「native 指针 → 包装」，指针一复用就会覆盖条目，且条目会被 GC 静默
   *   清掉 —— 于是关闭时遍历 `ref` 根本找不到漏掉的对象，只能让 C 层断言 abort。
   * - 现在按对象身份登记，`close()` 时能确定性地逐个归还，还能枚举出残留者。
   *
   * 这里刻意用强引用（与 flutter_qjs 的 `_RuntimeOpaque._ref` 一致）：只有强引用才能
   * 保证「还没 close 的对象一定还在册」，从而让清算**确定发生**而不是听天由命等 GC。
   * 这也是本类不设 GC 兜底清扫的底气 —— 见 [releaseImpl]。
   */
  private val refs = Collections.newSetFromMap(IdentityHashMap<JSRef, Boolean>())

  internal fun register(value: JSRef) {
    refs.add(value)
  }

  internal fun unregister(value: JSRef) {
    refs.remove(value)
  }

  private val updateChannel = Channel<Unit>()

  /** runtime 是否还在：销毁之后为 `false`。[onJsThreadQuietly] 靠它决定还能不能归还。 */
  private val runtimeAlive = AtomicBoolean(true)

  /**
   * 算出 runtime + context —— 覆写基类的句柄来源。
   *
   * ⚠️ 基类保证它**不在构造期调**、且`只调一次`（结果存进 `Pointer.ptr`）：它在**首次读
   * 句柄**那一刻才跑，那时 [moduleHandler] / [stackSize] / [memoryLimit] / [timeout] 都
   * 已经有值。别把它挪回构造期 —— 基类构造期本类的字段全是 `0`（实测 `putfield` 排在
   * `invokespecial <init>` **之后**，而且**编译器不报错**），而 `timeout = 0` 等于关掉死
   * 循环中断（native 侧 `JS_SetInterruptHandler` 里 `timeoutMs <= 0` 直接放行），表现就是
   * 「测试跑着跑着不动了」。钉这一点的用例：`PointerTest.initPtrSeesConstructionState`。
   *
   * 首次读发生在 [Pointer.withPtr] / [Pointer.withPtrSync] 的**块里**，也就是**投递之
   * 后**，所以本函数跑在归属线程上 —— `JS_NewRuntime()` 与
   * `JS_SetMaxStackSize()` 据此把**调用线程的帧地址**记成 `stack_top`，这条依赖不能丢
   * （见 [Pointer] 类文档「读指针」一节）。
   *
   * 建不起来（返回 `0`）由基类 `check` 出来，这里不用自己判。句柄**不会**在销毁后变成
   * `0`（[Pointer] 的指针不可更改），所以「runtime 还活着吗」要看 [runtimeAlive]，别拿
   * `ptr == 0L` 去猜。
   */
  override fun initPtr(): Long = initContext(this@QuickJS, stackSize, memoryLimit, timeout)

  /**
   * 真正销毁 runtime —— 覆写基类的归还钩子，[Pointer] 保证**只跑一次**。
   *
   * 句柄由基类**以参数交进来**（[Pointer.releaseImpl] 的 `ptr`），不必自己去读；跑在归属
   * 线程上（基类 [Pointer.submit] 投递过来的）。**不自己设门**：唯一入口 [closeAndCollect]
   * 已经用基类的
   * [Pointer.markClosed] 抢过名额，这里再抢必然失败 —— 那会变成「返回成功、其实没
   * 销毁」，runtime 连同它整个堆漏在 native。
   *
   * [runtimeAlive] 先落：它回答的是「runtime 还在不在」，与「关闭是否已标记」
   * （[isClosed]）**不是一回事** —— 关闭时的清算（[collectLeaks]）必须在标记之后、
   * 销毁之前照常归还引用，否则残留会撑到 `JS_FreeRuntime` 去触发 `gc_obj_list`
   * 断言、整个进程 abort。
   *
   * 本类**不设 GC 兜底**（曾尝试 `java.lang.ref.Cleaner`，已移除），两条理由：
   * - Android 上 `java.lang.ref.Cleaner` 是 **API 33** 才有的类，而本工程
   *   `minSdk = 24` 且没开 core library desugaring —— 低版本上是 `NoClassDefFoundError`，
   *   而它挂在实例字段上，构造 QuickJS 就会炸；
   * - 清理动作若持有被登记对象，该对象就永远可达、清扫器永不触发。`QuickJS` 的清理动作
   *   里写了成员调用，Kotlin 于是把 `this` 带进了捕获表（javap 实证），那层兜底在 JVM
   *   上其实从未生效过。
   *
   * 于是显式 [close] 是唯一销毁路径，[Pointer]（即 [AutoCloseable]）只是给它补上
   * `use {}` 这类语法契约。漏掉的引用由 [refs] 在关闭时清算并报告，想把它变成可断言的
   * 事实用 [closeAndCheckLeaks]。
   */
  override suspend fun releaseImpl(ptr: Long) {
    runtimeAlive.set(false)
    destroyContext(ptr)
  }

  init {
    // 句柄是惰性的（首次读时才建，见 [initPtr]），这里只起 pump。投递即返回、不等它。
    submit {
      for (v in updateChannel) {
        // 销毁消息可能已经排在前面：runtime 没了就别再去 executePendingJob
        if (!runtimeAlive.get()) break
        // 本协程就跑在归属线程上，withPtrSync 于是就地执行（零派发）
        withPtrSync { ptr ->
          while (true) {
            val err: Int = executePendingJob(ptr)
            if (err <= 0) {
              if (err < 0) print(getException(ptr))
              break
            }
          }
        }
      }
    }
  }

  private fun javaToJs(obj: Any?): Long = javaToJsImpl(obj)

  /**
   * 把 native 句柄转成 Java 值，供 [JSFunction] 调用。
   *
   * native 入口 `jsToJava` 是 `QuickJS` 的私有成员，只有它自己的成员够得着。
   * [JSFunction] 是**顶层**类、只是 [JSRef] 的子类 —— Kotlin 的 `private` 不跨继承
   * 传递，所以这里开一扇 `internal` 的门，而不是把 `jsToJava` 本身放出去。
   */
  internal fun toJava(handle: Long): Any? = withPtrSync { ptr -> jsToJava(ptr, handle) }

  @Keep
  private fun wrapJSPromiseAsync(
    obj: Long,
    then: JSFunction?,
  ): Deferred<Any?> {
    val ret = CompletableDeferred<Any?>()
    // 两个包装都是 native 交出来的新引用，由这里负责归还：
    // - `then` 是 native 为 "then" 属性**独占**造的 JSFunction。独占是常态：
    //   函数不登记进转换用的 cache（对齐 flutter_qjs 的 `_jsToDart` ——
    //   它的函数分支不写 `cache[valptr]`，只有数组/普通对象写）。所以同一个
    //   `Promise.prototype.then` 被数组里每个 promise 取到时，各自拿到一个新包装，
    //   这里 `close()` 只影响自己那一个。
    // - `thisVal` 是本函数第一个参数携带的 promise 自身（JS_DupValue 过的）
    // 漏掉任何一个，它就会一直挂在 refs 上，让关闭时的清算抛泄漏。
    //
    // `then` 可空：Kotlin 这边按 `JSFunction` 声明，但 native 传过来的是
    // `Object`，若该 Promise 的 "then" 属性不是函数（或转换失败）就是 null。
    // 以前直接 `then.close()` 会 NPE；用可空接收 + 安全调用堵住。
    val thisVal = JSRef(obj, this)
    try {
      jsCallImpl(
        then ?: run {
          ret.completeExceptionally(JSError("promise has no callable `then`"))
          return ret
        },
        object : JSInvokable {
          override fun invoke(
            vararg argv: Any?,
            thisVal: Any?,
          ) {
            ret.complete(argv[0])
          }
        },
        object : JSInvokable {
          override fun invoke(
            vararg argv: Any?,
            thisVal: Any?,
          ) {
            ret.completeExceptionally(argv[0] as? Throwable ?: JSError(argv.toString()))
          }
        },
        thisVal = thisVal,
      ).let { releaseValue(it) }
    } finally {
      thisVal.close()
      then?.close()
    }
    return ret
  }

  @Keep
  private fun loadModule(name: String): String? =
    try {
      moduleHandler?.invoke(name)
    } catch (e: Throwable) {
      e.printStackTrace()
      null
    }

  @Keep
  private fun handleJSInvokable(
    obj: JSInvokable,
    argv: Array<Any>,
    thisVal: Any?,
  ): Long =
    try {
      javaToJs(obj.invoke(*argv, thisVal = thisVal))
    } catch (e: Throwable) {
      e.printStackTrace()
      withPtrSync { ptr -> jsThrowError(ptr, javaToJs(e)) }
    }

  /**
   * **新操作**的入口（旧 `runOnDispatcher`）：先查 [isClosed] 再在归属线程上跑。
   *
   * 归还侧**不能**走这扇门，它得用 [onJsThreadQuietly] —— 见那边的注释。
   */
  private fun <T> runOnJsThread(block: (Long) -> T): T {
    if (isClosed) throw IllegalStateException("QuickJS context is closed")
    return withPtrSync(block)
  }

  internal fun jsCallImpl(
    obj: JSRef,
    vararg argv: Any?,
    thisVal: Any? = null,
  ): Long =
    runOnJsThread { ptr ->
      // javaToJs 每次返回的都是「新引用」的裸句柄（新建 或 jsDupValue），
      // 这里用 try/finally 保证归还：中途抛异常也不能漏。
      val argvJs = argv.map { javaToJs(it) }.toLongArray()
      val thisJs = javaToJs(thisVal)
      try {
        val ret = jsCall(ptr, obj.withPtrSync { it }, thisJs, argvJs.size, argvJs)
        updateChannel.trySend(Unit)
        if (isException(ret)) {
          throw getException(ptr)
        }
        ret
      } finally {
        releaseValue(thisJs)
        argvJs.forEach { releaseValue(it) }
      }
    }

  /**
   * 归还一个裸句柄持有的 JS 引用，并销毁包装。
   *
   * 这是唯一的释放出口 —— 对应 native 侧 `jsReleaseValue` + `jsDestroyHandle`
   * 两步。`javaToJsImpl` / `evaluate` / `jsNewPromise` 等都从这里返回句柄，
   * 所以调用点只需关心「用完了就还」，不必各自区分两种释放语义。
   *
   * 调用方可以是任意线程：这两步都要碰 runtime，本方法自己把它们投递到 JS 线程。
   */
  internal fun releaseValue(handle: Long) {
    if (handle == 0L) return
    withPtrSync { ptr ->
      jsReleaseValue(ptr, handle)
      jsDestroyHandle(handle)
    }
  }

  /**
   * 归还一个 [JSRef]：撤销登记 + 还掉它那一票。
   *
   * ⚠️ 还的是 **ref 自己的值句柄**（`ref.withPtrSync { it }` 读到的那个），**不是**块参数
   * 里那个 runtime 句柄 —— 两个都是 `Long`，混用就是拿 runtime 指针对去 `jsReleaseValue`，
   * 当场踩坏 native 堆（实测表现是测试进程 `0xC0000374` heap corruption 直接死掉）。
   */
  internal fun releaseRef(ref: JSRef) {
    withPtrSync {
      unregister(ref)
      // 已经在 JS 线程上，releaseValue 里面那次投递会就地执行，不产生第二次派发
      releaseValue(ref.withPtrSync { it })
    }
  }

  /**
   * Java 值 → JS 句柄。
   *
   * `cache` 是**按对象身份**的访问表，用来切断循环引用：`a["a"] = a` 这种图若不做
   * 记忆，转换会在 `a` 上无限递归。必须用 [IdentityHashMap] 而不是普通 `HashMap`：
   * - 语义上这里要的是「同一个对象实例」，不是「内容相等的对象」；
   * - 更要命的是普通 `HashMap` 会对**键**调用 `hashCode()`/`equals()`，而循环引用的
   *   对象（自引用 Map）在算哈希时就会无限递归，直接 `StackOverflowError`。
   *
   * 这与 [jsToJava] 那边的 `cache` 是同一个思路（按 native 指针记忆）。
   */
  fun javaToJsImpl(
    obj: Any?,
    cache: MutableMap<Any, Long> = IdentityHashMap<Any, Long>(),
  ): Long =
    withPtrSync { ptr ->
      if (obj == null || obj is Unit) return@withPtrSync jsNULL()
      if (obj is Throwable) {
        val ret = jsNewError(ptr)
        // definePropertyValue 完整接管 k/v 两个句柄（归还引用 + 销毁包装），
        // 所以这里传进去的 jsNewString 结果不需要、也不能再被引用。
        // 每个属性用一对独立的 jsNewString：句柄是一次性的，不能复用。
        definePropertyValue(
          ptr,
          ret,
          jsNewString(ptr, "name"),
          jsNewString(ptr, obj.javaClass.name),
        )
        definePropertyValue(
          ptr,
          ret,
          jsNewString(ptr, "message"),
          jsNewString(ptr, obj.message ?: ""),
        )
        definePropertyValue(
          ptr,
          ret,
          jsNewString(ptr, "stack"),
          jsNewString(ptr, obj.stackTraceToString()),
        )
        return@withPtrSync ret
      }
      if (obj is JSRef) {
        return@withPtrSync jsDupValue(ptr, obj.withPtrSync { it })
      }
      if (obj is Deferred<Any?>) {
        val (ret, jsRes, jsRej) = jsNewPromise(ptr)
        // jsToJava 在 native 侧就把句柄的引用消费掉了，所以 jsRes/jsRej 不需要
        // 单独归还 —— 它们的所有权已经转移给新建的 JSFunction。
        val resolve = jsToJava(ptr, jsRes) as JSFunction
        val reject = jsToJava(ptr, jsRej) as JSFunction
        @Suppress("DeferredResultUnused")
        submit {
          try {
            resolve.invoke(obj.await())
          } catch (e: Throwable) {
            reject.invoke(e)
          } finally {
            // 两个包装各持一票，用完归还
            resolve.close()
            reject.close()
          }
        }
        return@withPtrSync ret
      }
      if (obj is Boolean) return@withPtrSync jsNewBool(ptr, obj)
      if (obj is Byte) return@withPtrSync jsNewInt64(ptr, obj.toLong())
      if (obj is Char) return@withPtrSync jsNewInt64(ptr, obj.code.toLong())
      if (obj is Short) return@withPtrSync jsNewInt64(ptr, obj.toLong())
      if (obj is Int) return@withPtrSync jsNewInt64(ptr, obj.toLong())
      if (obj is Long) return@withPtrSync jsNewInt64(ptr, obj)
      if (obj is Number) return@withPtrSync jsNewFloat64(ptr, obj.toDouble())
      if (obj is String) return@withPtrSync jsNewString(ptr, obj)
      if (obj is ByteArray) {
        return@withPtrSync jsNewArrayBuffer(ptr, obj)
      }
      cache[obj]?.let {
        return@withPtrSync jsDupValue(ptr, it)
      }
      if (obj is Map<*, *>) {
        val ret = jsNewObject(ptr)
        cache[obj] = ret
        obj.forEach { entry ->
          definePropertyValue(
            ptr,
            ret,
            javaToJsImpl(entry.key, cache),
            javaToJsImpl(entry.value, cache),
          )
        }
        return@withPtrSync ret
      }
      val arrayObj = if (obj is Array<*>) obj.toList() else obj
      if (arrayObj is Iterable<*>) {
        val ret = jsNewArray(ptr)
        cache[obj] = ret
        arrayObj.forEachIndexed { i, v ->
          definePropertyValue(
            ptr,
            ret,
            jsNewInt64(ptr, i.toLong()),
            javaToJsImpl(v, cache),
          )
        }
        return@withPtrSync ret
      }
      val ret = jsWrapObject(ptr, obj)
      return@withPtrSync if (obj is JSInvokable) {
        val func = jsNewCFunction(ptr, ret)
        // jsNewCFunction 已经把 ret 包进 C function 的 func_data（内部持有），
        // 这里只需归还我们手上这一票。
        releaseValue(ret)
        func
      } else {
        ret
      }
    }

  /**
   * 求值并转换结果。
   *
   * **挂起**而不是阻塞：[Pointer.withPtr] 把整段求值搬到归属线程（[dispatcher]）上跑，
   * 并顺手把 runtime 句柄压进块里 —— 调用方线程被让出去等结果。对照
   * [Pointer.withPtrSync] 那条 `runBlocking` 的同步路径，后者是给 native 回调链准备的
   * （`@Keep` 的函数由 native 线程直接调进来，那里没有协程上下文）。
   *
   * `evaluate` native 返回的句柄由 [jsToJava] 消费掉（它在 native 侧就会调
   * `jsReleaseValue`），所以这里不需要额外归还。
   */
  suspend fun evaluate(
    cmd: String,
    name: String = "<eval>",
    flag: Int = JSEvalFlag.GLOBAL,
  ): Any? {
    // 标记即拒绝：晚于关闭消息投进来的求值会排在销毁之后，那是 use-after-free
    if (isClosed) throw IllegalStateException("QuickJS context is closed")
    return withPtr { ptr ->
      val ret = evaluate(ptr, cmd, name, flag)
      updateChannel.trySend(Unit)
      if (isException(ret)) {
        throw getException(ptr)
      }
      jsToJava(ptr, ret)
    }
  }

  /**
   * 关闭并把泄漏清单带回来。
   *
   * 与 [close] 的区别是**它给你一个 [Deferred]**：`await()` 之后才拿得到清算结果。
   * [close] 只投递、不等结果，所以拿不到清单 —— 要断言「没有泄漏」就得走这里。
   * 空列表 = 没有泄漏；`null` = 本次调用**没抢到关闭名额**（已经关过了），因此没跑清算。
   */
  fun closeAndCollect(): Deferred<List<String>?> =
    if (!markClosed()) {
      // ⚠️ 别写成 `CompletableDeferred(null)`：那个字面量会被重载解析挑到
      // `CompletableDeferred(parent: Job? = null)` 上去，造出一个**永远不完成**的
      // Deferred —— `await()` 于是永久挂起（`closeIsIdempotentAcrossEntryPoints`
      // 第二次关闭卡死就是这么来的）。带上类型实参 + 命名实参才落到 `value: T` 那个重载。
      CompletableDeferred<List<String>?>(value = null)
    } else {
      // 名额在上一行就抢了（基类那份），这里只管投递 —— 基类保证 [releaseImpl] 只跑一次
      submit {
        updateChannel.close()
        // 先清算再销毁：销毁之后 onJsThreadQuietly 会因为 runtimeAlive 为 false
        // 拒绝工作，那一轮归还就全变成空操作，残留反而撑到 JS_FreeRuntime 去 abort。
        val leaked = collectLeaks()
        withPtr { ptr -> releaseImpl(ptr) }
        leaked
      }
    }

  /**
   * 主动销毁 runtime；重复调用是安全的。
   *
   * 关闭是**异步**的：只把「清算 + 销毁」投递到 JS 线程就返回，同时
   * [isClosed] 立即置位 —— 标记了就当作已删除，之后的新操作会被
   * [runOnJsThread] / [evaluate] 拒绝。队列是 FIFO，所以投递之前发出的操作一定先跑完，
   * 之后发出的又一定排在销毁之后，不会 use-after-free。
   *
   * 泄漏报告只能打印在异步侧（[close] 不等结果，也就无法在这里抛）：
   * 想把它变成**可断言的失败**请用 [closeAndCheckLeaks]。
   */
  override fun close() {
    submit {
      val leaked = closeAndCollect().await()
      if (leaked?.isNotEmpty() == true) {
        System.err.println("QuickJS reference leak:\n" + leaked.joinToString("\n"))
      }
    }.invokeOnCompletion { e ->
      e?.printStackTrace()
    }
  }

  /**
   * 关闭前的强制清算：**先记录、再归还**。
   *
   * 移植自 flutter_qjs 的 `jsFreeRuntime`（`lib/src/ffi.dart`）。它同时服务两个目的，
   * 顺序不能颠倒：
   *
   * 1. **记录** —— 此刻仍在册的对象，说明调用方没有归还它。这就是「泄漏」的定义，
   *    必须在归还之前抓下来，否则归还之后就无迹可寻了。
   * 2. **归还** —— 逐个 [JSRef.release]，让 runtime 的
   *    `assert(list_empty(&rt->gc_obj_list))` 不会命中。这个断言一旦触发就是
   *    `abort()`，整个进程会死，测试连报告都发不出来。
   *
   * 换句话说：Leak 报告负责「暴露问题」，归还负责「不让它升级成崩溃」。
   * 两者都要，且缺一不可。
   */
  internal suspend fun collectLeaks(): List<String> {
    val snapshot = refs.toList()
    // 先记录：还在册 = 调用方漏了归还
    val leaked = snapshot.map { "  ${it.describe()}" }
    // 再归还：清空登记册，让 runtime 可以安全销毁
    snapshot.forEach { it.closeDeferred().await() }
    refs.clear()
    return leaked
  }

  /**
   * 关闭并断言没有引用泄漏。
   *
   * 与 [close] 的区别是**泄漏会抛异常**，而且会 `await()` 到清算真正跑完。
   * 测试用它来把「有没有漏归还」变成可断言的事实（对照 flutter_qjs 的
   * `test('reference leak')`），而不是等进程崩掉。
   */
  suspend fun closeAndCheckLeaks() {
    val leaked = closeAndCollect().await()
    if (leaked?.isNotEmpty() == true) {
      throw JSError(
        "reference leak:\n    REFS\tTYPE\tPTR\n" + leaked.joinToString("\n"),
      )
    }
  }
}
