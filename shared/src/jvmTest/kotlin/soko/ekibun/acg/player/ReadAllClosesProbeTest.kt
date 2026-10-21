package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Http.Response.readAll] 读完即 `close()`：成功路径与连接被掐断的异常路径都成立。
 */
class ReadAllClosesProbeTest {
  @Test(timeout = 60_000)
  fun readAllClosesOnSuccess() {
    val body = ByteArray(4096) { (it % 251).toByte() }
    TestMediaServer(body, honorRange = false).use { server ->
      val rsp = runBlocking { Http.request(mapOf("url" to server.url)) }
      val read = runBlocking { rsp.readAll() }
      assertEquals(body.size, read.size)
      assertTrue(rsp.isClosed, "readAll must close when done, otherwise the connection stays dangling")
    }
  }

  @Test(timeout = 60_000)
  fun readAllClosesWhenBodyIsTruncated() {
    // 服务端只发 1 KiB 就断链 ⇒ 异常路径上仍必须 close
    val body = ByteArray(64 * 1024) { (it % 251).toByte() }
    TestMediaServer(body, honorRange = false, cutAfterBytes = 1024).use { server ->
      val rsp = runBlocking { Http.request(mapOf("url" to server.url)) }
      var threw = false
      try {
        runBlocking { rsp.readAll() }
      } catch (_: Exception) {
        threw = true
      }
      assertTrue(threw, "readAll should throw when the server cuts the connection")
      assertTrue(
        rsp.isClosed,
        "**close must also happen on the failure path** -- that is exactly why the implementation is not " +
          "'read everything then close manually'",
      )
    }
  }
}
