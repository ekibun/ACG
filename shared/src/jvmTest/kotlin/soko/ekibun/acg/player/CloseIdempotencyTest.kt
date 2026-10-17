package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.common.Http
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住 `Http.Response.close()` 的幂等契约：**两条关闭路径**（调用方显式 close、JS 侧 GC 时的
 * `JavaObject` finalizer）可能并发到达，并且都可能落在同一条响应上。
 *
 * 判据三条：连关三次不抛、`isClosed` 如实反映、关掉之后 `read` 当场拒绝（不是伪装成「读空了」）。
 */
class CloseIdempotencyTest {
  @Test(timeout = 30_000)
  fun closingTwiceIsSafeAndIsClosedTracksIt() {
    val pool = Executors.newFixedThreadPool(1)
    try {
      val server =
        com.sun.net.httpserver.HttpServer
          .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
          .apply {
            executor = pool
            createContext("/x") { ex ->
              ex.sendResponseHeaders(200, 0)
              val out = ex.responseBody
              try {
                val buf = ByteArray(4096)
                while (true) {
                  out.write(buf)
                  out.flush()
                  Thread.sleep(50)
                }
              } catch (_: Exception) {
                // 客户端断开会走这里
              } finally {
                runCatching { out.close() }
              }
            }
            start()
          }
      val url = "http://127.0.0.1:${server.address.port}/x"
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to url))
          r.read(ByteArray(64)) // 先读一次，证明连接真的在用
          r
        }
      assertFalse(rsp.isClosed)
      // 连关三次：不抛异常就是幂等（第一次之后两次都被 [Http.Response.isClosed] 那道守卫挡掉）。
      rsp.close()
      rsp.close()
      rsp.close()
      assertTrue(rsp.isClosed, "关过就该报 isClosed")
      // 已关闭的响应上再读必须当场拒绝，而不是伪装成「读空了」。
      val thrown =
        runCatching { runBlocking { rsp.read(ByteArray(64)) } }.exceptionOrNull()
      assertTrue(thrown is IllegalStateException, "已关闭后 read 应抛 IllegalStateException，实际 $thrown")
      server.stop(0)
    } finally {
      pool.shutdownNow()
    }
  }
}
