package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `runBlocking` 拿到 [soko.ekibun.common.Http.Response] 后不等 body 下完就能退出，且退出后连接仍可读。
 */
class RunBlockingExitProbeTest {
  @Test(timeout = 60_000)
  fun runBlockingReturnsBeforeBodyFinishedAndConnectionStaysUsable() {
    val chunk = 4096
    val chunks = 10
    TestMediaServer(
      body = ByteArray(chunks * chunk) { (it % 251).toByte() },
      chunkDelayMs = 300,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val wholeMs = (chunks - 1) * 300L

      val t0 = System.nanoTime()
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to server.url))
          // 只读第一块就交出去
          assertTrue(r.read(ByteArray(chunk)) > 0, "the first chunk must be readable")
          r
        }
      val exitMs = (System.nanoTime() - t0) / 1_000_000

      // 判据 1：远早于整包发完（整包约 ${wholeMs}ms）
      assertTrue(
        exitMs < wholeMs / 2,
        "runBlocking should return well before the whole body arrives, took ${exitMs}ms (body takes about ${wholeMs}ms)",
      )

      try {
        // 判据 2：退出后连接仍活着，第 2 块读得到
        val got = runBlocking { rsp.read(ByteArray(chunk)) }
        assertTrue(
          got > 0,
          "data should still be readable after runBlocking exits (the connection was not cleaned up), got $got B",
        )
      } finally {
        runBlocking { rsp.close() }
      }
    }
  }
}
