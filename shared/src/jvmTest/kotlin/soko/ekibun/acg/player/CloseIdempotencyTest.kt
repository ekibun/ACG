package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住 `Http.Response.close()` 的幂等契约：**两条关闭路径**（调用方显式 close、JS 侧 GC 时的
 * `JavaObject` finalizer）可能并发到达，并且都可能落在同一条响应上。
 *
 * 判据三条：连关三次不抛、`isClosed` 如实反映、关掉之后 `read` 当场拒绝（不是伪装成「读空了」）。
 *
 * 服务端用 [TestMediaServer] 的心跳档：body 发完不关、每 50 ms 续 1 字节，客户端撒手时服务端
 * **看得见**（`exited` 追得上 `entered`），不必靠「睡够猜」判定。
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
          r.read(ByteArray(64)) // read once to prove the connection is really in use
          r
        }
      assertFalse(rsp.isClosed)
      // 连关三次：不抛异常就是幂等（第一次之后两次都被 [Http.Response.isClosed] 那道守卫挡掉）。
      rsp.close()
      rsp.close()
      rsp.close()
      assertTrue(rsp.isClosed, "a closed response must report isClosed")
      // 已关闭的响应上再读必须当场拒绝，而不是伪装成「读空了」。
      val thrown =
        runCatching { runBlocking { rsp.read(ByteArray(64)) } }.exceptionOrNull()
      assertTrue(
        thrown is IllegalStateException,
        "reading a closed response must throw IllegalStateException, got $thrown",
      )
    }
  }
}
