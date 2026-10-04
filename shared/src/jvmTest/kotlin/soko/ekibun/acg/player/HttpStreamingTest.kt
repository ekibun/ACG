package soko.ekibun.acg.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import soko.ekibun.TestIo
import soko.ekibun.acg.common.SOCKET_TIMEOUT_MS
import soko.ekibun.ffmpeg.AvFormat
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO` 必须是**流式**的：第一个 `read` 在响应体发完**之前**就该拿到数据。
 *
 * 判据用「第一次读的耗时 vs 整包下发时长」比：服务端把 body 分若干块下发、块间固定间隔，整包要约
 * 「块数 × 每块间隔」ms；第一次读只要**远早于**它（< 一半）就说明没在等整包。走便捷入口
 * `Http.request` 会先整包缓冲（实测见 `silent-failures.md` 的「整包缓冲」一节）。
 */
class HttpStreamingTest {
  /** 分块慢发：写一块、flush、睡一会儿，写完整块数才关。 */
  private class SlowServer(
    private val chunks: Int,
    private val chunkSize: Int,
    private val delayMs: Long,
    /** 第一块之前先憋一会儿（毫秒）—— 用来制造「读正在进行、但它是健康的」那段窗口。 */
    private val firstChunkDelayMs: Long = 0,
  ) : AutoCloseable {
    private val pool = Executors.newFixedThreadPool(2)
    private val stopped = CountDownLatch(1)
    private val block = ByteArray(chunkSize) { (it % 251).toByte() }

    /** 打过来几次请求 —— 「会话有没有被重开」看它。 */
    private val requests = AtomicInteger(0)
    val requestCount: Int get() = requests.get()

    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/media.bin") { serve(it) }
        start()
      }

    val url: String get() = "http://127.0.0.1:${server.address.port}/media.bin"

    private fun serve(exchange: HttpExchange) {
      requests.incrementAndGet()
      exchange.sendResponseHeaders(200, 0)
      val out = exchange.responseBody
      try {
        if (firstChunkDelayMs > 0) Thread.sleep(firstChunkDelayMs)
        repeat(chunks) {
          out.write(block)
          out.flush()
          Thread.sleep(delayMs)
        }
      } catch (_: InterruptedException) {
      } finally {
        runCatching { out.close() }
      }
    }

    override fun close() {
      stopped.countDown()
      server.stop(0)
      pool.shutdownNow()
    }
  }

  @Test(timeout = 60_000)
  fun firstReadReturnsLongBeforeTheWholeBodyArrives() {
    val chunks = 20
    val chunkSize = 4096
    val delayMs = 50L
    val totalMs = chunks * delayMs
    SlowServer(chunks, chunkSize, delayMs).use { server ->
      val io = HttpIO.Handler().open(server.url)
      try {
        val t0 = System.nanoTime()
        val first = TestIo.readBounded(io, chunkSize)
        val firstMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(first > 0, "第一次读应当拿到已到达的那一块，而不是等整包")
        assertTrue(
          firstMs < totalMs / 2,
          "第一次读用了 ${firstMs}ms —— 整包要约 ${totalMs}ms，这就不是流式了",
        )

        // 把剩下的读完，字节数要对得上（流式不等于丢数据）。
        var got = first
        while (got < chunks * chunkSize) got += TestIo.readBounded(io, chunkSize)
        assertEquals(chunks * chunkSize, got, "读完应当正好拿到全部字节")
      } finally {
        io.close()
      }
    }
  }

  /**
   * **没人在等的时候，「本轮作废」（`abort`）不该把会话关掉。**
   *
   * `FFPlayer` 每次 seek / 单帧步进（`-1f` / `+1f`）都要 `takeOverPlayback()` → `abort()`，
   * 而健康的流上那一刻**没有读在等网络**。如果 `abort()` 无条件关响应，每次极小步长操作都会白白
   * 重开一条会话 —— 服务端不认 Range 时更糟：新响应从头给，还得把 `offset` 之前的字节白读白丢一遍。
   *
   * 判据就是请求数：一次读 + 一次空作废 + 再读，服务端只该被打到 **1** 次。
   */
  @Test(timeout = 60_000)
  fun abortWithoutAnyWaitingReadKeepsTheSameSession() {
    SlowServer(chunks = 40, chunkSize = 4096, delayMs = 20).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        assertTrue(TestIo.readBounded(io, 4096) > 0, "第一次读应当拿到数据")
        assertEquals(1, server.requestCount, "第一次读应当只开一条会话")

        // 「本轮作废」—— 但上一次读早就返回了（没有读在等）。
        // 信号只活在一次 read 里，此刻没读在跑 ⇒ abort 是 no-op。
        handler.abort()

        assertTrue(TestIo.readBounded(io, 4096) > 0, "作废之后仍应当能继续读到数据")
        assertEquals(1, server.requestCount, "没人在等时 abort 不该把会话关掉、重发请求")
      } finally {
        io.close()
      }
    }
  }

  /**
   * **正在进行、但是健康的那次读**：abort 会把它叫醒（报 [AvFormat.AVERROR_EXIT]），
   * 但**会话必须保住** —— 会话的去留只看「还出不出字节」（闲置超过 [SOCKET_TIMEOUT_MS]
   * 才换），不是「有读正在进行」。健康的流 300ms 后就把第一块发出来了，闲置远不到阈值。
   *
   * 判据：读被叫醒成 EXIT；请求数仍是 1；撤销作废后下一次读在**同一条会话**上拿到数据。
   */
  @Test(timeout = 60_000)
  fun abortWakesAHealthyReadButKeepsTheSession() {
    val firstChunkDelayMs = 300L
    SlowServer(chunks = 40, chunkSize = 4096, delayMs = 20, firstChunkDelayMs = firstChunkDelayMs).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader =
          Thread { result.set(io.read(ByteArray(4096))) }.apply {
            isDaemon = true
            start()
          }
        // 服务端还没发第一块，所以此刻它确实在「进行中」。
        Thread.sleep(100)
        assertTrue(reader.isAlive, "此刻应当在读里")

        handler.abort()
        reader.join(5_000)
        assertTrue(!reader.isAlive, "abort 必须把等待中的读叫醒")
        assertEquals(
          AvFormat.AVERROR_EXIT,
          result.get(),
          "被作废的读应当报 AVERROR_EXIT（不是 -1，也不是 EOF）",
        )

        // 下一次 read 自带全新信号，不会再被上次的 abort 影响。
        assertTrue(TestIo.readBounded(io, 4096) > 0, "作废之后应当能继续读到数据")
        assertEquals(1, server.requestCount, "健康会话不该被 abort 拆掉、重发请求")
      } finally {
        io.close()
      }
    }
  }
}
