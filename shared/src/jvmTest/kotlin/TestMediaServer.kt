package soko.ekibun

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地媒体服务端：行为由构造参数选档（Range、长度声明、慢发、限速、心跳、冻结、首响应挂起、按字节掐断）。
 */
internal class TestMediaServer(
  /** 响应体。 */
  private val body: ByteArray,
  /** 端口。0 = ephemeral（测试）；dev 给固定端口。 */
  port: Int = 0,
  /** 每块多大。 */
  private val chunkSize: Int = 4096,
  /** 每块写完后睡多久（慢发档）。 */
  private val chunkDelayMs: Long = 0,
  /** 按「已发字节数应耗时」的预算限速（字节/秒）；null = 不限，dev 可经 /__control 热改。 */
  @Volatile var bytesPerSecond: Long? = null,
  /** false = 完全忽略 Range 头，一律 200 从 0 整发。 */
  private val honorRange: Boolean = true,
  /** false = 分块传输（`sendResponseHeaders(_, 0)`），不声明 Content-Length。 */
  private val declareLength: Boolean = true,
  /** body 发完后是否用心跳续着（只有分块传输的档有意义）。 */
  private val heartbeat: Boolean = false,
  /** 第一块之前先憋多久（毫秒）。 */
  private val firstChunkDelayMs: Long = 0,
  /** 是否开 /__control 控制端点。 */
  private val control: Boolean = false,
  /** 累计写到这么多字节就停笔（跨请求累计）；null = 不限。 */
  private val servedLimit: Long? = null,
  /** 只让第一条响应停住（只发响应头、body 不给），后续请求正常发完。 */
  private val hangFirstResponse: Boolean = false,
  /** 发到这么多字节就掐断连接（`close()` 响应体但不给足长度）。null = 不掐。 */
  private val cutAfterBytes: Int? = null,
) : AutoCloseable {
  /** 冻结：在途响应停笔、新请求只发响应头。dev 可经 `/__control` 热改。 */
  @Volatile var frozen: Boolean = false

  private val requestCount = AtomicInteger(0)
  private val enteredCount = AtomicInteger(0)
  private val exitedCount = AtomicInteger(0)
  private val servedBytes = AtomicLong(0)

  /** 打过来几次请求 —— 「会话有没有被重开」看它。 */
  val requests: Int get() = requestCount.get()

  /** 进场的 handler 数。 */
  val entered: Int get() = enteredCount.get()

  /** 出场的 handler 数。 */
  val exited: Int get() = exitedCount.get()

  /** 真正写到 socket 上的字节数（不含响应头与心跳字节）。 */
  val served: Long get() = servedBytes.get()

  /** 冻结期间攥着的响应体，[close] 时统一收掉。 */
  private val parked = Collections.synchronizedList(mutableListOf<OutputStream>())

  private val stopped = CountDownLatch(1)
  private val pool = Executors.newCachedThreadPool()

  private val server =
    HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
      executor = pool
      createContext("/media") { handle(it) }
      if (control) createContext("/__control") { handleControl(it) }
      start()
    }

  val url: String get() = "http://127.0.0.1:${server.address.port}/media"

  /** 第一条响应已经只发头就停住。 */
  val firstResponseHanging = CountDownLatch(1)

  private fun handle(exchange: HttpExchange) {
    val index = requestCount.getAndIncrement()
    enteredCount.incrementAndGet()
    try {
      val rangeStart =
        exchange.requestHeaders
          .getFirst("Range")
          ?.takeIf { honorRange }
          ?.removePrefix("bytes=")
          ?.substringBefore('-')
          ?.toIntOrNull()
          ?.coerceIn(0, body.size)
          ?: 0
      // 已发满上限：只发响应头，正文不写
      val cutOff = servedLimit != null && servedBytes.get() >= servedLimit
      val hangFirst = hangFirstResponse && index == 0
      val status = if (rangeStart > 0) 206 else 200
      if (status == 206) {
        exchange.responseHeaders.add("Content-Range", "bytes $rangeStart-${body.size - 1}/${body.size}")
      }
      // 挂住第一条时用分块传输：声明了固定长度却不发，客户端读到的是连接异常
      val declaredLength = if (declareLength && !hangFirst) (body.size - rangeStart).toLong() else 0L
      exchange.sendResponseHeaders(status, declaredLength)
      val out = exchange.responseBody
      try {
        if (firstChunkDelayMs > 0) Thread.sleep(firstChunkDelayMs)
        // 越过断点：响应头照发，body 不写、也不 close
        if (cutOff || hangFirst) {
          if (hangFirst) firstResponseHanging.countDown()
          parkForever(out)
        } else {
          writeBody(out, rangeStart)
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

  /** 停笔并把响应体挂在 [parked] 上，等 [close] 统一收掉。 */
  private fun parkForever(out: OutputStream) {
    parked += out
    while (stopped.count != 0L) Thread.sleep(20)
  }

  /** 写正文；到 [servedLimit] 就停笔交给 [parked]。 */
  private fun writeBody(
    out: OutputStream,
    from: Int,
  ) {
    var position = from
    // 限速基准按本条响应算：每条 range 请求从头计时
    val startedAt = System.nanoTime()
    var sentHere = 0L
    // 掐断用 out.close() 这个信号，handle 的 finally 再关一次也无害
    val cutAt = cutAfterBytes
    while (position < body.size) {
      if (!awaitWhileFrozen(out)) break
      val limit = servedLimit
      if (limit != null && servedBytes.get() >= limit) {
        parkForever(out)
        return
      }
      if (cutAt != null && sentHere >= cutAt) {
        runCatching { out.close() }
        return
      }
      var n = minOf(chunkSize, body.size - position)
      // 一次不越过上限
      if (limit != null) n = minOf(n, (limit - servedBytes.get()).toInt().coerceAtLeast(1))
      if (cutAt != null) n = minOf(n, (cutAt - sentHere).toInt().coerceAtLeast(1))
      out.write(body, position, n)
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

  private fun handleControl(exchange: HttpExchange) {
    val uri = exchange.requestURI
    when (uri.path) {
      "/__control/freeze" -> frozen = true
      "/__control/resume" -> frozen = false
      "/__control/throttle" -> {
        val bps =
          uri.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("bps=") }
            ?.removePrefix("bps=")
            ?.toLongOrNull()
        if (bps != null) bytesPerSecond = bps.takeIf { it > 0 }
      }

      "/__control/shutdown" ->
        // 先回状态再退，留 300ms 给响应冲刷
        Thread {
          Thread.sleep(300)
          System.exit(0)
        }.apply {
          isDaemon = false
          start()
        }

      // /__control/status 无动作，落到下面统一回状态文本
    }
    val bpsText = bytesPerSecond?.takeIf { it > 0 }?.toString() ?: "unlimited"
    val state = "requests=$requests served=$served frozen=$frozen bps=$bpsText\n"
    val bytes = state.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  /** 幂等。 */
  override fun close() {
    stopped.countDown()
    parked.forEach { runCatching { it.close() } }
    server.stop(0)
    pool.shutdownNow()
  }

  private companion object {
    const val HEARTBEAT_MS = 50L
  }
}
