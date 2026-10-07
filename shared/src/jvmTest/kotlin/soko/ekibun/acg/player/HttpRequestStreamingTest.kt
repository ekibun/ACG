package soko.ekibun.acg.player

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 钉住 `Http.request(...)` 的**返回值式**契约：它返回一条活的 [Http.Response]，
 * 元数据从 `delegate` 取，`close()` 必须真把服务端那条连接放掉。
 *
 * 与 `HttpStreamingTest` 的分工：那边测的是 `HttpIO`（消费方）的行为；这边直接测 API 本身，
 * 判据独立于 `HttpIO` —— 这样「返回式 API 的生命周期对不对」不再只能靠播放器路径间接验证。
 */
class HttpRequestStreamingTest {
  /**
   * 返回的 [Http.Response] 必须**真的包着块内那个响应**：`status` / `headers` / `call`
   * 都对得上，`offset` 与 `contentLength` 的推导口径也对。
   *
   * `Http.request` **不收任何作废信号**：建立期的取消由调用方（`HttpIO.read` 的哨兵）
   * **取消整条协程**掀掉 —— 见 [Http.request] 与 `HttpIO.read` 的 KDoc。
   */
  @Test(timeout = 60_000)
  fun responseExposesTheInBlockDelegateAndDerivedMetadata() {
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
        // ktor 元数据一律从 delegate 取（不转发）。
        val status = rsp.delegate.status.value
        val request = rsp.delegate.call.request
        val requestUrl = request.url.toString()
        assertEquals(200, status, "delegate 应当是那条 200 响应")
        assertTrue(
          rsp.delegate.headers[HttpHeaders.TransferEncoding] != null,
          "delegate.headers 应当带着服务端的头（这里服务端用 chunked 发）",
        )
        assertEquals(server.url, requestUrl, "delegate 的请求 URL 要对得上")
        // 网络游标起点由 `Content-Range` 推（200 = 服务端从头给 ⇒ 起点 0），
        // 见 [Http.Response.offset] 的 KDoc。
        assertEquals(0, rsp.offset, "服务端回 200 ⇒ 起点按 0 记")
        // contentLength 由 init 从 delegate 的 header 推 —— 这里服务端用 chunked 发
        // （sendResponseHeaders(200, 0)），没有 Content-Length，所以按契约是 -1。
        assertEquals(-1L, rsp.contentLength, "没有 Content-Length/Content-Range 时总长未知，按契约是 -1")
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
          assertTrue(n > 0, "应当能读到字节（返回原始 Int，不做 AVERROR 翻译）")
          // 连读两次，确认通道能复用（`offset` 对外只读，这里只证明「读得动、读得下去」）。
          val n2 = rsp.read(buf)
          assertTrue(n2 >= 0, "通道应当可以连续读（-1 表示读完了，一次性数据不该在这里出现）")
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
      assertTrue(runBlocking { rsp.read(buf) } > 0, "先读到数据，证明连接真的在用")
      rsp.close()

      // 静默期：等服务端 handler 察觉到撒手并出场。
      val deadline = System.nanoTime() + 8_000_000_000L
      while (System.nanoTime() < deadline && server.exited < server.entered) Thread.sleep(50)
      assertEquals(
        server.entered,
        server.exited,
        "close() 之后连接仍挂着（entered>exited）：block 没被放回去、ktor 没 cleanup",
      )
      assertEquals(1, server.requests, "整个过程只该开一条会话")
    }
  }
}
