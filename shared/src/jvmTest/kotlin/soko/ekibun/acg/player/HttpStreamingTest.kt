package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO` 必须是**流式**的：第一个 `read` 在响应体发完**之前**就该拿到数据。
 *
 * 判据用「第一次读的耗时 vs 整包下发时长」比：服务端把 body 分若干块下发、块间固定间隔，整包要约
 * 「块数 × 每块间隔」ms；第一次读只要**远早于**它（< 一半）就说明没在等整包。整包缓冲是这层最容易
 * 退回去的形态（实测见 `docs/development/playback-invariants.md` 的「流式读取」一节）。
 *
 * 一次**可能永久阻塞**的读走 `readBounded`（守护线程 + join 超时，定义见 `TestSurface.kt`）：
 * 测试不许被它吊死，用例再带 `@Test(timeout = …)` 兜一层。
 */
class HttpStreamingTest {
  @Test(timeout = 60_000)
  fun firstReadReturnsLongBeforeTheWholeBodyArrives() {
    // 整包下发期 = 块数 × 块间隔。判据是「首读 < 一半」，所以块数/间隔只要够区分就行 ——
    // 20 块 × 50ms 那种一个整秒的下发期是白等（实测 1.2 s 里 1 s 是这里）。
    val chunks = 6
    val chunkSize = 4096
    val delayMs = 30L
    val totalMs = chunks * delayMs
    TestMediaServer(
      body = ByteArray(chunks * chunkSize) { (it % 251).toByte() },
      chunkDelayMs = delayMs,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val io = HttpIO.Handler().open(server.url)
      try {
        val t0 = System.nanoTime()
        val first = io.readBounded(chunkSize)
        val firstMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(first > 0, "the first read should get the arrived chunk, not wait for the whole body")
        assertTrue(
          firstMs < totalMs / 2,
          "the first read took ${firstMs}ms -- the whole body takes about ${totalMs}ms, this is not streaming",
        )

        // 把剩下的读完，字节数要对得上（流式不等于丢数据）。
        var got = first
        while (got < chunks * chunkSize) got += io.readBounded(chunkSize)
        assertEquals(chunks * chunkSize, got, "reading to the end should yield exactly all the bytes")
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
    TestMediaServer(
      body = ByteArray(40 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        assertTrue(io.readBounded(4096) > 0, "the first read should get data")
        assertEquals(1, server.requests, "the first read should open exactly one session")

        // 「本轮作废」—— 但上一次读早就返回了（没有读在等）。
        // 信号只活在一次 read 里，此刻没读在跑 ⇒ abort 是 no-op。
        handler.abort()

        assertTrue(io.readBounded(4096) > 0, "reads should still work after the abort")
        assertEquals(1, server.requests, "abort with nobody waiting must not drop the session and resend")
      } finally {
        io.close()
      }
    }
  }

  /**
   * **正在进行、但是健康的那次读**：abort 会把它叫醒（报「读满 buf.size」的正数），代价是
   * **那条会话跟着没了** —— 叫醒一次阻塞在 socket 上的读，唯一的手段就是关掉那条连接
   * （见 `docs/development/playback-invariants.md` 第五节「私有作废信号」）。
   *
   * 健康不健康在这里救不了它：被掀的那一刻，字节还没从连接上取出来。所以判据是「叫醒 + 重发」：
   * 请求数变成 2，且下一次读**在新会话上**拿到正确的字节（丢掉作废那一下的字节不算丢数据 ——
   * 被作废的那次读上报的是「读满 buf.size」的谎，那一包本来就丢）。
   */
  @Test(timeout = 60_000)
  fun abortWakesAHealthyReadButDropsTheSession() {
    val firstChunkDelayMs = 300L
    TestMediaServer(
      body = ByteArray(40 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      firstChunkDelayMs = firstChunkDelayMs,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader =
          Thread { result.set(io.read(ByteArray(4096), 4096)) }.apply {
            isDaemon = true
            start()
          }
        // 服务端还没发第一块，所以此刻它确实在「进行中」。
        Thread.sleep(100)
        assertTrue(reader.isAlive, "the read should still be in flight at this point")

        handler.abort()
        reader.join(5_000)
        assertTrue(!reader.isAlive, "abort must wake up the waiting read")
        assertEquals(
          4096,
          result.get(),
          "an aborted read must report the positive 'read a full buf.size' value so the demuxer stops at once " +
            "instead of reading again (see HttpIO.read)",
        )

        // 下一次 read 自带全新信号（且手上那条会话已作废）⇒ 重发一条、接着读。
        assertTrue(io.readBounded(4096) > 0, "reads should keep working after the abort")
        assertEquals(2, server.requests, "the abort dropped that session, so the next read must resend")
      } finally {
        io.close()
      }
    }
  }
}
