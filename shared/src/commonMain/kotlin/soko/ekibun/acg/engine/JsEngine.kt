package soko.ekibun.acg.engine

import acg.shared.generated.resources.Res
import androidx.annotation.Keep
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import io.ktor.util.toMap
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.common.Http
import soko.ekibun.acg.web.WebViewInterception
import soko.ekibun.acg.web.WebViewRequest
import soko.ekibun.acg.web.WebViewTask
import soko.ekibun.acg.web.WebViewTaskResult
import soko.ekibun.acg.web.loadBackgroundWebView
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSRef
import soko.ekibun.quickjs.QuickJS
import soko.ekibun.quickjs.freeRecursive
import java.nio.charset.Charset

class JsEngine {
  companion object {
    val instance by lazy { JsEngine() }

    /** 引擎插件类的包名。 */
    private const val ENGINE_PACKAGE = "soko.ekibun.acg.engine"

    /** [webviewAsync] 与 `init.js` 里内联的 `webview` wrapper 约定的结果标记键。 */
    private const val WEBVIEW_KIND_KEY = "__webview_kind__"

    /** 判断一个实参能否赋给指定的形参类型（含装箱与数值放宽）。 */
    private fun acceptsArg(
      paramType: Class<*>,
      arg: Any?,
    ): Boolean {
      if (arg == null) return !paramType.isPrimitive
      // 基本类型按名字映射到装箱类名，避免在 Kotlin 里引用 java.lang.Integer.TYPE 等
      val boxedName =
        if (paramType.isPrimitive) {
          when (paramType.name) {
            "boolean" -> "java.lang.Boolean"
            "char" -> "java.lang.Character"
            "byte" -> "java.lang.Byte"
            "short" -> "java.lang.Short"
            "int" -> "java.lang.Integer"
            "long" -> "java.lang.Long"
            "float" -> "java.lang.Float"
            "double" -> "java.lang.Double"
            else -> paramType.name
          }
        } else {
          paramType.name
        }
      if (!paramType.isPrimitive && paramType.isInstance(arg)) return true
      if (arg::class.java.name == boxedName) return true
      // 数值放宽：JS 侧拿到的整数都是 Double，需要能落到 Int/Long/Float 形参上
      if (arg !is Number) return false
      return when (boxedName) {
        "java.lang.Integer", "java.lang.Long", "java.lang.Short",
        "java.lang.Byte", "java.lang.Float", "java.lang.Double",
        -> true
        else -> false
      }
    }
  }

  private var quickjsDelegate: QuickJS? = null
  private val quickjs: QuickJS
    get() =
      quickjsDelegate ?: run {
        val moduleHandler = { module: String ->
          val modulePath =
            if (module == "@init") {
              "files/js/init.js"
            } else {
              "files/js/module/" + module.replaceFirst(".js$".toRegex(), "") + ".js"
            }
          runBlocking {
            try {
              Res.readBytes(modulePath).decodeToString()
            } catch (_: Exception) {
              null
            }
          }
        }
        val ctx1 =
          runBlocking {
            QuickJS.create(
              moduleHandler = moduleHandler,
            )
          }
        quickjsDelegate = ctx1
        // 声明成 JSInvokable 会丢掉 AutoCloseable -> 这个工厂函数再也关不掉，
        // 每个引擎实例固定漏 1 票，reset() 的泄漏报告随之永久带一条噪音。
        // JS 侧没有别的引用持有它，调用完即可归还。
        // 这里在属性 getter 里，不是协程上下文，只能阻塞等这一次求值 ——
        // evaluate 本身是挂起的（它要把求值搬到 QuickJS 的归属线程上）。
        val init =
          runBlocking { ctx1.evaluate(moduleHandler("@init")!!, "<init>") } as JSFunction
        try {
          init(
            object : JSInvokable {
              override fun invoke(
                vararg argv: Any?,
                thisVal: Any?,
              ): Any? {
                val obj = argv[0]
                return if (obj is String) {
                  // 按名称实例化引擎插件类。包名必须跟随本工程的实际包名，
                  // 而不是从别处拷来的 `soko.ekibun.nekomp.*`。
                  val className = "$ENGINE_PACKAGE.$obj"
                  val cls =
                    javaClass.classLoader?.loadClass(className)
                      ?: throw JSError("cannot load class '$className'")
                  val ctorArgs = argv.sliceArray(1 until argv.size)
                  // 按实参个数选构造函数，不要盲取 constructors[0]（顺序无保证，
                  // 且多个构造函数时会选错）。
                  val ctor =
                    cls.constructors.firstOrNull { it.parameterCount == ctorArgs.size }
                      ?: throw JSError(
                        "no constructor of '$className' accepts ${ctorArgs.size} argument(s)",
                      )
                  ctor.isAccessible = true
                  ctor.newInstance(*ctorArgs)
                } else {
                  val methodName = argv[1] as String
                  val objWrap = (obj ?: this@JsEngine)
                  val callArgs = argv.sliceArray(2 until argv.size)
                  // 重载时 declaredMethods 里会有多个同名方法，`first{}` 可能选错。
                  // 用"名字 + 参数个数"匹配，仍不唯一时再按参数类型宽容匹配。
                  val candidates =
                    objWrap.javaClass.methods
                      .filter { it.name == methodName && it.parameterCount == callArgs.size }
                  val method =
                    candidates.firstOrNull { m ->
                      m.parameterTypes.withIndex().all { (i, t) -> acceptsArg(t, callArgs[i]) }
                    } ?: candidates.firstOrNull()
                      ?: throw JSError(
                        "no method '$methodName' with ${callArgs.size} argument(s) " +
                          "on ${objWrap.javaClass.name}",
                      )
                  method.isAccessible = true
                  method.invoke(objWrap, *callArgs)
                }
              }
            },
          )
        } finally {
          init.close()
        }
        ctx1
      }

  /**
   * 求值。**挂起**：[QuickJS.evaluate] 会把求值搬到 runtime 的归属线程上。
   *
   * 这里不做 `runBlocking` 包一层 —— 门面一旦阻塞，UI 侧（[soko.ekibun.acg.ui.screen
   * .CodeScreen]）就得跟着卡。调用方本来就在协程里。
   */
  suspend fun evaluate(
    cmd: String,
    name: String = "<eval>",
  ): Any? = quickjs.evaluate(cmd, name)

  fun reset() {
    // 主动销毁 runtime，而不是只把引用置空等 GC：JS 侧的 Java 对象持有
    // global ref，只有 destroyContext 触发的析构才会把它们还回去。
    quickjsDelegate?.close()
    quickjsDelegate = null
  }

  @Keep
  private fun console(
    type: String,
    data: Array<Any?>,
  ) {
    println("$type\n${data.toList()}")
  }

  @Keep
  private fun encode(
    input: String,
    to: String?,
  ): ByteArray = input.toByteArray(Charset.forName(to ?: "utf-8"))

  @Keep
  private fun decode(
    input: ByteArray,
    from: String?,
  ): String = String(input, Charset.forName(from ?: "utf-8"))

  /**
   * 起一个 [Deferred]，并在它收场时（成功 / 失败 / 取消）归还接手的 JS 实参。
   *
   * 不能用 `async` 体里的 `finally`：Deferred 被取消、协程体根本没跑起来时，
   * `finally` 不会执行，那一票就永远挂在 `QuickJS` 的登记表上了（关闭时才由
   * `collectLeaks` 兜掉并报一条 `reference leak`）。`invokeOnCompletion`
   * 三种收场都覆盖。
   */
  private fun <T> CoroutineScope.asyncReleasing(
    // 形参刻意收窄成 JSRef 而不是 AutoCloseable：QuickJS 自己也实现了 AutoCloseable，
    // 若这里接受 AutoCloseable，把 runtime 传进来就会被 invokeOnCompletion 当场销毁。
    vararg values: JSRef?,
    block: suspend CoroutineScope.() -> T,
  ): Deferred<T> {
    val job = async(block = block)
    job.invokeOnCompletion { values.forEach { it?.close() } }
    return job
  }

  /**
   * 插件 JS 的 `fetch(...)` 落到这里（`init.js` 的 `__fetch__`）。
   *
   * `options` 已经不是一票 JS 引用了：`jsToJava` 把它整图展开成纯数据的
   * `Map`，连嵌套对象也在 native 侧就地还掉了引用，所以这里**没有东西可还**。
   * 只有 `options` 里嵌了函数（fetch 选项里不会有）才需要额外 `freeRecursive`。
   */
  @Keep
  private fun fetchAsync(options: Map<Any, Any?>): Deferred<Any?> {
    return CoroutineScope(Dispatchers.IO).asyncReleasing {
      val response = Http.request(options)
      assert(response.isActive)
      return@asyncReleasing mapOf(
        "url" to response.request.url.toString(),
        "headers" to response.headers.toMap(),
        "ok" to response.status.isSuccess(),
        "redirected" to (response.status.value in 300..399),
        "status" to response.status.value,
        "body" to response.bodyAsChannel().toByteArray(),
      )
    }
  }

  /**
   * 后台 WebView 桥 —— 插件 JS 里的 `webview(url, header, script, onInterceptRequest)`。
   *
   * 对齐 BangumiPlugin `assets/modules/http.js#__webview__`，两处有意的差异：
   *
   * 1. 返回 [Deferred]（JS 侧是 Promise），不阻塞 —— QuickJS 只有一个事件循环，
   *    照参照实现那样 `Thread.sleep` 等加载会把整个引擎锁死；
   * 2. 结果多包一层 [WEBVIEW_KIND_KEY]，好让 JS wrapper 区分「命中拦截」与
   *    「脚本返回值」—— 两者都可能是任意对象，不加标记无从判别。
   *
   * `onInterceptRequest` 是全图里**唯一**要归还的东西：函数仍以 [JSFunction]
   * 包装存在、持着一票，而且它要活到 WebView 任务结束（早还就会让回调打在已经
   * 释放的包装上），所以交给 [asyncReleasing]。
   *
   * `header` 不用归还 —— 它和 `options` 一样，已经被整图展开成纯数据的 `Map`。
   */
  @Keep
  private fun webviewAsync(
    url: String,
    header: Map<Any, Any?>?,
    script: String?,
    onInterceptRequest: JSFunction?,
  ): Deferred<Any?> =
    CoroutineScope(Dispatchers.IO).asyncReleasing(onInterceptRequest) {
      val task =
        WebViewTask(
          url = url,
          headers =
            header?.entries?.associate { it.key.toString() to it.value.toString() }
              ?: emptyMap(),
          script = script,
          onInterceptRequest =
            onInterceptRequest?.let { fn ->
              { request: WebViewRequest -> invokeInterceptor(fn, request) }
            },
        )

      val payload: Any? =
        when (val result = loadBackgroundWebView(task)) {
          is WebViewTaskResult.Intercepted ->
            mapOf(
              WEBVIEW_KIND_KEY to "intercept",
              "value" to
                mapOf(
                  "url" to result.interception.url,
                  "headers" to result.interception.headers,
                ),
            )

          is WebViewTaskResult.Scripted ->
            mapOf(
              WEBVIEW_KIND_KEY to "script",
              "value" to result.json,
            )

          // 失败要让 JS 侧 reject，而不是回一个空值让脚本去猜哪一步没生效。
          is WebViewTaskResult.Failed -> throw JSError(result.message)
        }
      payload
    }

  /**
   * 把 WebView 的回调转给 JS 侧的 `onInterceptRequest`。
   *
   * 返回 null 表示放行。JS 回调抛错也按放行处理 —— 一次回调出错不该把整次加载
   * 搞崩，但会把错误打到 console 上，别让它无声无息。
   *
   * 回调返回的 JS 对象在 Kotlin 侧已经被 `jsToJava` 整图展开成 `Map` / `Array`
   * 那样的纯数据，`as? Map` 判断天然成立。数据本身没有票可还，但展开出来的图里
   * 可能嵌着函数包装（它们各持一票），所以收尾用 `freeRecursive()` 穿透容器去还
   * —— 不还的话每拦一次请求就在 `QuickJS` 的登记表上挂一笔。
   *
   * 调用时机：本函数跑在 WebView 的回调线程上，此时脚本正挂起等 `webviewAsync`
   * 的结果，QuickJS 派发线程是空闲的，所以这里的 `fn.invoke` / `close`
   * 都能安全地借道 `Pointer.withPtrSync` 落到 JS 线程（`fn.invoke` 内部就是它）。
   */
  private fun invokeInterceptor(
    fn: JSFunction,
    request: WebViewRequest,
  ): WebViewInterception? {
    val ret =
      try {
        fn.invoke(
          mapOf(
            "url" to request.url,
            "headers" to request.headers,
            "method" to request.method,
            "isForMainFrame" to request.isForMainFrame,
            "isRedirect" to request.isRedirect,
          ),
        )
      } catch (e: Throwable) {
        console("error", arrayOf("onInterceptRequest 回调出错，按放行处理: $e"))
        return null
      }
    try {
      val map = ret as? Map<*, *> ?: return null
      val interceptedUrl = map["url"] as? String ?: return null
      val headers = map["headers"] as? Map<*, *>
      return WebViewInterception(
        url = interceptedUrl,
        headers =
          headers
            ?.entries
            ?.associate { it.key.toString() to it.value.toString() }
            ?: emptyMap(),
      )
    } finally {
      ret.freeRecursive()
    }
  }
}
