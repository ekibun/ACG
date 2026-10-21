package soko.ekibun.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

/**
 * `:platform` jvmTest 共用的本地页面服务端：`/page.html` + `/sub.js` + `/sub.png`，页面形状与 `:shared` 那份一致。
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
    /** 1×1 的透明 PNG。 */
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
