package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 嵌套 `io_open` 失败：错误干净上报；分片 open 确实发生；每个 IO 句柄恰好关一次。见 `cxx/AGENTS.md`。
 */
class AvFormatNestedOpenFailureTest {
  /** 假 IO：首次 open 给真数据，其余恒失败，并记录每个交出的句柄有没有被 close。 */
  private class HlsSegmentFailureHandler(
    private val playlist: ByteArray,
  ) : AvIO.Handler {
    /** 每次 open 收到的 URL，按到达顺序。 */
    val openedUrls = mutableListOf<String>()
    var openedCount = 0
    var closedCount = 0

    override fun open(url: String): AvIO {
      openedUrls += url
      val isPlaylist = openedCount++ == 0
      val delegate: AvIO =
        if (isPlaylist) {
          TestIo(playlist).open(url)
        } else {
          object : AvIO {
            override fun read(
              buf: ByteArray,
              len: Int,
            ): Int = -1

            override fun seek(
              offset: Int,
              whence: Int,
            ): Int = -1

            override fun close() {}
          }
        }
      return object : AvIO by delegate {
        override fun close() {
          closedCount++
          delegate.close()
        }
      }
    }
  }

  @Test
  fun nestedOpenClosedBeforeFailurePathIsNotClosedTwice() {
    val handler = HlsSegmentFailureHandler(PLAYLIST)
    assertFailsWith<IllegalStateException>("open must fail as a handle-less init, not crash native") {
      runBlocking {
        withTimeout(30_000) {
          AvFormat("memory:test.m3u8", handler).getStreams()
        }
      }
    }

    // 分片那次 open 必须发生过
    assertTrue(
      handler.openedUrls.size >= 2,
      "expected a nested open after the playlist; opened=${handler.openedUrls}",
    )
    assertEquals(
      handler.openedCount,
      handler.closedCount,
      "every handed-out AvIO must be closed exactly once; opened=" +
        "${handler.openedCount} closed=${handler.closedCount} urls=${handler.openedUrls}",
    )
  }

  private companion object {
    /** 媒体播放列表（带分片）：HLS 要打开第一条分片才触发嵌套 `io_open`。 */
    val PLAYLIST =
      """
      #EXTM3U
      #EXT-X-VERSION:3
      #EXT-X-TARGETDURATION:10
      #EXT-X-MEDIA-SEQUENCE:0
      #EXTINF:10.0,
      segment0.ts
      #EXT-X-ENDLIST
      """.trimIndent().toByteArray(Charsets.US_ASCII)
  }
}
