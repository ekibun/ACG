package soko.ekibun.acg.engine

import acg.shared.generated.resources.Res
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import io.ktor.util.toMap
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.encodeToByteArray
import io.ktor.utils.io.charsets.forName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import soko.ekibun.common.Http
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSRef
import soko.ekibun.quickjs.QuickJS
import soko.ekibun.quickjs.freeRecursive
import soko.ekibun.web.WebViewInterception
import soko.ekibun.web.WebViewRequest
import soko.ekibun.web.WebViewTask
import soko.ekibun.web.WebViewTaskResult
import soko.ekibun.web.loadBackgroundWebView

class JsEngine {
  companion object {
    val instance by lazy { JsEngine() }

    /** [webviewAsync] 与 `init.js` 里内联的 `webview` wrapper 约定的结果标记键。 */
    private const val WEBVIEW_KIND_KEY = "__webview_kind__"
  }

  private var quickjsDelegate: QuickJS? = null

  /**
   * 惰性初始化的锁：首建是一段 check-then-act（建 runtime、求值 init、赋值），
   * 两个协程并发首访会各建一个 [QuickJS]，后赋值者覆盖先前 —— 那一个永不
   * [close][QuickJS.close]（runtime + 归属线程泄漏）。锁里那些 `runBlocking`
   * 没有死锁风险：等的是 QuickJS 的归属线程，而归属线程上的回调
   * （`handleJSInvokable` / `init` 那个桥）都不进这把锁。
   */
  private val quickjsLock = Any()

  private val quickjs: QuickJS
    get() =
      synchronized(quickjsLock) {
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
          val ctx1 = QuickJS(moduleHandler = moduleHandler)
          quickjsDelegate = ctx1
          // `init` 是**工厂函数**，JS 侧没别的引用持有它，调用完即可归还 —— 但必须 `as JSFunction`：
          // 声明成 `as JSInvokable` 会丢掉 `AutoCloseable`，每个引擎实例固定漏 1 票（见 [ability-bridge]）。
          // 这里在属性 getter 里、没有协程上下文，只能阻塞等（`evaluate` 本身是挂起的）。
          val init =
            runBlocking { ctx1.evaluate(moduleHandler("@init")!!, "<init>") } as JSFunction
          try {
            init(
              // `_binding(method, args)` 的落地：`argv` 是 init.js 那一**份**实参（`vararg` 打的
              // `Array<Any?>`），`thisVal` 是隐式接收者、不占位。`when` **直接调**各能力、不按名字找 ⇒
              // `private` 天然不受影响、写错名字是编译错误（反射走不通，依据见 [ability-bridge]）。
              JSInvokable { argv ->
                val args = argv[1] as List<*>
                when (argv[0] as String) {
                  "encode" -> encode(args[0] as String, args[1] as String?)
                  "decode" -> decode(args[0] as ByteArray, args[1] as String?)
                  // args[1] 是 console.log(...) 的**实参数组**，作为 args 的元素整体送达，
                  // 不是摊平成位置参数。
                  "console" -> console(args[0] as String, args[1] as List<Any?>)
                  // `fetch` 只建会话、**不读 body**：body 交给 `Response.arrayBuffer`
                  // 按需取（`init.js` 那边 await 之后才调）。所以 `_opaque` 交的是
                  // [Http.Response] 本体，JS 侧那个 `JavaObject` 包装就是 finalizer
                  // 能回收它的抓手（`close()` 由析构回调代调，见 cxx/quickjs/quickjs.cpp）。
                  "fetch" ->
                    CoroutineScope(Dispatchers.IO).async {
                      val rsp = Http.request(args[0] as Map<Any, Any?>)
                      val meta = rsp.delegate
                      mapOf(
                        "url" to meta.request.url.toString(),
                        "headers" to meta.headers.toMap(),
                        "ok" to meta.status.isSuccess(),
                        "redirected" to (meta.status.value in 300..399),
                        "status" to meta.status.value,
                        "_opaque" to rsp,
                      )
                    }
                  "Response.arrayBuffer" ->
                    CoroutineScope(Dispatchers.IO).async {
                      (args[0] as Http.Response).readAll()
                    }
                  // webview 的 4 个实参打进 args，不是位置参数。args[3] 是回调，
                  // 它的归属语义见 [webviewAsync]。
                  "webview" ->
                    webviewAsync(
                      args[0] as String,
                      args[1] as Map<Any, Any?>?,
                      args[2] as String?,
                      args[3] as JSFunction?,
                    )
                  else -> throw JSError("unknown method '${argv[0]}'")
                }
              },
            )
          } finally {
            init.close()
          }
          ctx1
        }
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
    synchronized(quickjsLock) {
      quickjsDelegate?.close()
      quickjsDelegate = null
    }
  }

  private fun console(
    type: String,
    data: List<Any?>,
  ) {
    println("$type\n$data")
  }

  /**
   * [input] 按 [to] 编码成字节 —— `TextEncoder` 那侧的实现。
   *
   * [to] 是 JS 侧 `new TextEncoder(label)` 的 `label`，**原样交给 charset 表**：大小写、
   * 别名（`utf8` / `UTF-8`）、非 UTF 编码都认，**不认识就抛**（`UnsupportedCharsetException`
   * / `IllegalCharsetNameException`），JS 侧 `catch` 接得住。
   * **别兜底成 UTF-8**：静默换编码会让请求体以另一种编码发出去，而对端按声明的编码解，
   * 服务端收到的是乱码却不报错。
   *
   * 走 ktor 的 [io.ktor.utils.io.charsets]：它那套是 **commonMain** 声明（`forName` 是
   * `expect`，JVM 上直接转发 `java.nio.charset.Charset.forName`），所以 `commonMain` 里
   * 不出现平台符号。
   */
  private fun encode(
    input: String,
    to: String?,
  ): ByteArray = Charsets.forName(to ?: "utf-8").newEncoder().encodeToByteArray(input)

  /**
   * [input] 按 [from] 解码成字符串 —— `TextDecoder` 那侧的实现。
   *
   * [from] 的来由与判据与 [encode] 完全一致（同一个 `label` 参数、同一种「不认识就抛」）。
   * 非法字节按 charset 的默认策略**替换**（U+FFFD），不抛。
   *
   * 经 [ByteString] 而不是 decoder：ktor 的 `decode` 收的是 `Source`，而 `ByteString` 是
   * `Source` 的一种 —— ktor 自己在 JVM 上实现那条时走的也是 `ByteString.decodeToString`。
   */
  private fun decode(
    input: ByteArray,
    from: String?,
  ): String = ByteString(input).decodeToString(Charsets.forName(from ?: "utf-8"))

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
   * 后台 WebView 桥 —— 插件 JS 里的 `webview(url, header, script, onInterceptRequest)`。
   *
   * 两处有意的差异：
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
        console("error", listOf("onInterceptRequest 回调出错，按放行处理: $e"))
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
