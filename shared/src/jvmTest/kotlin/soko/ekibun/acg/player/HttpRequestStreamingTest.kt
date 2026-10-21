package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `Http.request(...)` 的返回值式契约：返回活的 [Http.Response]，`close()` 真把服务端连接放掉。 */
class HttpRequestStreamingTest {
  /** 返回的响应带着服务端那条响应的 status / headers / url，以及推导出的 `offset` 与 `contentLength`。 */
  @Test(timeout = 60_000)
  fun responseExposesStatusHeadersAndUrlWithDerivedMetadata() {
    TestMediaServer(
      body = ByteArray(20 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 30,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val rsp =
        runBlocking {
          Http.request(mapOf("url" to server.url))
        }
      try {
        assertEquals(200, rsp.status, "status should be the 200 response")
        assertTrue(
          rsp.headers.any { (name, values) ->
            name.equals("Transfer-Encoding", ignoreCase = true) &&
              values.any { it.equals("chunked", ignoreCase = true) }
          },
          "headers should carry the server's headers (the server sends chunked here)",
        )
        assertEquals(server.url, rsp.url, "the response URL must match")
        // 网络游标起点由 Content-Range 推（200 ⇒ 起点 0）
        assertEquals(0, rsp.offset, "a 200 from the server means the start offset is 0")
        // chunked 响应没有 Content-Length ⇒ 契约上是 -1
        assertEquals(-1L, rsp.contentLength, "no Content-Length/Content-Range means unknown length, -1 by contract")
      } finally {
        rsp.close()
      }
    }
  }

  /** 返回的响应能直接读到字节。 */
  @Test(timeout = 60_000)
  fun responseIsReadable() {
    TestMediaServer(
      body = ByteArray(20 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 30,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      // Response.read 是 suspend：数据没到就等
      runBlocking {
        val rsp = Http.request(mapOf("url" to server.url))
        try {
          val buf = ByteArray(4096)
          val n = rsp.read(buf)
          assertTrue(n > 0, "should be able to read bytes (raw Int, no AVERROR translation)")
          // 连读两次，确认通道能复用
          val n2 = rsp.read(buf)
          assertTrue(n2 >= 0, "the stream should stay readable (-1 means EOF, which streaming data must not hit)")
        } finally {
          rsp.close()
        }
      }
    }
  }

  /** `close()` 之后服务端连接被放掉：`entered == exited`，且全程只开一条会话。 */
  @Test(timeout = 60_000)
  fun closeReleasesTheServerConnection() {
    TestMediaServer(
      body = ByteArray(100 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val rsp = runBlocking { Http.request(mapOf("url" to server.url)) }
      val buf = ByteArray(4096)
      assertTrue(runBlocking { rsp.read(buf) } > 0, "read some data first to prove the connection is in use")
      rsp.close()

      // 静默期：等服务端 handler 察觉到撒手并出场
      val deadline = System.nanoTime() + 8_000_000_000L
      while (System.nanoTime() < deadline && server.exited < server.entered) Thread.sleep(50)
      assertEquals(
        server.entered,
        server.exited,
        "connection still held after close() (entered>exited): the transport did not clean up",
      )
      assertEquals(1, server.requests, "the whole run should open exactly one session")
    }
  }
}
