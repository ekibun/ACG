package soko.ekibun.common

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 传输层原语 `Http.Response`：拉取式读、`readAll` 的收场、`close()` 幂等、作用域退出后连接还活着。 */
class HttpTest {
  /**
   * 读掉请求、写半截响应就断开的裸 socket。声明长度未必真断链，分块传输又会补终止块，只能裸 `ServerSocket`。
   */
  private fun truncatedServer(
    body: ByteArray,
    sent: Int,
  ): ServerSocket =
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
      Thread {
        runCatching {
          accept().use { conn ->
            conn.getInputStream().read(ByteArray(4096))
            val out = conn.getOutputStream()
            out.write(
              (
                "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                  "Content-Type: application/octet-stream\r\n\r\n"
              ).toByteArray(),
            )
            out.write(body, 0, sent)
            out.flush()
            // 不够长度就断：客户端读到的必须是「流被截断」而不是「正常结束」
          }
        }
      }.apply {
        isDaemon = true
        start()
      }
    }

  /** `readAll` 读完即 `close()`：成功路径与连接被掐断的异常路径都成立。 */
  @Test(timeout = 60_000)
  fun readAllClosesOnBothPaths() {
    val body = ByteArray(4096) { (it % 251).toByte() }
    TestServer(body).use { server ->
      val rsp = runBlocking { Http.request(mapOf("url" to server.mediaUrl)) }
      val read = runBlocking { rsp.readAll() }
      assertEquals(body.size, read.size)
      assertTrue(rsp.isClosed, "readAll must close when done, otherwise the connection stays dangling")
    }

    // 声明 64 KiB、只发 1 KiB 就断链 ⇒ 异常路径上仍必须 close
    truncatedServer(ByteArray(64 * 1024) { (it % 251).toByte() }, sent = 1024).use { socket ->
      val rsp = runBlocking { Http.request(mapOf("url" to "http://127.0.0.1:${socket.localPort}/media")) }
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

  /** `request` 交给一条活着的响应：元数据对得上、能直接读、`close()` 真的放掉连接。分块下 `contentLength` 是 -1。 */
  @Test(timeout = 60_000)
  fun requestHandsOverALiveResponse() {
    TestServer(
      body = ByteArray(40 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val rsp = runBlocking { Http.request(mapOf("url" to server.mediaUrl)) }
      try {
        assertEquals(200, rsp.status, "status should be the 200 response")
        assertTrue(
          rsp.headers.any { (name, values) ->
            name.equals("Transfer-Encoding", ignoreCase = true) &&
              values.any { it.equals("chunked", ignoreCase = true) }
          },
          "headers should carry the server's headers (the server sends chunked here)",
        )
        assertEquals(server.mediaUrl, rsp.url, "the response URL must match")
        // 网络游标起点由 Content-Range 推（200 ⇒ 起点 0）
        assertEquals(0, rsp.offset, "a 200 from the server means the start offset is 0")
        // chunked 响应没有 Content-Length ⇒ 契约上是 -1
        assertEquals(-1L, rsp.contentLength, "no Content-Length/Content-Range means unknown length, -1 by contract")

        runBlocking {
          val buf = ByteArray(4096)
          assertTrue(rsp.read(buf) > 0, "should be able to read bytes (raw Int, no AVERROR translation)")
          // 连读两次，确认通道能复用
          assertTrue(
            rsp.read(buf) >= 0,
            "the stream should stay readable (-1 means EOF, which streaming data must not hit)",
          )
        }
      } finally {
        rsp.close()
      }

      // close() 之后服务端那条会话要收场，且全程只开过一条
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

  /** 拉取式：读到 1/4 时服务端还没发完。判据落在发送进度上，一次下完整包会当场露馅。 */
  @Test(timeout = 60_000)
  fun readingIsPullBased() {
    val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    val quarter = body.size / 4
    TestServer(body, chunkSize = 64 * 1024).use { server ->
      runBlocking {
        val rsp = Http.request(mapOf("url" to server.mediaUrl))
        try {
          val buf = ByteArray(64 * 1024)
          var read = 0
          var peak = 0
          while (read < quarter) {
            val n = rsp.read(buf)
            if (n < 0) break
            read += n
            peak = maxOf(peak, rsp.availableForRead)
          }
          assertTrue(read >= quarter, "could only read $read B of $quarter")
          assertTrue(
            server.served < body.size,
            "only $read B consumed while the server already sent ${server.served}/${body.size} B -- the transport buffers the whole body up front",
          )
          assertTrue(
            peak <= 32 * 1024,
            "peak local buffered amount ${peak}B exceeds one avio buffer -- the transport layer is pre-buffering",
          )
        } finally {
          rsp.close()
        }
      }
    }
  }

  /** `runBlocking` 退出不能带走连接：body 还在发时交出去，退出后同一条会话仍读得到。 */
  @Test(timeout = 60_000)
  fun connectionSurvivesAfterTheCoroutineScopeExits() {
    val chunk = 4096
    val chunks = 10
    TestServer(
      body = ByteArray(chunks * chunk) { (it % 251).toByte() },
      chunkDelayMs = 100,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to server.mediaUrl))
          assertTrue(r.read(ByteArray(chunk)) > 0, "the first chunk must be readable")
          r
        }
      try {
        val got = runBlocking { rsp.read(ByteArray(chunk)) }
        assertTrue(
          got > 0,
          "data should still be readable after runBlocking exits (the connection was not cleaned up), got $got B",
        )
        assertEquals(1, server.requests, "the session must survive the scope exit, not be reopened")
      } finally {
        rsp.close()
      }
    }
  }

  /** `close()` 幂等、`isClosed` 如实、关掉后 `read` 抛 `IllegalStateException`（返回 0 会被当成正常 EOF）。 */
  @Test(timeout = 30_000)
  fun closeIsIdempotentAndAClosedResponseRejectsRead() {
    val body = ByteArray(4096) { (it % 251).toByte() }
    TestServer(body, heartbeat = true).use { server ->
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to server.mediaUrl))
          assertTrue(r.read(ByteArray(64)) > 0, "the response must be readable before close")
          r
        }
      assertFalse(rsp.isClosed, "a live response must not report isClosed")
      // 连关三次不抛即幂等
      rsp.close()
      rsp.close()
      rsp.close()
      assertTrue(rsp.isClosed, "a closed response must report isClosed")
      val thrown = runCatching { runBlocking { rsp.read(ByteArray(64)) } }.exceptionOrNull()
      assertTrue(
        thrown is IllegalStateException,
        "reading a closed response must throw IllegalStateException, got $thrown",
      )
    }
  }
}
