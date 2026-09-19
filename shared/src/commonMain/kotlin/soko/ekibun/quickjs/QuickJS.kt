package soko.ekibun.quickjs

import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import soko.ekibun.jniLoadLibrary
import java.lang.ref.Cleaner
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors

object QuickJS {
  init {
    jniLoadLibrary("quickjs")
  }

  @JvmStatic
  private external fun initContext(
    ctx: Context,
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
    flags: Int = JSProp.C_W_E,
  ): Int

  @JvmStatic
  private external fun getPropertyValue(
    ctx: Long,
    obj: Long,
    k: Long,
  ): Long

  @JvmStatic
  private external fun getObjectKeys(
    ctx: Long,
    obj: Long,
  ): Array<Any>

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

  /**
   * 一个独立的 QuickJS runtime。
   *
   * @param moduleHandler 模块加载器，返回 null 表示模块不存在
   * @param stackSize JS 层最大栈空间（字节），<=0 用默认 256KB。必须显著小于
   *   宿主线程栈（JVM 默认约 1MB），否则深递归会撞穿宿主栈导致整个进程崩溃
   * @param memoryLimit 堆内存上限（字节），<=0 表示不限制
   * @param timeout 单次 evaluate/executePendingJob 的超时（毫秒），<=0 表示不限制
   */
  class Context(
    val moduleHandler: ((String) -> String?)? = null,
    val stackSize: Long = 256 * 1024,
    val memoryLimit: Long = -1,
    val timeout: Long = -1,
  ) {
    /**
     * 一个 JS 值的持有者。
     *
     * 引用计数模型（移植自 flutter_qjs 的 `_JSObject`）：
     * - 构造即持有 **一票**（调用方通过 native 新建/dup 出来的引用）；
     * - [dup] 增票、[close] 减票，减到 0 才真正调 `jsReleaseValue` + `jsDestroyHandle`；
     * - [close] 之后 [ptr] 失效，任何访问都是 use-after-free。
     *
     * 之所以要显式计数：native 把「释放 JS 引用」和「销毁包装」拆成了两个操作，
     * 而包装可能被多个 JS 操作复用（每次配对一次 `jsDupValue`）。只靠 GC 时机
     * 归还会让 QuickJS 在 `JS_FreeRuntime` 时断言 `gc_obj_list` 非空并 abort。
     */
    open class JSValue internal constructor(
      val ptr: Long,
      protected val ctx: Context,
    ) : JSRef,
      JSRefLeakable,
      AutoCloseable {
      private var _refCount: Int = 1

      override val refCount: Int get() = _refCount

      @Volatile
      private var destroyed = false

      override val released: Boolean get() = destroyed

      init {
        @Suppress("LeakingThis")
        ctx.register(this)
      }

      override fun dup(): JSRef {
        if (destroyed) throw IllegalStateException("JSValue already released")
        _refCount++
        return this
      }

      /**
       * 释放一票。归零时销毁。
       *
       * 重复调用是安全的：[free] 与 [release] 都先看 `destroyed`，归零之后再调
       * 直接返回（**不抛异常**），不会重复释放 native 句柄 —— 这是此前
       * `delete` 与 `jsFreeValue` 混用导致双重释放的地方。
       * 会抛 `IllegalStateException` 的是 [dup]：已经还完了还想要一票，没得给。
       */
      override fun free() {
        if (destroyed) return
        _refCount--
        if (_refCount <= 0) release()
      }

      /** [free] 的别名，实现 [AutoCloseable] 以便 `use {}`。重复调用安全。 */
      override fun close() = free()

      override fun describe(): String = "${javaClass.simpleName}(refs=$refCount, ptr=$ptr)"

      /** 真正把引用还给 runtime。必须在 JS 线程上下文内调用。 */
      internal fun release() {
        if (destroyed) return
        destroyed = true
        ctx.unregister(this)
        // 同时撤掉「指针 → 包装」登记：指针可能被 QuickJS 复用，
        // 留着会让后续转换命中一个已经销毁的包装。
        ctx.forgetWrapper(this)
        ctx.releaseValue(ptr)
      }

      protected fun jsCall(
        vararg argv: Any?,
        thisVal: Any?,
      ): Any? = jsToJava(ctx.ptr, ctx.jsCallImpl(this, *argv, thisVal = thisVal))

      protected fun getPropertyValue(k: Any): Any? =
        ctx.runOnDispatcher {
          jsToJava(ctx.ptr, getPropertyValue(ctx.ptr, ptr, ctx.javaToJs(k)))
        }

      protected fun getObjectKeys(): Array<Any> =
        ctx.runOnDispatcher {
          getObjectKeys(ctx.ptr, ptr)
        }
    }

    /**
     * 所有仍持有 native 引用的对象，**强引用**登记。
     *
     * 用身份登记表而不是原来的 `WeakHashMap<Long, JSValue>`：
     * - 原来是「native 指针 → 包装」，指针一复用就会覆盖条目，且条目会被 GC 静默
     *   清掉 —— 于是关闭时遍历 `ref` 根本找不到漏掉的对象，只能让 C 层断言 abort。
     * - 现在按对象身份登记，`close()` 时能确定性地逐个归还，还能枚举出残留者。
     *
     * 这里刻意用强引用（与 flutter_qjs 的 `_RuntimeOpaque._ref` 一致）：只有强引用才能
     * 保证「还没 close 的对象一定还在册」，从而让清算**确定发生**而不是听天由命等 GC。
     * 代价是 [Cleaner] 不再作用于这些对象 —— 这不是损失，因为清算已经覆盖了它们；
     * [Cleaner] 负责的是下面 [cleaner] 注释里说明的另一种情况。
     */
    private val refs = Collections.newSetFromMap(IdentityHashMap<JSValue, Boolean>())

    /**
     * native 指针 → 包装对象的**持久**登记表。
     *
     * 与 [refs] 的分工：[refs] 按**对象身份**登记「谁还欠着引用」，用于关闭前清算；
     * 本表按 **native 指针**登记「这个 JS 对象已经有哪个包装」，用于保证
     * **同一个 JS 对象总是转换成同一个包装**。
     *
     * 为什么必须持久：native 侧 `jsToJava` 里那个 `cache` 是**每次调用新建的局部变量**，
     * 只能保证「一次转换遍历内」同一个对象映射到同一个包装。而 Kotlin 侧的属性访问
     * 每次都是一轮独立的 `jsToJava`（`obj["a"]` 两次就是两轮），所以 `a["a"] === a`
     * 这种**跨调用**的身份必须靠本表维持 —— 这正是 flutter_qjs 在 `_jsToDart` 里
     * `cache[valptr] = ret` 想要、但只在其「整图一次性转换」模型下才够用的语义。
     *
     * 用普通 [HashMap]（键是 Long，没有 hashCode 递归问题），不用身份表。
     */
    private val wrapperCache = HashMap<Long, Any>()

    /** 包装对象被归还时，同步撤掉登记，避免指针复用后映射到已销毁的对象。 */
    private fun forgetWrapper(value: JSValue) {
      wrapperCache.entries.removeIf { it.value === value }
    }

    // ── 下面三个方法仅供 native 侧（`quickjs.cpp` 的 `jsToJava`）回调 ──────────
    // 它们命名刻意保持简单，避免 native 侧签名写错。

    /** 取该指针已有的包装；没有则返回 null。 */
    @Keep
    private fun peekWrapper(ptr: Long): Any? = wrapperCache[ptr]

    /** 为新包装建立「指针 → 包装」映射。 */
    @Keep
    private fun registerWrapper(
      ptr: Long,
      wrapper: Any,
    ) {
      wrapperCache[ptr] = wrapper
    }

    /**
     * 复用已有包装：把它**当作新的一票**返回给调用方（`dup()`）。
     *
     * 每轮 `jsToJava` 转换都算调用方借到一票，命中已有包装时也不例外；否则
     * 「刚造出来的包装要还、复用的包装不用还」这条不一致的规则，会让归还的人
     * 还掉别人的票（提前释放 → use-after-free）。native 侧（`jsToJavaObject`）
     * 为此先造一个 `new JSValue(JS_DupValue(...))` 再交给这里。
     *
     * 那一票在 native 上多出来的引用由 `releaseValue(新句柄)` 立即还掉，包装自己
     * 仍然只持有**一份** native 引用 —— [release] 在票数归零时释放的正是它。
     * 与 flutter_qjs 的约定一致：转换结果归调用方持有、用完要 `free()`；
     * 想再留一份就自己 `dup()`。
     */
    @Keep
    private fun reuseWrapper(
      wrapper: Any,
      dupHandle: Long,
    ): Any {
      releaseValue(dupHandle)
      return (wrapper as JSValue).dup()
    }

    /**
     * 替代已废弃的 `Object.finalize()` 的兜底清扫。
     *
     * `finalize` 有两个致命问题：一是在任意 GC 线程执行、无法保证线程安全，二是
     * JDK 18 起被标记为 deprecated for removal。[Cleaner] 把清理动作排队到守护线程
     * 执行，并且可以用 [Cleaner.Cleanable.clean] 显式触发。
     *
     * 这里登记的是 **Context 自身**，作用域是「runtime 有没有被销毁」：即便调用方
     * 完全忘记 `close()`，Context 被 GC 时也会有人去 `JS_FreeRuntime`，不会把一个
     * runtime 连同它的堆永久漏掉。`JSValue` 的泄漏则由 [refs] 在 close 时清算 ——
     * 两条路径分工明确，不重叠。
     *
     * **闭包只捕获 `handle`（一个 Long）与 `dispatcher`，绝不捕获 Context 自身。**
     * 这是 Cleaner 的硬性要求：清理动作若持有被登记对象，该对象就永远可达，清扫器
     * 永远不会触发。
     *
     * 另外清理动作运行在 Cleaner 的守护线程上，所有 native 调用都必须回到
     * [dispatcher]，否则会和 JS 线程并发访问同一个 runtime。
     */
    private val cleaner = Cleaner.create()

    internal fun register(value: JSValue) {
      refs.add(value)
    }

    internal fun unregister(value: JSValue) {
      refs.remove(value)
    }

    private val ctxDelegate = lazy { initContext(this, stackSize, memoryLimit, timeout) }
    private val ptr by ctxDelegate
    private val updateChannel = Channel<Unit>()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dispatcherThread = runBlocking(dispatcher) { Thread.currentThread() }

    @Volatile
    private var closed = false

    private fun <T> runOnDispatcher(block: () -> T): T {
      // 关闭后 ptr 指向的 runtime 已被销毁，任何访问都是 use-after-free
      if (closed) throw IllegalStateException("QuickJS context is closed")
      if (Thread.currentThread() == dispatcherThread) return block()
      return runBlocking(dispatcher) { block() }
    }

    /**
     * 承载 `ptr` 的小盒子：让 Cleaner 的清理动作不必引用 Context 自身，
     * 同时把「销毁 runtime」变成一次性的。
     */
    private class Reachable {
      @Volatile var ptr: Long = 0
      private val armed =
        java.util.concurrent.atomic
          .AtomicBoolean(true)

      /** 返回要销毁的指针；已被销毁过则返回 null。 */
      fun disarm(): Long? = if (armed.compareAndSet(true, false)) ptr else null
    }

    private lateinit var runtimeHandle: Reachable

    init {
      // 兜底：调用方忘了 close() 时，Context 被 GC 后仍会有人去销毁 runtime。
      //
      // 清理动作只能捕获**值**，绝不能捕获 `this`：动作若持有 Context，Context 就
      // 永远可达，清扫器永远不会触发。这里把需要的东西先取成局部变量再交给 Cleaner。
      //
      // [Reachable.disarm] 保证 `destroyContext` 只被调用一次 —— close() 与 Cleaner
      // 是两条独立路径，都可能在同一个 runtime 上触发销毁，而 native 侧的
      // `JS_FreeRuntime` 不可重入。
      val dispatcherRef = dispatcher
      val reachable = Reachable()
      reachable.ptr = ptr
      runtimeHandle = reachable
      cleaner.register(this) {
        val handle = reachable.disarm() ?: return@register
        runCatching { runBlocking(dispatcherRef) { destroyContext(handle) } }
      }
      MainScope().launch(dispatcher) {
        for (v in updateChannel) {
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

    /** 销毁 runtime，保证只发生一次。 */
    private fun destroyRuntimeOnce() {
      val handle = if (::runtimeHandle.isInitialized) runtimeHandle.disarm() else null
      if (handle != null) destroyContext(handle)
    }

    private fun javaToJs(obj: Any?): Long = javaToJsImpl(obj)

    @Keep
    private fun wrapJSPromiseAsync(
      obj: Long,
      then: JSFunction?,
    ): Deferred<Any?> {
      val ret = CompletableDeferred<Any?>()
      // 两个包装都是 native 交出来的新引用，由这里负责归还：
      // - `then` 是 jsToJava 在 Promise 分支里为 "then" 属性新建的 JSFunction
      // - `thisVal` 是本函数第一个参数携带的 promise 自身（JS_DupValue 过的）
      // 漏掉任何一个，它就会一直挂在 refs 上，让关闭时的清算抛泄漏。
      //
      // `then` 可空：Kotlin 这边按 `JSFunction` 声明，但 native 传过来的是
      // `Object`，若该 Promise 的 "then" 属性不是函数（或转换失败）就是 null。
      // 以前直接 `then.close()` 会 NPE；用可空接收 + 安全调用堵住。
      val thisVal = JSValue(obj, this)
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
        jsThrowError(ptr, javaToJs(e))
      }

    private fun jsCallImpl(
      obj: JSValue,
      vararg argv: Any?,
      thisVal: Any? = null,
    ): Long =
      runOnDispatcher {
        // javaToJs 每次返回的都是「新引用」的裸句柄（新建 或 jsDupValue），
        // 这里用 try/finally 保证归还：中途抛异常也不能漏。
        val argvJs = argv.map { javaToJs(it) }.toLongArray()
        val thisJs = javaToJs(thisVal)
        try {
          val ret = jsCall(ptr, obj.ptr, thisJs, argvJs.size, argvJs)
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
     */
    internal fun releaseValue(handle: Long) {
      if (handle == 0L) return
      if (closed) return
      jsReleaseValue(ptr, handle)
      jsDestroyHandle(handle)
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
    ): Long {
      if (obj == null || obj is Unit) return jsNULL()
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
        return ret
      }
      if (obj is JSValue) {
        return jsDupValue(ptr, obj.ptr)
      }
      if (obj is Deferred<Any?>) {
        val (ret, jsRes, jsRej) = jsNewPromise(ptr)
        // jsToJava 在 native 侧就把句柄的引用消费掉了，所以 jsRes/jsRej 不需要
        // 单独归还 —— 它们的所有权已经转移给新建的 JSFunction。
        val resolve = jsToJava(ptr, jsRes) as JSFunction
        val reject = jsToJava(ptr, jsRej) as JSFunction
        MainScope().launch(dispatcher) {
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
        return ret
      }
      if (obj is Boolean) return jsNewBool(ptr, obj)
      if (obj is Byte) return jsNewInt64(ptr, obj.toLong())
      if (obj is Char) return jsNewInt64(ptr, obj.code.toLong())
      if (obj is Short) return jsNewInt64(ptr, obj.toLong())
      if (obj is Int) return jsNewInt64(ptr, obj.toLong())
      if (obj is Long) return jsNewInt64(ptr, obj)
      if (obj is Number) return jsNewFloat64(ptr, obj.toDouble())
      if (obj is String) return jsNewString(ptr, obj)
      if (obj is ByteArray) {
        return jsNewArrayBuffer(ptr, obj)
      }
      cache[obj]?.let {
        return jsDupValue(ptr, it)
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
        return ret
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
        return ret
      }
      val ret = jsWrapObject(ptr, obj)
      return if (obj is JSInvokable) {
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
     * `evaluate` native 返回的句柄由 [jsToJava] 消费掉（它在 native 侧就会调
     * `jsReleaseValue`），所以这里不需要额外归还。
     */
    fun evaluate(
      cmd: String,
      name: String = "<eval>",
      flag: Int = JSEvalFlag.GLOBAL,
    ): Any? =
      runOnDispatcher {
        val ret = evaluate(ptr, cmd, name, flag)
        updateChannel.trySend(Unit)
        if (isException(ret)) {
          throw getException(ptr)
        }
        jsToJava(ptr, ret)
      }

    /**
     * 主动销毁 runtime；重复调用是安全的。
     *
     * 销毁前会做一轮强制清算（[collectLeaks]），把还登记着的引用逐个归还。
     * 这一步不可省略：QuickJS 的 `JS_FreeRuntime` 结尾有
     * `assert(list_empty(&rt->gc_obj_list))`，只要还有活引用就是 `abort()`，
     * 整个进程会直接死掉。清算之后残留（理论上不该有）只打印不抛出 ——
     * 需要把泄漏当成**可断言的事实**时用 [closeAndCheckLeaks]。
     */
    fun close() {
      if (closed) return
      val destroy = {
        updateChannel.close()
        if (ctxDelegate.isInitialized()) {
          // 先清算再置 closed：releaseValue 在 closed 之后会拒绝工作，
          // 否则这一轮归还全部变成空操作，残留反而撑到 JS_FreeRuntime 去 abort。
          val leaked = collectLeaks()
          closed = true
          destroyRuntimeOnce()
          wrapperCache.clear()
          if (leaked.isNotEmpty()) {
            System.err.println("QuickJS reference leak:\n" + leaked.joinToString("\n"))
          }
        } else {
          closed = true
        }
      }
      if (Thread.currentThread() == dispatcherThread) {
        destroy()
      } else {
        runBlocking(dispatcher) { destroy() }
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
     * 2. **归还** —— 逐个 [JSValue.release]，让 runtime 的
     *    `assert(list_empty(&rt->gc_obj_list))` 不会命中。这个断言一旦触发就是
     *    `abort()`，整个进程会死，测试连报告都发不出来。
     *
     * 换句话说：Leak 报告负责「暴露问题」，归还负责「不让它升级成崩溃」。
     * 两者都要，且缺一不可。
     */
    internal fun collectLeaks(): List<String> {
      val snapshot = refs.toList()
      // 先记录：还在册 = 调用方漏了归还
      val leaked = snapshot.map { "  ${it.describe()}" }
      // 再归还：清空登记册，让 runtime 可以安全销毁
      snapshot.forEach { it.release() }
      refs.clear()
      return leaked
    }

    /**
     * 关闭并断言没有引用泄漏。
     *
     * 与 [close] 的区别是**泄漏会抛异常**。测试用它来把「有没有漏归还」变成可断言
     * 的事实（对照 flutter_qjs 的 `test('reference leak')`），而不是等进程崩掉。
     */
    fun closeAndCheckLeaks() {
      if (closed) return
      val runDestroy = {
        updateChannel.close()
        if (ctxDelegate.isInitialized()) {
          // 与 close 同理：清算必须在 closed 置位之前，否则 releaseValue 会拒绝执行
          val leaked = collectLeaks()
          closed = true
          destroyRuntimeOnce()
          wrapperCache.clear()
          if (leaked.isNotEmpty()) {
            throw JSError(
              "reference leak:\n    REFS\tTYPE\tPTR\n" + leaked.joinToString("\n"),
            )
          }
        } else {
          closed = true
        }
      }
      if (Thread.currentThread() == dispatcherThread) {
        runDestroy()
      } else {
        runBlocking(dispatcher) { runDestroy() }
      }
    }
  }
}
