package soko.ekibun.common

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * `:platform` jvmTest 的本地服务端：`/media` 一条流式响应（档位由参数选），`pages` 挂一个空 `/page.html`。
 * 与 `:shared` 那份互不约束，各按本模块用例的最小需要写。
 */
internal class TestServer(
  private val body: ByteArray = ByteArray(0),
  /** false = 分块传输（不发 `Content-Length`，响应头带 `Transfer-Encoding: chunked`）。 */
  private val declareLength: Boolean = true,
  private val chunkSize: Int = 4096,
  /** 每块写完后睡多久（慢发档）。 */
  private val chunkDelayMs: Long = 0,
  /** body 发完不关连接，每 [HEARTBEAT_MS] 续 1 字节 —— 客户端撒手时服务端才察觉得到。 */
  private val heartbeat: Boolean = false,
  /** 是否挂页面路由。 */
  private val pages: Boolean = false,
) : AutoCloseable {
  private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
  private val requestCount = AtomicInteger(0)
  private val enteredCount = AtomicInteger(0)
  private val exitedCount = AtomicInteger(0)
  private val servedBytes = AtomicLong(0)
  private val stopped = CountDownLatch(1)

  /** 空页面只提供能设 cookie 的 http origin；子资源属于 `:shared` 的拦截判据。 */
  private val pageFiles: Map<String, Pair<String, ByteArray>> =
    mapOf(
      "/page.html" to
        (
          "text/html; charset=utf-8" to
            "<!doctype html><html><head><title>acg-test</title></head><body></body></html>".toByteArray()
        ),
    )

  init {
    server.createContext("/media") { handle(it) }
    if (pages) pageFiles.forEach { (path, spec) -> server.createContext(path) { it.respond(spec.first, spec.second) } }
    server.executor = Executors.newCachedThreadPool()
    server.start()
  }

  /** 媒体入口：`/media`（注意与 [url] 区分，那个是拼任意路径）。 */
  val mediaUrl: String get() = "http://127.0.0.1:$port/media"

  val port: Int get() = server.address.port

  fun url(path: String): String = "http://127.0.0.1:$port$path"

  /** 打过来几次请求 —— 「会话有没有被重开」看它。 */
  val requests: Int get() = requestCount.get()

  /** 真正写到 socket 上的正文字节数（不含响应头与心跳字节）。 */
  val served: Long get() = servedBytes.get()

  /** 进场的 handler 数。 */
  val entered: Int get() = enteredCount.get()

  /** 出场的 handler 数。 */
  val exited: Int get() = exitedCount.get()

  private fun handle(exchange: HttpExchange) {
    requestCount.incrementAndGet()
    enteredCount.incrementAndGet()
    try {
      exchange.sendResponseHeaders(200, if (declareLength) body.size.toLong() else 0L)
      val out = exchange.responseBody
      try {
        writeBody(out)
        if (heartbeat) {
          while (stopped.count != 0L) {
            out.write(1)
            out.flush()
            Thread.sleep(HEARTBEAT_MS)
          }
        }
      } finally {
        runCatching { out.close() }
      }
    } catch (_: Exception) {
      // 客户端放掉了
    } finally {
      exitedCount.incrementAndGet()
    }
  }

  private fun writeBody(out: OutputStream) {
    var position = 0
    while (position < body.size) {
      val n = minOf(chunkSize, body.size - position)
      out.write(body, position, n)
      out.flush()
      servedBytes.addAndGet(n.toLong())
      position += n
      if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
    }
  }

  /** 统一的响应出口：类型 + 字节，200。 */
  private fun HttpExchange.respond(
    contentType: String,
    body: ByteArray,
  ) {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(200, body.size.toLong())
    responseBody.use { it.write(body) }
  }

  override fun close() {
    stopped.countDown()
    server.stop(0)
  }

  private companion object {
    const val HEARTBEAT_MS = 50L
  }
}
