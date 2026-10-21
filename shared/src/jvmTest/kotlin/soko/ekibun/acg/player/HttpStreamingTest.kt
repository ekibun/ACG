package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO` 是流式的：第一个 `read` 在响应体发完之前就拿到数据。见 `docs/development/playback-invariants.md` 的「流式读取」一节。
 */
class HttpStreamingTest {
  @Test(timeout = 60_000)
  fun firstReadReturnsLongBeforeTheWholeBodyArrives() {
    // 整包下发期 = 块数 × 块间隔
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

        // 把剩下的读完，字节数要对得上
        var got = first
        while (got < chunks * chunkSize) got += io.readBounded(chunkSize)
        assertEquals(chunks * chunkSize, got, "reading to the end should yield exactly all the bytes")
      } finally {
        io.close()
      }
    }
  }

  /** 没有读在等的时候，`abort()` 不该关掉会话：请求数保持 1。 */
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

        // 上一次读早就返回了，此刻没读在跑 ⇒ abort 是 no-op
        handler.abort()

        assertTrue(io.readBounded(4096) > 0, "reads should still work after the abort")
        assertEquals(1, server.requests, "abort with nobody waiting must not drop the session and resend")
      } finally {
        io.close()
      }
    }
  }

  /** 进行中的读被 abort 叫醒（报「读满 buf.size」），代价是会话作废、下一次读重发。 */
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
        // 服务端还没发第一块，所以此刻它确实在进行中
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

        // 下一次 read 自带新信号，手上那条会话已作废 ⇒ 重发
        assertTrue(io.readBounded(4096) > 0, "reads should keep working after the abort")
        assertEquals(2, server.requests, "the abort dropped that session, so the next read must resend")
      } finally {
        io.close()
      }
    }
  }
}
