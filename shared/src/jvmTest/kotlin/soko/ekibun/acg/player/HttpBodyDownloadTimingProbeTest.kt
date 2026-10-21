package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 探针：传输层是拉取式的 —— 流式读时本地存量始终远小于整个 body。 */
class HttpBodyDownloadTimingProbeTest {
  /** 一次 `read` 里本地最多能攒多少：一个 avio 缓冲的量级。 */
  private val bufferBound = 32 * 1024

  @Test(timeout = 60_000)
  fun localBufferedAmountStaysFarBelowTheBodyWhileStreaming() {
    val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    TestMediaServer(body, chunkSize = 64 * 1024, honorRange = false).use { server ->
      runBlocking {
        val rsp = Http.request(mapOf("url" to server.url))
        try {
          val buf = ByteArray(64 * 1024)
          var read = 0
          var maxBuffered = 0
          while (read < body.size) {
            val n = rsp.read(buf)
            if (n < 0) break
            read += n
            maxBuffered = maxOf(maxBuffered, rsp.availableForRead)
          }
          println("[probe-pull] read ${body.size}B, peak local buffered amount ${maxBuffered}B")
          assertEquals(body.size, read, "the whole stream should be read")
          assertTrue(
            maxBuffered <= bufferBound,
            "peak local buffered amount ${maxBuffered}B exceeds one avio buffer -- the transport layer is pre-buffering again",
          )
        } finally {
          rsp.close()
        }
      }
    }
  }
}
