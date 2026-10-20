package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **探针**：传输层是**拉取式**的 —— 不读就不下，读了也不在本地攒。
 *
 * 这是 `Http.Response.availableForRead` 那个「存量偏小」的语义来源，也是换掉旧传输层的直接理由：
 * 旧实现（通道式）在写侧会先攒出一大片（上限约 1 MiB），于是「已缓冲」可能虚高到 avio 缓冲之外；
 * 现在 os 层面的 socket 缓冲是唯一的提前量，客户端自己一字节不留。
 *
 * 判据只看**客户端侧**：服务端 `served` 只可能告诉我们「服务端写了多少」，socket 缓冲里
 * 堆了多少与客户端有没有读无关，拿它当「客户端已下载」是错的。
 *
 * 「还没读时存量是 0」那半不在这里单独测：`HttpStreamingTest.firstReadReturnsLongBeforeTheWholeBodyArrives`
 * 拿到的是「刚到的那一块」而不是整包，已经是同一件事的更强判据；空等 500 ms 只会让它变成
 * 一条恒真断言。
 */
class HttpBodyDownloadTimingProbeTest {
  /** 一次 `read` 里本地最多能攒多少：一个 avio 缓冲就够宽松了，1 MiB 级预读会立刻越界。 */
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
