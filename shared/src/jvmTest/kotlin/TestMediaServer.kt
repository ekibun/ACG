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
 * 本地媒体服务端：jvmTest 的 HTTP 用例与 `DevServerKt`（dev 调试进程）共用同一份实现。
 *
 * 行为由构造参数决定，用例按需选档：
 * - [honorRange] = false：一律 200 从 0 整发 —— 「服务端不认 Range，seek 只能往前推」的形态；
 * - [declareLength] = false：分块传输、不声明 Content-Length —— 「长度未知」的流。声明固定长度
 *   却只发一部分会变成「长度对不上」（客户端那边是 `ClosedByteChannelException`），也不是「卡住」；
 * - [chunkDelayMs] / [bytesPerSecond]：两种发送节奏 —— 按块慢发（消费快于生产，通道恒空）与
 *   按「已发字节数应耗时」的预算限速（复现弱网）；给了 [bytesPerSecond] 就忽略块间睡；
 * - [heartbeat]：body 发完不关、每 [HEARTBEAT_MS]ms 续 1 字节 —— 客户端撒手时服务端要**尽快**
 *   察觉到（否则 exited 永远追不上 entered），而「读到流尾就算完」的客户端会一直挂在心跳上；
 * - [frozen]：冻结 —— 在途响应立刻停笔、新请求只发响应头就停住，且**不 close**（close 等于
 *   告诉客户端 body 结束，那叫「截断」不叫「卡住」）。「播放中途断网」的现场就靠它。
 *
 * 计数器是判据的一部分：[requests]（开过几条会话 —— 「换了几次会话」的直接证据）、
 * [entered] / [exited]（handler 进出场，exited < entered 即有连接被漏下）、
 * [served]（真写出的字节数 —— 「网络停没停」的公证人）。
 *
 * 线程池用**可缓存**而非固定：冻结期间挂住的 handler 会一直占着线程，而 HttpIO 的重试还会发
 * 新请求 —— 固定池会被挂死的那些吃光，新请求根本进不来。
 *
 * [control] = true 时多开 `/__control` 端点（只有 dev 用）：`/freeze`、`/resume`、
 * `/throttle?bps=<n>`（0 = 解除限速）、`/status`（只读，控制条轮询用）、
 * `/shutdown`（结束 dev 进程），播放中途热改行为。
 */
internal class TestMediaServer(
  /** 响应体。dev 场景可能是一整部视频（读进内存）；测试场景都是几 MB 的切片。 */
  private val body: ByteArray,
  /** 端口。0 = ephemeral（测试）；dev 给固定端口 —— App 里要手填 URL。 */
  port: Int = 0,
  /** 每块多大。 */
  private val chunkSize: Int = 4096,
  /** 每块写完后睡多久（慢发档）。 */
  private val chunkDelayMs: Long = 0,
  /** 按「已发字节数应耗时」的预算限速（字节/秒）；null = 不限。dev 可经 /__control 热改。 */
  @Volatile var bytesPerSecond: Long? = null,
  /** false = 完全忽略 Range 头，一律 200 从 0 整发。 */
  private val honorRange: Boolean = true,
  /** false = 分块传输（`sendResponseHeaders(_, 0)`），不声明 Content-Length。 */
  private val declareLength: Boolean = true,
  /** body 发完后是否用心跳续着（只有分块传输的档有意义）。 */
  private val heartbeat: Boolean = false,
  /** 第一块之前先憋多久（毫秒）—— 用来制造「读正在进行、但它是健康的」那段窗口。 */
  private val firstChunkDelayMs: Long = 0,
  /** 是否开 /__control 控制端点。 */
  private val control: Boolean = false,
  /**
   * 累计写出到这么多字节就停笔（跨请求累计）。null = 不限。
   *
   * **停笔等于「卡住」而不是「截断」**：写到上限后既不 close、也不结束会话，只挂在 [parked] 上
   * 等 [close] 收场 —— 客户端读到的是「数据在路上但永远不来」。
   *
   * 为什么是**累计字节**而不是「第几次请求」或「响应内偏移」：avformat 读 MP4 **不是单调前进**
   * 的 —— 实测它会连发几条 range 请求（先拿全量、再回退重读索引附近），按请求数切的位置与
   * 「数据发到几分之几」根本对不上。累计字节才是「服务器一共发出去多少」这件事本身，也就是
   * 断网现场里那个「发到一半」的量。
   *
   * 上限要**高于开流所需**（`avformat_find_stream_info` 得拿到索引才返回；低于它连开流都
   * 过不去，测到的是「压根打不开流」），又**明显低于素材总长** —— 可行区间由调用方按素材
   * 实测定（取值示范见 `FFPlayerStallMidPlaybackTest` 的 `SERVED_LIMIT`）。
   */
  private val servedLimit: Long? = null,
) : AutoCloseable {
  /** 冻结 —— 在途响应停笔、新请求只发响应头就停住（类 KDoc 的 frozen 档）。dev 可经 /__control 热改。 */
  @Volatile var frozen: Boolean = false

  private val requestCount = AtomicInteger(0)
  private val enteredCount = AtomicInteger(0)
  private val exitedCount = AtomicInteger(0)
  private val servedBytes = AtomicLong(0)

  /** 打过来几次请求 —— 「会话有没有被重开」看它。 */
  val requests: Int get() = requestCount.get()

  /** 进场的 handler 数。 */
  val entered: Int get() = enteredCount.get()

  /** 出场的 handler 数 —— 与 [entered] 相等才说明连接都被放掉了。 */
  val exited: Int get() = exitedCount.get()

  /** 真正写到 socket 上的字节数（不含响应头与心跳字节）。 */
  val served: Long get() = servedBytes.get()

  /** 冻结期间攥着的响应体 —— [close] 时统一收掉，别让 handler 线程吊死。 */
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

  private fun handle(exchange: HttpExchange) {
    requestCount.incrementAndGet()
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
      // 已经发满上限：这条会话只发响应头，正文一个字节都不写（见 [servedLimit]）。
      val cutOff = servedLimit != null && servedBytes.get() >= servedLimit
      val status = if (rangeStart > 0) 206 else 200
      if (status == 206) {
        exchange.responseHeaders.add("Content-Range", "bytes $rangeStart-${body.size - 1}/${body.size}")
      }
      exchange.sendResponseHeaders(status, if (declareLength) (body.size - rangeStart).toLong() else 0L)
      val out = exchange.responseBody
      try {
        if (firstChunkDelayMs > 0) Thread.sleep(firstChunkDelayMs)
        // 越过断点：响应头照发（客户端得先认下这条会话），body 一个字节都不写、也**不 close**
        // —— close 等于告诉客户端 body 结束（那是「截断」），而这里要制造的是「数据永远不来」。
        if (cutOff) {
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
        // 收场（[stopped] 已放行）时 parked 的流已由 close() 关掉；正常发完的这条要自己关。
        // 判据用 parked 里有没有它，而不是 cutOff —— writeBody 中途到上限时也会 park。
        if (!parked.contains(out)) runCatching { out.close() }
      }
    } catch (_: Exception) {
      // Broken pipe / reset == 客户端放掉了 —— 这正是要计的「出场」。
    } finally {
      exitedCount.incrementAndGet()
    }
  }

  /**
   * 停笔并把响应体挂在 [parked] 上，等 [close] 统一收掉。
   *
   * 挂住期间 handler 线程一直占着（线程池是**可缓存**的，就为了这个）—— 不挂住的话 `finally`
   * 会立刻 close，客户端读到的就是「流结束」，那是截断不是卡住。
   */
  private fun parkForever(out: OutputStream) {
    parked += out
    while (stopped.count != 0L) Thread.sleep(20)
  }

  /**
   * 这条响应的正文。写到 [servedLimit] 就**停笔**（不 close —— close 是「截断」，这里要的是
   * 「卡住」）：本方法返回后由 [handle] 把它挂到 [parked] 上，等 [close] 统一收场。
   */
  private fun writeBody(
    out: OutputStream,
    from: Int,
  ) {
    var position = from
    // 限速基准按**本条响应**算：每条 range 请求从头计时，客户端感知到的就是一个恒定速率的流。
    val startedAt = System.nanoTime()
    var sentHere = 0L
    while (position < body.size) {
      if (!awaitWhileFrozen(out)) break
      val limit = servedLimit
      if (limit != null && servedBytes.get() >= limit) {
        parkForever(out)
        return
      }
      var n = minOf(chunkSize, body.size - position)
      // 一次不许越过上限：断点落在块中间时只发到上限为止。
      if (limit != null) n = minOf(n, (limit - servedBytes.get()).toInt().coerceAtLeast(1))
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
    // 停笔。解冻（frozen 回 false）就接着发；close() 则 countDown 把它放出去。
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
        // 先回状态再退：留 300ms 给响应冲刷，然后结束整个 dev 进程（curl 走这里）。
        Thread {
          Thread.sleep(300)
          System.exit(0)
        }.apply {
          isDaemon = false
          start()
        }

      // `/__control/status` 不设分支 —— 无动作可改，落到下面统一回状态文本（控制条轮询用，只读）。
    }
    val bpsText = bytesPerSecond?.takeIf { it > 0 }?.toString() ?: "unlimited"
    val state = "requests=$requests served=$served frozen=$frozen bps=$bpsText\n"
    val bytes = state.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  /** 幂等：测试体与 `use` 各会调一次。 */
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
