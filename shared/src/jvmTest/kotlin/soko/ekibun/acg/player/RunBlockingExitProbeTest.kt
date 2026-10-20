package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 探针：`runBlocking` 里拿到 [soko.ekibun.common.Http.Response] 后，**不等 body 下完**能不能退出
 * `runBlocking`，且退出后连接是否仍然可用（读得到后续数据）。
 *
 * 判据两条：
 * 1. `runBlocking` 在服务端只发完第一块时就返回（墙钟远小于「整包发完」）；
 * 2. 退出后逐段读仍能拿到**后面几块** —— 连接没被 `runBlocking` 收尾时顺手 cleanup。
 *
 * 「只有 [soko.ekibun.common.Http.Response.close] 才结束会话」那半不在这里：
 * `HttpRequestStreamingTest.closeReleasesTheServerConnection` 用 `entered == exited` 零睡眠判据
 * 钉着同一件事，而这里只能靠两次固定 `Thread.sleep` 去猜服务端什么时候收场 —— 那是恒真断言。
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
          // 只读第一块就交出去：`runBlocking` 的返回值式契约是「拿到响应就返回」。
          assertTrue(r.read(ByteArray(chunk)) > 0, "the first chunk must be readable")
          r
        }
      val exitMs = (System.nanoTime() - t0) / 1_000_000

      // 判据 1：远早于整包发完（整包约 ${wholeMs}ms）。
      assertTrue(
        exitMs < wholeMs / 2,
        "runBlocking should return well before the whole body arrives, took ${exitMs}ms (body takes about ${wholeMs}ms)",
      )

      try {
        // 判据 2：退出后连接仍活着 —— 第 2 块读得到，且不是流尾。
        // 只读**一块**就收场：服务端发完 chunk 后转入心跳（每次 1 B，永不流尾），
        // 读到流尾会一直挂在心跳上。
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
