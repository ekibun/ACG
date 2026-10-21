package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AvIO
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpAbandonedSessionTest {
  /** abort 落在前推途中：已建好的响应被摘掉并关掉，服务端 `entered == exited`。 */
  @Test(timeout = 120_000)
  fun abandonedSessionIsClosedWhenAdvanceToThrows() {
    val body = ByteArray(200_000) { (it % 251).toByte() }
    TestMediaServer(
      body,
      chunkDelayMs = 80,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      // 先读一次让会话存在，再把它丢掉
      assertTrue(io.readBounded(4096) > 0, "first read")
      io.close()

      // 逻辑 offset 拉得很远：下一次读要新建会话、靠 advance 一路往前磨
      io.seek(150_000, AvIO.SEEK_SET)
      val result = AtomicInteger(Int.MIN_VALUE)
      val reader =
        Thread { result.set(io.read(ByteArray(4096), 4096)) }.apply {
          isDaemon = true
          start()
        }
      Thread.sleep(250)
      assertTrue(reader.isAlive, "read must be grinding through advance")
      handler.abort()
      reader.join(5_000)
      assertTrue(!reader.isAlive, "abort must end the read")
      val ret = result.get()
      io.close()

      // 静默期：等每个 handler 察觉并退出
      val deadline = System.nanoTime() + 8_000_000_000L
      while (System.nanoTime() < deadline && server.exited < server.entered) Thread.sleep(50)
      println("ret=$ret entered=${server.entered} exited=${server.exited} leaked=${server.entered - server.exited}")
      assertEquals(4096, ret, "aborted read must report a full-buffer read (buf.size)")
      assertEquals(
        server.entered,
        server.exited,
        "a session was left parked (entered>exited): the response abandoned by discard is never closed",
      )
    }
  }

  /**
   * 首条会话挂死（只发响应头、body 不给）时，闲置超时必须丢掉它、重开一条接着读。
   *
   * 不重试的话 `read` 会把 [soko.ekibun.common.Http.IdleTimeoutException] 折成 -1，ffmpeg 判 IO 错误。
   * 判据：读到的字节等于真身，且不止一条会话（说明真的换了）。
   */
  @Test(timeout = 120_000)
  fun idleTimeoutReopensSessionAndKeepsReading() {
    val body = ByteArray(4 * HttpCache.BLOCK_BYTES) { (it % 251).toByte() }
    TestMediaServer(body, hangFirstResponse = true).use { server ->
      val io = HttpIO(server.url, mapOf(), HttpIO.Handler())
      try {
        val buf = ByteArray(4096)
        val n = io.read(buf, buf.size)
        assertTrue(n > 0, "闲置超时后应当换会话重读，而不是返回 -1")
        assertContentEquals(body.copyOfRange(0, n), buf.copyOf(n), "换会话后读到的必须是流首的字节")
        assertTrue(server.requests >= 2, "首条会话挂死，应当再开一条")
      } finally {
        io.close()
      }
    }
  }
}
