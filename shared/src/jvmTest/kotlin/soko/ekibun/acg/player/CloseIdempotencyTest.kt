package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Http.Response.close()` 的幂等契约：连关三次不抛、`isClosed` 如实反映、关掉之后 `read` 当场拒绝。
 */
class CloseIdempotencyTest {
  @Test(timeout = 30_000)
  fun closingTwiceIsSafeAndIsClosedTracksIt() {
    TestMediaServer(
      body = ByteArray(4096) { (it % 251).toByte() },
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to server.url))
          r.read(ByteArray(64)) // 先读一次，确认连接真在用
          r
        }
      assertFalse(rsp.isClosed)
      // 连关三次不抛即幂等
      rsp.close()
      rsp.close()
      rsp.close()
      assertTrue(rsp.isClosed, "a closed response must report isClosed")
      // 已关闭的响应上再读必须当场拒绝，不能伪装成「读空了」
      val thrown =
        runCatching { runBlocking { rsp.read(ByteArray(64)) } }.exceptionOrNull()
      assertTrue(
        thrown is IllegalStateException,
        "reading a closed response must throw IllegalStateException, got $thrown",
      )
    }
  }
}
