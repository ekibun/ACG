package soko.ekibun.acg.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

/**
 * WebView / fetch 用例共用的**本地页面服务端**：`/page.html` + `/sub.js` + `/sub.png`，
 * 可选 `/data.json`。
 *
 * 为什么抽出来：`JsEngineDispatchTest` / `WebViewHostTest` / `:platform` 的 `Webview2Test`、
 * `WebViewCookieManagerTest` 四个文件各抄了一份起停代码与同一个 1×1 PNG 常量，
 * 其中两份 PNG 的 base64 **还不一样**（改一处漏一处就是隐性的假红）。
 *
 * 页面上一定带一个 `<script src>`：拦资源比拦主框架有信息量 —— `isForMainFrame` 必须是 `false`，
 * 误命中会把页面整个拦掉、连脚本都执行不了。
 *
 * 线程池用可缓存而非固定：拦截用例里 handler 可能被挂住，固定池会被吃光、新请求进不来。
 */
internal class TestPageServer(
  /** 额外挂一个 `/data.json`（`fetch` 用例要它）。 */
  private val withJsonEndpoint: Boolean = false,
  /** 主框架请求到达时的回调（用例用它记录 header）。 */
  private val onMainFrame: ((HttpExchange) -> Unit)? = null,
  /** 响应体写出前的钩子（用例用它做日志）。 */
  private val onRespond: ((HttpExchange) -> Unit)? = null,
) : AutoCloseable {
  private val pageHtml =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>acg-test</title>
    <script src="/sub.js"></script></head>
    <body><img src="/sub.png"><p>hello</p></body></html>
    """.trimIndent()

  private val server =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      executor = Executors.newCachedThreadPool()
      createContext("/page.html") { exchange ->
        onMainFrame?.invoke(exchange)
        onRespond?.invoke(exchange)
        exchange.respond("text/html; charset=utf-8", pageHtml.toByteArray())
      }
      createContext("/sub.js") {
        it.respond("application/javascript", "window.__subLoaded = true;".toByteArray())
      }
      createContext("/sub.png") { it.respond("image/png", Base64.getDecoder().decode(PNG_1X1)) }
      if (withJsonEndpoint) {
        createContext("/data.json") {
          it.respond("application/json; charset=utf-8", """{"a":1}""".toByteArray())
        }
      }
      start()
    }

  val port: Int get() = server.address.port

  fun url(path: String): String = "http://127.0.0.1:$port$path"

  override fun close() {
    server.stop(0)
  }

  private companion object {
    /** 1×1 的透明 PNG。用真的图，免得 WebView2 那边报解码错误干扰日志。 */
    const val PNG_1X1 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
  }
}

/** 统一的响应出口：类型 + 字节，200。 */
internal fun HttpExchange.respond(
  contentType: String,
  body: ByteArray,
) {
  responseHeaders.add("Content-Type", contentType)
  sendResponseHeaders(200, body.size.toLong())
  responseBody.use { it.write(body) }
}
