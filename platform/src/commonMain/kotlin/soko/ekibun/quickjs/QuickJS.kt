package soko.ekibun.quickjs

import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.loadLibrary

/**
 * 一个独立的 QuickJS runtime。
 *
 * @param moduleHandler 模块加载器，返回 null 表示模块不存在
 * @param stackSize JS 层最大栈空间（字节），<=0 用默认 256KB
 * @param memoryLimit 堆内存上限（字节），<=0 表示不限制
 * @param timeout 单次 evaluate / executePendingJob 的超时（毫秒），<=0 表示不限制
 *
 * 生命周期与转换规则见 `docs/development/quickjs-lifetime.md`。
 */
class QuickJS(
  val moduleHandler: ((String) -> String?)? = null,
  val stackSize: Long = 256 * 1024,
  val memoryLimit: Long = -1,
  val timeout: Long = -1,
  /** 归属线程：所有 native 调用都投递到这里串行执行，默认共享 [sharedDispatcher]。 */
  val dispatcher: ThreadDispatcher = sharedDispatcher,
) : Pointer(dispatcher = dispatcher) {
  companion object {
    /** [dispatcher] 的默认值：全进程共享一条线程，名字 `quickjs`。 */
    val sharedDispatcher = ThreadDispatcher("quickjs")

    init {
      loadLibrary("quickjs")
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

    /** 释放出口：放引用 + 销毁包装，一次过界。 */
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
    // C API 的薄包装，不做类型判断；分派与释放在 [jsToJava]。

    /** 反向映射后的 tag 码（原始 tag 不出 native），其余 tag 由分派器接住转 null。 */
    private const val JS_TAG_OBJECT = 5
    private const val JS_TAG_STRING = 4
    private const val JS_TAG_FLOAT64 = 3
    private const val JS_TAG_INT = 2
    private const val JS_TAG_BOOL = 1

    /** 返回反向映射后的 tag 码。 */
    @JvmStatic
    private external fun jsGetTag(obj: Long): Int

    /** cache 的键：JS 对象指针。 */
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

    /** `ToString` + UTF-8 拷贝在 C 侧完成；失败给 null。 */
    @JvmStatic
    private external fun jsToCString(
      ctx: Long,
      obj: Long,
    ): String?

    /** 非 ArrayBuffer 给 null。 */
    @JvmStatic
    private external fun jsGetArrayBuffer(
      ctx: Long,
      obj: Long,
    ): ByteArray?

    /** 取 `jsWrapObject` 挂的原对象，不是包装时给 null。 */
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

    /** 全部自有属性（含不可枚举与 symbol）的 (key, value) 交错句柄对，失败给 null。 */
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

  /** 仍持有 native 引用的对象，按对象身份强引用登记。 */
  private val refs = HashSet<JSRef>()

  internal fun register(value: JSRef) {
    refs.add(value)
  }

  internal fun unregister(value: JSRef) {
    refs.remove(value)
  }

  private val updateChannel = Channel<Unit>()

  /** 算出 runtime + context。 */
  override fun initPtr(): Long = initContext(this@QuickJS, stackSize, memoryLimit, timeout)

  /** 销毁 runtime：先清算登记在册的 JSRef，再销毁。 */
  override suspend fun releaseImpl(ptr: Long) {
    // 关通道让 pump 的 for 循环正常结束
    updateChannel.close()
    // 先清算再销毁。
    val leaked = collectLeaks()
    if (leaked.isNotEmpty()) {
      println("QuickJS reference leak:\n" + leaked.joinToString("\n"))
    }
    destroyContext(ptr)
  }

  init {
    // 只起 pump：投递即返回。
    submit {
      for (v in updateChannel) {
        // 已在归属线程上。
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

  /** JS → Java 的转换入口：分派、递归、防环、释放；消费 [obj] 那一票。 */
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
          // NULL / UNDEFINED / EXCEPTION / symbol / bigint 一律 null
          else -> null
        }
      }
      // ArrayBuffer 探针在最前：一次过界拿字节，C 侧按 class id 过滤
      jsGetArrayBuffer(ptr, obj)?.let { return it }
      // Java 来路的对象还原成原实例。
      jsGetOpaque(ptr, obj)?.let { return it }
      val key = jsValueGetPtr(obj)
      cache[key]?.let { return it }
      if (jsIsFunction(ptr, obj)) {
        // 可调用的活对象不展开；dup 出的新票归拿到它的人
        return JSFunction(jsDupValue(ptr, obj), this)
      }
      if (jsIsError(ptr, obj)) {
        // JSError 是纯数据。
        val message = jsToCString(ptr, obj)
        val stack =
          jsToJava(ptr, jsGetPropertyStr(ptr, obj, "stack"), cache) as? String
        return JSError(message, stack)
      }
      if (jsIsPromise(ptr, obj)) {
        // promise 转成 Deferred，一次遍历内同一个 promise 给同一个
        val thenHandle = jsGetPropertyStr(ptr, obj, "then")
        // 只递函数过去。
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
        val arr = ArrayList<Any?>(len.toInt())
        cache[key] = arr
        for (i in 0 until len) {
          val elem = jsGetPropertyUint32(ptr, obj, i.toInt())
          arr.add(jsToJava(ptr, elem, cache))
        }
        return arr
      }
      // 普通对象展开成 LinkedHashMap（键的插入序即属性序）。
      val map = LinkedHashMap<Any?, Any?>()
      cache[key] = map
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

  /** 给 [JSFunction] 用的转换门。 */
  internal fun toJava(handle: Long): Any? = jsToJava(ptr, handle)

  /** 把 JS Promise 包成 [Deferred]，消费 [obj] 那一票。 */
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

  /** JS 调 Kotlin 的入口：转换实参与 this，返回值经 [javaToJs] 递回。 */
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
    // isClosed 门由 withPtrSync 兜住
    withPtrSync { ptr ->
      // javaToJs 返回的都是新引用，中途抛异常也不能漏还
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

  /** 归还一个 [JSRef]：撤销登记 + 还它自己那一票。 */
  internal fun releaseRef(ref: JSRef) {
    unregister(ref)
    jsFreeValue(ptr, ref.ptr)
  }

  /** Java 值 → JS 句柄；`cache` 按对象身份去重。 */
  private fun javaToJsImpl(
    obj: Any?,
    cache: IdentityMap<Any, Long> = IdentityMap(),
  ): Long =
    withPtrSync { ptr ->
      if (obj == null || obj is Unit) return@withPtrSync jsNULL()
      if (obj is Throwable) {
        val ret = jsNewError(ptr)
        // definePropertyValue 完整接管 k/v 两个句柄，且句柄一次性、不能复用
        definePropertyValue(
          ptr,
          ret,
          jsNewString(ptr, "name"),
          jsNewString(ptr, obj::class.qualifiedName ?: "Error"),
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
        // jsToJava 消费这两个句柄，所有权转移给新建的 JSFunction
        val resolve = jsToJava(ptr, jsRes) as JSFunction
        val reject = jsToJava(ptr, jsRej) as JSFunction
        submit {
          try {
            resolve.invoke(obj.await())
          } catch (e: Throwable) {
            reject.invoke(e)
          } finally {
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
      // AutoCloseable 不另开分支：jsWrapObject 挂的 JavaObject 就是析构回调回收它的抓手
      val ret = jsWrapObject(ptr, obj)
      return@withPtrSync if (obj is JSInvokable) {
        val func = jsNewCFunction(ptr, ret)
        // func_data 已持有 ret，这里只归还我们手上这一票
        jsFreeValue(ptr, ret)
        func
      } else {
        ret
      }
    }

  /** 求值并转换结果（挂起，整段跑在归属线程上）。 */
  suspend fun evaluate(
    cmd: String,
    name: String = "<eval>",
    flag: Int = JSEvalFlag.GLOBAL,
  ): Any? {
    // 关闭后拒绝。
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

  /** 关闭前的清算：先记录泄漏，再逐个归还。 */
  internal suspend fun collectLeaks(): List<String> {
    val snapshot = refs.toList()
    // 还在册 = 调用方漏了归还
    val leaked = snapshot.map { "  ${it.describe()}" }
    snapshot.forEach { it.closeDeferred().join() }
    refs.clear()
    return leaked
  }
}

/** 按对象身份（`===`）比对的表；哈希取常数 0，只实现 [get] 与 [set]。 */
private class IdentityMap<K : Any, V> {
  private val keys = ArrayList<K>()
  private val values = ArrayList<V>()

  /** 读侧：没有这一票就说明还没转过它。 */
  operator fun get(key: K): V? {
    val i = indexOfKey(key)
    return if (i >= 0) values[i] else null
  }

  /** 写侧：容器在**填之前**登记。 */
  operator fun set(
    key: K,
    value: V,
  ) {
    val i = indexOfKey(key)
    if (i >= 0) {
      values[i] = value
    } else {
      keys += key
      values += value
    }
  }

  /** 只有身份相同才算命中。 */
  private fun indexOfKey(key: K): Int = keys.indexOfFirst { it === key }
}
