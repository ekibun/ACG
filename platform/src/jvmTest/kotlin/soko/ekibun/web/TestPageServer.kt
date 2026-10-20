package soko.ekibun.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

/**
 * `:platform` jvmTest 共用的**本地页面服务端**：`/page.html` + `/sub.js` + `/sub.png`。
 *
 * 与 `:shared` 那边的 `TestPageServer` 是同形实现，不能共用一个类 —— `:platform` 的测试
 * **不该反向依赖 `:shared`**（依赖方向反了）。两边各自一份，但**PNG 与页面形状必须一致**：
 * 以前这里和 `Webview2Test` 各有一份，PNG 的 base64 还不一样，改一处漏一处就是隐性的假红。
 *
 * 线程池用可缓存：被挂住的 handler 会一直占线程，固定池会被吃光。
 */
internal class TestPageServer(
  /** 额外挂的资源路径 → (contentType, body)；`/page.html`、`/sub.js`、`/sub.png` 已内置。 */
  private val extra: Map<String, Pair<String, ByteArray>> = emptyMap(),
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
      createContext("/page.html") { it.respond("text/html; charset=utf-8", pageHtml.toByteArray()) }
      createContext("/sub.js") {
        it.respond("application/javascript", "window.__subLoaded = true;".toByteArray())
      }
      createContext("/sub.png") { it.respond("image/png", Base64.getDecoder().decode(PNG_1X1)) }
      extra.forEach { (path, spec) -> createContext(path) { it.respond(spec.first, spec.second) } }
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
