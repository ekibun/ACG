package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.acg.common.Http
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 探针：`runBlocking` 里拿到 [soko.ekibun.acg.common.Http.Response] 后，**不等 body 下完**能不能退出
 * `runBlocking`，且退出后连接是否仍然可用（读得到后续数据）。
 *
 * 判据三条：
 * 1. `runBlocking` 在服务端只发完第一块时就返回（墙钟远小于「整包发完」）；
 * 2. 退出后逐段读仍能拿到**后面几块** —— 连接没被 `runBlocking` 收尾时顺手 cleanup；
 * 3. [soko.ekibun.acg.common.Http.Response.close] 才是结束会话的那一下（对照：不 close 会漏连接）。
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
      var afterExit = 0

      val t0 = System.nanoTime()
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to server.url))
          // 只读第一块就交出去：`runBlocking` 的返回值式契约是「拿到响应就返回」。
          assertTrue(r.read(ByteArray(chunk)) > 0, "第一块必须读到数据")
          r
        }
      val exitMs = (System.nanoTime() - t0) / 1_000_000

      // 判据 1：远早于整包发完（整包约 ${wholeMs}ms）。
      assertTrue(
        exitMs < wholeMs / 2,
        "runBlocking 应在远早于整包下发完成时返回，实际 ${exitMs}ms（整包约 ${wholeMs}ms）",
      )

      try {
        // 判据 2：退出后连接仍活着 —— 第 2 块读得到，且不是流尾。
        // 只读**一块**就收场：服务端发完 chunk 后转入心跳（每次 1 B，永不流尾），
        // 读到流尾会一直挂在心跳上。
        val got = runBlocking { rsp.read(ByteArray(chunk)) }
        afterExit = got
        assertTrue(
          got > 0,
          "runBlocking 退出后仍应读得到后续数据（连接没被 cleanup），实际读到 $got B",
        )
      } finally {
        runBlocking { rsp.close() }
      }
    }
  }

  @Test(timeout = 60_000)
  fun closeIsWhatEndsTheSessionNotRunBlockingExit() {
    TestMediaServer(
      body = ByteArray(20 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 200,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to server.url))
          r.read(ByteArray(4096))
          r
        }
      // 不 close：runBlocking 早已返回，但会话还挂着 ⇒ 服务端 handler 不出场。
      Thread.sleep(1_500)
      val leaked = server.entered > server.exited
      runBlocking { rsp.close() }
      Thread.sleep(800)
      assertTrue(
        leaked,
        "不 close 时连接应一直挂着（entered=${server.entered} exited=${server.exited}）",
      )
      assertTrue(
        server.entered == server.exited,
        "close 之后 handler 应收场（entered=${server.entered} exited=${server.exited}）",
      )
    }
  }
}
