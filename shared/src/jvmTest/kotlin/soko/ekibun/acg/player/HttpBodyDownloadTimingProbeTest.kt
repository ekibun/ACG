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
 * 两条判据都只看**客户端侧**：服务端 `served` 只可能告诉我们「服务端写了多少」，socket 缓冲里
 * 堆了多少与客户端有没有读无关，拿它当「客户端已下载」是错的。
 */
class HttpBodyDownloadTimingProbeTest {
  /** 一次 `read` 里本地最多能攒多少：一个 avio 缓冲就够宽松了，1 MiB 级预读会立刻越界。 */
  private val bufferBound = 32 * 1024

  @Test(timeout = 60_000)
  fun nothingIsBufferedLocallyBeforeTheFirstRead() {
    val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    TestMediaServer(body, chunkSize = 64 * 1024, honorRange = false).use { server ->
      val rsp =
        runBlocking {
          Http.request(
            mapOf<Any, Any?>(
              "url" to server.url,
              "headers" to mapOf("range" to "bytes=0-"),
            ),
          )
        }
      try {
        // 响应头已到、body 一个字节都没读。
        assertEquals(0, rsp.availableForRead, "还没读，本地不该有存量")
        // 空等也不会自己涨：存量只可能因本客户端的读而变大（服务端写了多少是另一回事）。
        Thread.sleep(500)
        println("[probe-pull] 空等 500ms 之后：本地存量 ${rsp.availableForRead}B、服务端已写出 ${server.served}B")
        assertEquals(0, rsp.availableForRead, "空等不该让本地存量自己涨")
      } finally {
        rsp.close()
      }
    }
  }

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
          println("[probe-pull] 读完 ${body.size}B 期间本地存量峰值 ${maxBuffered}B")
          assertEquals(body.size, read, "应当把整条流读完")
          assertTrue(
            maxBuffered <= bufferBound,
            "本地存量峰值 ${maxBuffered}B 超过一个 avio 缓冲 —— 传输层又开始预读了",
          )
        } finally {
          rsp.close()
        }
      }
    }
  }
}
