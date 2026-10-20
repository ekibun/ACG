package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 钉住 `Http.request(...)` 的**返回值式**契约：它返回一条活的 [Http.Response]，
 * 元数据从响应自身取，`close()` 必须真把服务端那条连接放掉。
 *
 * 与 `HttpStreamingTest` 的分工：那边测的是 `HttpIO`（消费方）的行为；这边直接测 API 本身，
 * 判据独立于 `HttpIO` —— 这样「返回式 API 的生命周期对不对」不再只能靠播放器路径间接验证。
 */
class HttpRequestStreamingTest {
  /**
   * 返回的 [Http.Response] 必须**真的包着服务端那条响应**：`status` / `headers` / `url`
   * 都对得上，`offset` 与 `contentLength` 的推导口径也对。
   *
   * `Http.request` **不收任何作废信号**：建立期的取消由调用方（`HttpIO.read` 的哨兵）
   * **取消整条协程**掀掉 —— 见 [Http.request] 与 `HttpIO.read` 的 KDoc。
   */
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
        // 网络游标起点由 `Content-Range` 推（200 = 服务端从头给 ⇒ 起点 0），
        // 见 [Http.Response.offset] 的 KDoc。
        assertEquals(0, rsp.offset, "a 200 from the server means the start offset is 0")
        // contentLength 从响应头推 —— 这里服务端用 chunked 发
        // （sendResponseHeaders(200, 0)），没有 Content-Length，所以按契约是 -1。
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
      // `Response.read` 是 suspend，且内部「先等数据到、再读」—— 数据没到它会等到到（或超时抛），
      // 阻塞由调用方按需包（本类里不再有 runBlocking 之外的阻塞）。
      runBlocking {
        val rsp = Http.request(mapOf("url" to server.url))
        try {
          val buf = ByteArray(4096)
          val n = rsp.read(buf)
          assertTrue(n > 0, "should be able to read bytes (raw Int, no AVERROR translation)")
          // 连读两次，确认通道能复用（`offset` 对外只读，这里只证明「读得动、读得下去」）。
          val n2 = rsp.read(buf)
          assertTrue(n2 >= 0, "the stream should stay readable (-1 means EOF, which streaming data must not hit)")
        } finally {
          rsp.close()
        }
      }
    }
  }

  /**
   * **`close()` 之后服务端连接必须被放掉** —— 这是「返回式 API 的生命周期对不对」唯一
   * 能真证明的判据（`HttpAbandonedSessionTest` 同款 `entered`/`exited` 计数）。
   */
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

      // 静默期：等服务端 handler 察觉到撒手并出场。
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
