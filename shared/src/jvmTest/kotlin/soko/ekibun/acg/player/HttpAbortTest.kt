package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `AvIO.Handler.abort()`：把正在等数据的那次读叫醒，且下一次读能重新发请求恢复。 */
class HttpAbortTest {
  /** 卡在请求里时 abort 立刻以「读满 `buf.size`」收场，之后下一次读重连并读到数据。 */
  @Test(timeout = 60_000)
  fun abortWakesAStalledRequestAndTheNextReadReconnects() {
    val body = ByteArray(256 * 1024) { (it % 251).toByte() }
    // 只有第一条响应停住，后续正常发完
    TestMediaServer(body, chunkSize = 16 * 1024, hangFirstResponse = true).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader =
          Thread { result.set(io.read(ByteArray(4096), 4096)) }.apply {
            isDaemon = true
            start()
          }
        assertTrue(server.firstResponseHanging.await(10, TimeUnit.SECONDS), "the first request never arrived")
        reader.join(500)
        assertTrue(reader.isAlive, "the read must not return while the server is holding")

        handler.abort()
        reader.join(10_000)
        if (reader.isAlive) {
          println("[diag] reader still stuck after abort:\n" + reader.stackTrace.joinToString("\n"))
        }
        assertFalse(reader.isAlive, "abort must wake up the waiting read")
        assertEquals(
          4096,
          result.get(),
          "an aborted read must report the positive 'read a full buf.size' value so the demuxer stops at once " +
            "instead of reading again (see HttpIO.read)",
        )

        // 作废信号只活在那一次 read 里，这次读直接重发
        val after = io.readBounded(4096, timeoutMs = 30_000)
        assertTrue(after > 0, "the read after an abort should resend by itself and get data, got $after")
      } finally {
        io.close()
      }
    }
  }
}
