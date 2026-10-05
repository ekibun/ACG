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

    /**
     * 释放出口的 native 落地：放一票 JS 引用 + 销毁堆上包装，**一次过界**。
     * C 侧另有粒度 helper（`jsReleaseValue` / `jsDestroyHandle`）给
     * `definePropertyValue` 按「只要其一」取用，不经 JNI 暴露。
     */
    @JvmStatic
    private external fun jsFreeValue(
      ctx: Long,
      obj: Long,
    )

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

    // ---- JS → Java 的透传原语 ----
    // 类型分派 / 递归 / 防环 / 释放全在下面的 [jsToJava]；这里的声明是 QuickJS
    // C API 的薄包装，不做任何转换判断（分层对齐 flutter_qjs；jsGetOwnProperties
    // 是唯一打包多条 C 调用的例外——只打包读取，分派仍全在 Kotlin）。

    /**
     * [jsGetTag] 反向映射出来的 tag 码：**名字**沿用 QuickJS 的 JS_TAG_*，
     * **数值**是 C 侧映射的返回值（QuickJS 原始 tag —— 含随构建配置变形的
     * FLOAT64 判据 —— 不出 native，这里不镜像 ABI）。五个名字之外的 tag
     * （NULL / UNDEFINED / EXCEPTION / symbol / bigint）不设常量，由分派器的
     * `else` 接住转 null。
     */
    private const val JS_TAG_OBJECT = 5
    private const val JS_TAG_STRING = 4
    private const val JS_TAG_FLOAT64 = 3
    private const val JS_TAG_INT = 2
    private const val JS_TAG_BOOL = 1

    /** 返回反向映射后的 tag 码（映射表在 C 侧，QuickJS 原始 tag 不出 native）。 */
    @JvmStatic
    private external fun jsGetTag(obj: Long): Int

    /** cache 的键：JS 对象指针（仅 OBJECT tag 的值有意义）。 */
    @JvmStatic
    private external fun jsValueGetPtr(obj: Long): Long

    @JvmStatic
    private external fun jsIsFunction(
      ctx: Long,
      obj: Long,
    ): Boolean

    @JvmStatic
    private external fun jsIsError(
      ctx: Long,
      obj: Long,
    ): Boolean

    @JvmStatic
    private external fun jsIsPromise(
      ctx: Long,
      obj: Long,
    ): Boolean

    @JvmStatic
    private external fun jsIsArray(
      ctx: Long,
      obj: Long,
    ): Boolean

    @JvmStatic
    private external fun jsToBool(
      ctx: Long,
      obj: Long,
    ): Boolean

    @JvmStatic
    private external fun jsToInt64(
      ctx: Long,
      obj: Long,
    ): Long

    @JvmStatic
    private external fun jsToFloat64(
      ctx: Long,
      obj: Long,
    ): Double

    /** `ToString` 转换 + UTF-8 拷贝（在 C 侧完成）；失败给 null 并就地清掉异常。 */
    @JvmStatic
    private external fun jsToCString(
      ctx: Long,
      obj: Long,
    ): String?

    /** 非 ArrayBuffer 给 null（C 侧先按 class id 过滤，不往 runtime 抛异常）；ArrayBuffer 则拷出字节。 */
    @JvmStatic
    private external fun jsGetArrayBuffer(
      ctx: Long,
      obj: Long,
    ): ByteArray?

    /** 取 `jsWrapObject` 挂在 JavaObject 类上的原对象（global ref），不是包装时给 null。 */
    @JvmStatic
    private external fun jsGetOpaque(
      ctx: Long,
      obj: Long,
    ): Any?

    @JvmStatic
    private external fun jsGetPropertyStr(
      ctx: Long,
      obj: Long,
      name: String,
    ): Long

    @JvmStatic
    private external fun jsGetPropertyUint32(
      ctx: Long,
      obj: Long,
      index: Int,
    ): Long

    /**
     * 全部自有属性（含不可枚举与 symbol）取成 **(key, value) 交错**的句柄对，
     * 枚举失败给 null。atom 在 C 侧就地物化并放掉、不过 JNI 界；两类句柄都由
     * 调用方（[jsToJava] 的对象分支）消费。
     */
    @JvmStatic
    private external fun jsGetOwnProperties(
      ctx: Long,
      obj: Long,
    ): LongArray?

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
   * `0`（[Pointer] 的指针不可更改），别拿 `ptr == 0L` 判断任何状态。
   */
  override fun initPtr(): Long = initContext(this@QuickJS, stackSize, memoryLimit, timeout)

  /**
   * 真正销毁 runtime —— 覆写基类的归还钩子，[Pointer] 保证**只跑一次**。
   *
   * 句柄由基类**以参数交进来**（[Pointer.releaseImpl] 的 `ptr`），不必自己去读；跑在归属
   * 线程上（基类 [Pointer.submit] 投递过来的）。**不自己设门**：唯一入口 [Pointer.closeDeferred]
   * 已经用基类的
   * [Pointer.markClosed] 抢过名额，这里再抢必然失败 —— 那会变成「返回成功、其实没
   * 销毁」，runtime 连同它整个堆漏在 native。
   *
   * 清算（[collectLeaks]）必须在标记之后、销毁之前照常归还引用，否则残留会撑到
   * `JS_FreeRuntime` 去触发 `gc_obj_list` 断言、整个进程 abort。这也是本函数**不走**查 [isClosed] 的两扇门、直读 [ptr] 的原因。
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
   * `use {}` 这类语法契约。漏掉的引用由 [refs] 在关闭时清算并报告到 System.err，
   * 测试捕获它断言。
   */
  override suspend fun releaseImpl(ptr: Long) {
    // 泵退出靠关通道：executePendingJob 不会再跑（通道关闭让 for 循环正常结束）。
    updateChannel.close()
    // 先清算再销毁，顺序承重（见 [collectLeaks]）：登记在册的 JSRef 指向本 runtime
    // 内部，销毁前必须逐个归还，否则 JS_FreeRuntime 的 gc_obj_list 断言 abort。
    // 泄漏清单走 System.err —— 生产可观测；测试捕获它断言（没有为测试保留的
    // 结果通道）。
    val leaked = collectLeaks()
    if (leaked.isNotEmpty()) {
      System.err.println("QuickJS reference leak:\n" + leaked.joinToString("\n"))
    }
    destroyContext(ptr)
  }

  init {
    // 句柄是惰性的（首次读时才建，见 [initPtr]），这里只起 pump。投递即返回、不等它。
    submit {
      for (v in updateChannel) {
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
   * 所有 JS → Java 转换的唯一入口。native 只留透传原语（分层对齐 flutter_qjs 的
   * `_jsToDart`），类型分派、递归、防环、释放都在这层 Kotlin 里。
   *
   * 对象分支放在**这里**而不是各个调用点，是有原因的：递归转换（数组元素、promise
   * 的 `then`）必须一律走本函数 —— 绕开它走只认标量的辅助函数时，顺着手碰到的
   * 对象会悄悄变成 null（取证见 `quickjs-reference-ownership.md` 规则 3）。
   *
   * `cache` 整棵对象图共用同一张表（[ptr] 逐层递下去），键是 [jsValueGetPtr]。
   * 三条登记规则，对齐上游 `_jsToDart`（取证见 `quickjs-reference-ownership.md`
   * 规则 4）：
   * 1. **每次顶层调用新建**一张，只在一次遍历内有效；
   * 2. 容器（数组 / 普通对象 / Promise）在**填之前**登记 —— 递归回自身时拿到的
   *    正是那个还在填的容器，`a['a'] === a` 天然成立，共享子对象也不会被转成
   *    两份副本；
   * 3. **函数不登记** —— 它是全图唯一保留的包装，每次出现各造一个独占包装、
   *    谁拿到谁还。登记会制造共享：某个拿到的人 `close()` 之后，别处再命中
   *    cache 得到的便是已关闭的包装，读已析构的 JSValue，不报错、只失效。
   *
   * **消费句柄**：[obj] 那一票由这里放掉（所有路径收尾 [jsFreeValue]），调用方
   * 之后不得再碰它。函数 / Promise 包装持的是自己
   * [jsDupValue] 的新票，生命周期归拿到它的人。
   *
   * 必须与调用同块落在 JS 线程上（[withPtr] / [withPtrSync] 块内）：转换的每一步
   * 都在碰 runtime，挪到调用方线程就是「调用 → 转换」与 executePendingJob / GC
   * 并发，偶发崩溃（`quickjs-reference-ownership.md` 规则 7）。
   */
  private fun jsToJava(
    ptr: Long,
    obj: Long,
    cache: MutableMap<Long, Any?> = HashMap(),
  ): Any? {
    try {
      val tag = jsGetTag(obj)
      if (tag != JS_TAG_OBJECT) {
        return when (tag) {
          JS_TAG_BOOL -> jsToBool(ptr, obj)
          JS_TAG_INT -> jsToInt64(ptr, obj)
          JS_TAG_FLOAT64 -> jsToFloat64(ptr, obj)
          JS_TAG_STRING -> jsToCString(ptr, obj)
          // NULL / UNDEFINED / EXCEPTION / symbol / bigint：一律 null（undefined
          // → null 是 JSInvokable 记录过的契约：JS 裸调 f() 时 this 就是这么落的）。
          else -> null
        }
      }
      // ArrayBuffer 探针在最前（与旧 C++ 同序）：一次过界拿字节，非 ArrayBuffer
      // 给 null —— C 侧先按 class id 过滤，不往 runtime 抛 TypeError。
      jsGetArrayBuffer(ptr, obj)?.let { return it }
      // Java 侧来路的对象：还原成 opaque 上挂着的原对象。它没有票，也不必进
      // cache —— 同一个 JS 对象两个位置还原出来的是同一个 Java 实例。
      jsGetOpaque(ptr, obj)?.let { return it }
      val key = jsValueGetPtr(obj)
      cache[key]?.let { return it }
      if (jsIsFunction(ptr, obj)) {
        // 可调用的活对象，展开成数据没有意义；dup 出的新票归拿到它的人 free()。
        return JSFunction(jsDupValue(ptr, obj), this)
      }
      if (jsIsError(ptr, obj)) {
        // JSError 是纯数据（message + stack），与 JS 侧脱钩，没有票。message 是
        // Error 本体的 ToString；stack 走标准分派（真栈是字符串，等价；数字 /
        // 对象 / symbol 一类野值一律 null，句柄由递归自己消费）。
        val message = jsToCString(ptr, obj)
        val stack =
          jsToJava(ptr, jsGetPropertyStr(ptr, obj, "stack"), cache) as? String
        return JSError(message, stack)
      }
      if (jsIsPromise(ptr, obj)) {
        // promise 不展开成数据：它要等 then 回调，落在调用方手里的值是 Deferred
        // （对齐上游 _jsToDart 的 completer.future）。一次遍历内同一个 promise 给
        // 同一个 Deferred。
        val thenHandle = jsGetPropertyStr(ptr, obj, "then")
        // 只把**函数**递过去：then 不是函数（或是个 thenable 对象）时给 null，
        // [wrapJSPromiseAsync] 按「promise 没有可调用的 then」处理。不能无条件
        // 递归：非函数的 then 会被展开成 Map，塞进 JSFunction 形参里。
        val then =
          if (jsIsFunction(ptr, thenHandle)) {
            jsToJava(ptr, thenHandle, cache) as JSFunction
          } else {
            jsFreeValue(ptr, thenHandle)
            null
          }
        val ret = wrapJSPromiseAsync(ptr, jsDupValue(ptr, obj), then)
        cache[key] = ret
        return ret
      }
      if (jsIsArray(ptr, obj)) {
        val lenHandle = jsGetPropertyStr(ptr, obj, "length")
        val len = jsToInt64(ptr, lenHandle)
        jsFreeValue(ptr, lenHandle)
        // 用 ArrayList 承接，调用方拿到的是 List；填之前登记进 cache
        // （`arr[0] = arr` 时递归命中的就是这个数组自己）。
        val arr = ArrayList<Any?>(len.toInt())
        cache[key] = arr
        for (i in 0 until len) {
          val elem = jsGetPropertyUint32(ptr, obj, i.toInt())
          arr.add(jsToJava(ptr, elem, cache))
        }
        return arr
      }
      // 普通对象：**整图展开**成 LinkedHashMap。展开出来的是纯数据，与 JS 侧脱钩
      // —— 改它不会影响 JS，也就不需要包装 + 引用计数那一整套。
      val map = LinkedHashMap<Any?, Any?>()
      // 同样在填之前登记（理由同数组）。
      cache[key] = map
      // 自有属性已在 C 侧取成 (key, value) 交错的句柄对（atom 不过界）；两支
      // 句柄各由下面的递归转换消费。
      val pairs = jsGetOwnProperties(ptr, obj)
      if (pairs != null) {
        for (i in pairs.indices step 2) {
          map[jsToJava(ptr, pairs[i], cache)] = jsToJava(ptr, pairs[i + 1], cache)
        }
      }
      return map
    } finally {
      jsFreeValue(ptr, obj)
    }
  }

  /**
   * 把 native 句柄转成 Java 值，供 [JSFunction] 调用。
   *
   * [jsToJava] 是 `QuickJS` 的私有成员，只有它自己的成员够得着。[JSFunction] 是
   * **顶层**类、只是 [JSRef] 的子类 —— Kotlin 的 `private` 不跨继承传递，所以这里
   * 开一扇 `internal` 的门，而不是把 [jsToJava] 本身放出去。
   */
  internal fun toJava(handle: Long): Any? = jsToJava(ptr, handle)

  /**
   * 把 JS Promise 包成 [Deferred]：拿 promise 的 `then`，配 resolve / reject 两个
   * [JSInvokable] 回调调过去（对齐 flutter_qjs 用 completer 承接 `then` 的做法）。
   *
   * `obj` 是调用方 [jsDupValue] 出来的**独占**引用，由这里包进 [JSRef]、收尾归还；
   * 漏还就挂在 refs 上、关闭清算时抛泄漏。`then` 可空（promise 没有可调用的
   * `then`），那层安全调用别去掉。
   */
  private fun wrapJSPromiseAsync(
    ptr: Long,
    obj: Long,
    then: JSFunction?,
  ): Deferred<Any?> {
    val ret = CompletableDeferred<Any?>()
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
      ).let { jsFreeValue(ptr, it) }
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

  /**
   * JS 调 Kotlin 实现的函数时的入口（C 回调 `jsNewCFunction` 只递句柄）。
   *
   * 实参 / this 的转换连同句柄消费在这里做：每个实参一张新 cache（顶层调用自开
   * 一张，对齐 [jsToJava] 的默认参数）。`argv` 是 C 侧 [jsDupValue] 出来的新票，
   * [jsToJava] 逐个消费；返回值经 [javaToJs] 变成句柄递回 C。
   */
  @Keep
  private fun handleJSInvokable(
    obj: JSInvokable,
    argv: LongArray,
    thisVal: Long,
  ): Long =
    try {
      val (args, thisJava) =
        withPtrSync { ptr ->
          Array(argv.size) { jsToJava(ptr, argv[it]) } to jsToJava(ptr, thisVal)
        }
      javaToJs(obj.invoke(*args, thisVal = thisJava))
    } catch (e: Throwable) {
      e.printStackTrace()
      withPtrSync { ptr -> jsThrowError(ptr, javaToJs(e)) }
    }

  internal fun jsCallImpl(
    obj: JSRef,
    vararg argv: Any?,
    thisVal: Any? = null,
  ): Long =
    // 「标记即拒绝」由 withPtrSync 的 isClosed 门自动兜住（close 之后的调用当场抛）。
    withPtrSync { ptr ->
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
        jsFreeValue(ptr, thisJs)
        argvJs.forEach { jsFreeValue(ptr, it) }
      }
    }

  /**
   * 归还一个 [JSRef]：撤销登记 + 还掉它那一票。
   *
   * 归还跑在「已标记、未销毁」窗口里，**不走**查 [isClosed] 的 withPtrSync —— 直读
   * 两份句柄（本类的 ctx 句柄与 [ref] 自己的值句柄）。本函数只被 [JSRef.releaseImpl]
   * 调，而 close() / free() 可能从**任意线程**发起 —— 它们经 [Pointer.closeDeferred]
   * 的 `submit { releaseImpl(ptr) }` 投到归属线程上，**submit 就是线程边界**：落地时
   * 已在归属线程，这里不必（也不可）再自行切换。
   *
   * ⚠️ **别包 withPtrSync**：它带着 isClosed 门，会把这个窗口里的归还当场挡掉
   * （[collectLeaks] 那一轮清算就此断掉）。**也别改 submit**：那会把归还排队到当前
   * 块之后，而 close 的 [releaseImpl]（销毁）不等它 —— 对着已销毁的
   * runtime 释放句柄，use-after-free。线程保证来自调用链（基类投递 → 落地后同步直调），
   * `ptr` 的同线程断言（`-ea` 下）兜住跑错线程的调用。
   *
   * ⚠️ 还的是 **ref 自己的值句柄**（`ref.ptr`），**不是**本类的 ctx 句柄 —— 两个都是
   * `Long`，混用就是拿 runtime 指针对去 `jsFreeValue`，当场踩坏 native 堆（实测表现是
   * 测试进程 `0xC0000374` heap corruption 直接死掉）。
   */
  internal fun releaseRef(ref: JSRef) {
    unregister(ref)
    jsFreeValue(ptr, ref.ptr)
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
        // jsToJava 会消费掉这两个句柄（所有路径收尾 jsFreeValue），所以
        // jsRes/jsRej 不需要单独归还 —— 所有权已经转移给新建的 JSFunction。
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
        jsFreeValue(ptr, ret)
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
   * `evaluate` native 返回的句柄由 [jsToJava] 消费掉（所有路径收尾
   * [jsFreeValue]），所以这里不需要额外归还。
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
}
