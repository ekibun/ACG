package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 探针：[Http.Response.readAll] 读完即 `close()` 的两条性质。
 *
 * `readAll` 是插件 JS 的 `Response.arrayBuffer()` 那条路，**读完就关**是它的固有语义
 * （消费者拿到字节数组就不再需要这条连接）。这里钉住两件事：
 *
 * 1. **正常路径确实关了** —— 读完之后 `isClosed` 为真。
 * 2. **异常路径也关** —— 服务端在读完之前掐断连接时 `toByteArray()` 抛异常，
 *    这时连接与响应同样必须放掉（靠 `use`；写成「读完再手动 close」会漏掉这一条）。
 *
 * 「异常路径上有没有漏」只看 `isClosed` 就够：连接怎么回收是传输层的事，不是本层的契约。
 *
 * 服务端用 [TestMediaServer] 的 `cutAfterBytes` 档：发到指定字节就掐断，客户端读到的不是流尾。
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
    // 服务端只发 1 KiB 就断链 ⇒ toByteArray() 抛 ⇒ 异常路径上仍必须 close。
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
