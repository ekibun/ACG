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
import soko.ekibun.quickjs.JSObject
import soko.ekibun.quickjs.QuickJS
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

  private var quickjsDelegate: QuickJS.Context? = null
  private val quickjs: QuickJS.Context
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
          QuickJS.Context(
            moduleHandler = moduleHandler,
          )
        quickjsDelegate = ctx1
        val init = ctx1.evaluate(moduleHandler("@init")!!, "<init>") as JSInvokable
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
        ctx1
      }

  fun evaluate(
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

  @Keep
  private fun fetchAsync(options: JSObject): Deferred<Any?> {
    return CoroutineScope(Dispatchers.IO).async {
      val response = Http.request(options)
      assert(response.isActive)
      return@async mapOf(
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
   * `header` 与 `onInterceptRequest` 到达这里时各持一票 JS 引用（每轮 `jsToJava`
   * 转换都会拿到独立的一票，复用已有包装时也一样，见 `Context.reuseWrapper`）。
   * 本函数**不归还它们**：归还本身是安全的（票分开算），但 `onInterceptRequest`
   * 要活到 WebView 任务结束，早还就会让回调打在已经释放的包装上。代价是每次调用
   * 留下一到两票 —— 收尾见 TODO「JsEngine 不归还 JS 参数」。
   */
  @Keep
  private fun webviewAsync(
    url: String,
    header: JSObject?,
    script: String?,
    onInterceptRequest: JSFunction?,
  ): Deferred<Any?> =
    CoroutineScope(Dispatchers.IO).async {
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
   * 回调返回的 JS 对象在 Kotlin 侧是 [JSObject] 包装（它实现了 `Map`，所以
   * `as? Map` 判断照样成立），每读一个属性都是一轮 native 转换。**每轮转换都归
   * 调用方一票，拿到就得还** —— 与 `QuickJSTest.jsInvokableReceivesThisValAndArgs`
   * 对回调里 `thisVal` 的处理一致；不还的话每拦一次请求就在 `Context.refs` 上挂一笔。
   *
   * 调用时机：本函数跑在 WebView 的回调线程上，此时脚本正挂起等 `webviewAsync`
   * 的结果，QuickJS 派发线程是空闲的，所以这里的 `fn.invoke` / `close`
   * 都能安全地借道 `runOnDispatcher` 落到 JS 线程。
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
      try {
        return WebViewInterception(
          url = interceptedUrl,
          headers =
            headers
              ?.entries
              ?.associate { it.key.toString() to it.value.toString() }
              ?: emptyMap(),
        )
      } finally {
        (headers as? AutoCloseable)?.close()
      }
    } finally {
      (ret as? AutoCloseable)?.close()
    }
  }
}
