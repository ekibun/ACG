package soko.ekibun

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * `:shared` jvmTest 的本地服务端：`/media` 流式档位（Range、慢发、限速、心跳、冻结）、
 * `pages` 挂页面与子资源、`routes` 给 dev 挂 `/__control`。与 `:platform` 那份互不约束。
 */
internal class TestServer(
  /** `/media` 的兜底响应体：URL 没命中分包里的文件时整发它。 */
  private val body: ByteArray = ByteArray(0),
  /** 端口。0 = ephemeral（测试）；dev 给固定端口。 */
  port: Int = 0,
  /** 每块多大。 */
  private val chunkSize: Int = 4096,
  /** 每块写完后睡多久（慢发档）。 */
  private val chunkDelayMs: Long = 0,
  /** 按「已发字节数应耗时」的预算限速（字节/秒）；null = 不限，dev 可经 `/__control` 热改。 */
  @Volatile var bytesPerSecond: Long? = null,
  /** false = 完全忽略 Range 头，一律 200 从 0 整发。 */
  private val honorRange: Boolean = true,
  /** false = 分块传输（`sendResponseHeaders(_, 0)`），不声明 Content-Length。 */
  private val declareLength: Boolean = true,
  /** body 发完后是否用心跳续着（只有分块传输的档有意义）。 */
  private val heartbeat: Boolean = false,
  /** 第一块之前先憋多久（毫秒）。 */
  private val firstChunkDelayMs: Long = 0,
  /** 额外路由 → 处理器（dev 的 `/__control` 就挂在这里；测试一般不用）。 */
  private val routes: Map<String, (HttpExchange) -> Unit> = emptyMap(),
  /** 只让第一条响应停住（只发响应头、body 不给），后续请求正常发完。 */
  private val hangFirstResponse: Boolean = false,
  /** 是否挂页面路由。 */
  private val pages: Boolean = false,
  /** 额外挂 `/echo`：把收到的请求形状记进 [echo]（`fetch` 的请求侧判据要它）。 */
  private val echoEndpoint: Boolean = false,
) : AutoCloseable {
  /** 冻结：在途响应停笔、新请求只发响应头。dev 可经 `/__control` 热改。 */
  @Volatile var frozen: Boolean = false

  private val requestCount = AtomicInteger(0)
  private val enteredCount = AtomicInteger(0)
  private val exitedCount = AtomicInteger(0)
  private val servedBytes = AtomicLong(0)

  /** `pages` 只挂本模块用例读到的文件：`sub.js`（拦截命中）、`sub.png`、`data.json`。 */
  private val pageFiles: Map<String, Pair<String, ByteArray>> =
    mapOf(
      "/page.html" to
        (
          "text/html; charset=utf-8" to
            """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>acg-test</title>
            <script src="/sub.js"></script></head>
            <body><img src="/sub.png"><p>hello</p></body></html>
            """.trimIndent().toByteArray()
        ),
      "/sub.js" to ("application/javascript" to "window.__subLoaded = true;".toByteArray()),
      "/sub.png" to ("image/png" to Base64.getDecoder().decode(PNG_1X1)),
      "/data.json" to ("application/json; charset=utf-8" to """{"a":1}""".toByteArray()),
    )

  /** 打过来几次请求 —— 「会话有没有被重开」看它。 */
  val requests: Int get() = requestCount.get()

  /** 进场的 handler 数。 */
  val entered: Int get() = enteredCount.get()

  /** 出场的 handler 数。 */
  val exited: Int get() = exitedCount.get()

  /** 真正写到 socket 上的字节数（不含响应头与心跳字节）。 */
  val served: Long get() = servedBytes.get()

  /** 冻结时停笔的响应体。只在 `close` 里读，那时 server 与线程池已停 —— 没有写方，用普通 `ArrayList`。 */
  private val parked = ArrayList<OutputStream>()

  private val stopped = CountDownLatch(1)
  private val pool = Executors.newCachedThreadPool()

  private val server =
    HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
      executor = pool
      createContext("/media") { handle(it) }
      if (pages) pageFiles.forEach { (path, spec) -> createContext(path) { it.respond(spec.first, spec.second) } }
      if (echoEndpoint) {
        createContext("/echo") { exchange ->
          echo.set(
            Echo(
              method = exchange.requestMethod,
              contentType = exchange.requestHeaders.getFirst("Content-Type"),
              body = exchange.requestBody.readBytes().decodeToString(),
            ),
          )
          exchange.respond("application/json; charset=utf-8", """{"title":"ok"}""".toByteArray())
        }
      }
      routes.forEach { (path, handler) -> createContext(path) { handler(it) } }
      start()
    }

  /** `/echo` 收到的请求形状：方法、`Content-Type`、body。 */
  data class Echo(
    val method: String,
    val contentType: String?,
    val body: String,
  )

  /** 最近一次打到 `/echo` 的请求；没开 [echoEndpoint] 时恒为 null。 */
  val echo = AtomicReference<Echo?>(null)

  /** 媒体入口：`/media`（注意与 [url] 区分，那个是拼任意路径）。 */
  val mediaUrl: String get() = "http://127.0.0.1:$port/media"

  /**
   * 带随机 query 的 `/media` 入口。`HttpCache` 是进程级的、键是完整 URL，端口却是 ephemeral；
   * 不带后缀会撞上上一个用例的缓存。走 `HttpIO` 的用例一律用它。
   */
  val caseUrl: String by lazy { "$mediaUrl?case=${java.util.UUID.randomUUID().toString().take(8)}" }

  val port: Int get() = server.address.port

  fun url(path: String): String = "http://127.0.0.1:$port$path"

  /** 第一条响应已经只发头就停住。 */
  val firstResponseHanging = CountDownLatch(1)

  private fun handle(exchange: HttpExchange) {
    val index = requestCount.getAndIncrement()
    enteredCount.incrementAndGet()
    try {
      val payload = resolveFile(exchange.requestURI.path) ?: body
      val rangeStart =
        exchange.requestHeaders
          .getFirst("Range")
          ?.takeIf { honorRange }
          ?.removePrefix("bytes=")
          ?.substringBefore('-')
          ?.toIntOrNull()
          ?.coerceIn(0, payload.size)
          ?: 0
      val hangFirst = hangFirstResponse && index == 0
      val status = if (rangeStart > 0) 206 else 200
      if (status == 206) {
        exchange.responseHeaders.add(
          "Content-Range",
          "bytes $rangeStart-${payload.size - 1}/${payload.size}",
        )
      }
      // 挂住第一条时用分块传输：声明了固定长度却不发，客户端读到的是连接异常
      val declaredLength = if (declareLength && !hangFirst) (payload.size - rangeStart).toLong() else 0L
      exchange.sendResponseHeaders(status, declaredLength)
      val out = exchange.responseBody
      try {
        if (firstChunkDelayMs > 0) Thread.sleep(firstChunkDelayMs)
        if (hangFirst) {
          firstResponseHanging.countDown()
          parkForever(out)
        } else {
          writeBody(out, payload, rangeStart)
          if (heartbeat) {
            while (stopped.count != 0L) {
              out.write(1)
              out.flush()
              Thread.sleep(HEARTBEAT_MS)
            }
          }
        }
      } finally {
        // 收场时 parked 的流已由 close() 关掉；正常发完的这条自己关
        if (!parked.contains(out)) runCatching { out.close() }
      }
    } catch (_: Exception) {
      // 客户端放掉了
    } finally {
      exitedCount.incrementAndGet()
    }
  }

  /** `/media/<相对路径>` 命中分包里的素材就返回它的字节（HLS 分片、播放列表）；否则 null（回落成 [body]）。 */
  private fun resolveFile(path: String?): ByteArray? {
    val relative =
      path?.removePrefix("/media")?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return null
    if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    return runCatching {
      TestMedia::class.java.classLoader.getResourceAsStream(relative)?.use {
        it.readBytes()
      }
    }.getOrNull()
  }

  /** 停笔并把响应体挂在 [parked] 上，等 [close] 统一收掉。 */
  private fun parkForever(out: OutputStream) {
    parked += out
    while (stopped.count != 0L) Thread.sleep(20)
  }

  /** 写正文；被冻结时停笔交给 [parked]。 */
  private fun writeBody(
    out: OutputStream,
    payload: ByteArray,
    from: Int,
  ) {
    var position = from
    // 限速基准按本条响应算：每条 range 请求从头计时
    val startedAt = System.nanoTime()
    var sentHere = 0L
    while (position < payload.size) {
      if (!awaitWhileFrozen(out)) break
      val n = minOf(chunkSize, payload.size - position)
      out.write(payload, position, n)
      out.flush()
      position += n
      servedBytes.addAndGet(n.toLong())
      sentHere += n
      pace(sentHere, startedAt)
    }
  }

  /** 冻结时停笔；false = 服务端正在收场（[stopped] 已放行），调用方该 break 了。 */
  private fun awaitWhileFrozen(out: OutputStream): Boolean {
    if (!frozen) return true
    parked += out
    // 解冻就接着发；close() 放行
    while (stopped.count != 0L && frozen) Thread.sleep(20)
    return stopped.count != 0L
  }

  /** 按档位睡：块间慢发优先，其次按「已发字节数应耗时」的预算限速 —— 差多少就补睡多少。 */
  private fun pace(
    sentHere: Long,
    startedAt: Long,
  ) {
    val bps = bytesPerSecond
    when {
      chunkDelayMs > 0 -> Thread.sleep(chunkDelayMs)
      bps != null -> {
        val shouldHaveTakenMs = sentHere * 1000L / bps
        val actuallyTakenMs = (System.nanoTime() - startedAt) / 1_000_000L
        val behind = shouldHaveTakenMs - actuallyTakenMs
        if (behind > 0) Thread.sleep(behind)
      }
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

  /** 幂等。 */
  override fun close() {
    stopped.countDown()
    // 先停 listener 与 handler 线程（之后不会再有新请求往 parked 里塞），再收残留的流
    server.stop(0)
    pool.shutdownNow()
    parked.forEach { runCatching { it.close() } }
  }

  private companion object {
    const val HEARTBEAT_MS = 50L

    /** 1×1 的透明 PNG。 */
    const val PNG_1X1 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
  }
}
