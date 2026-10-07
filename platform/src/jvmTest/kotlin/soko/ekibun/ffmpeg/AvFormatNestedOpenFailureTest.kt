package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 嵌套 `io_open` 中途失败后，打开失败路径**不许再关一次已经关掉的 pb**。
 *
 * 与 [AvReadErrorFoldTest.openFailureFailsCleanly] 的区别在**第几次 open 失败**：那条是顶层
 * 就失败（pb 由失败路径收，`io_close2` 从没跑过），这条是**顶层成功、嵌套打开失败** ——
 * HLS 的 `read_header` 中途探分片时失败，会自己先走一次 `ff_format_io_close` → `io_close2`
 * 把分片 pb 关掉，之后 `avformat_open_input` 才返回失败。native 侧账本要是没被 `io_close2`
 * 销账，失败路径就会拿着已释放的 pb 二次关闭，`pb->opaque` 读出垃圾值当 jobject 交给
 * `GetObjectClass` ⇒ `EXCEPTION_ACCESS_VIOLATION`（崩因链与 hs_err 证据见
 * `cxx/AGENTS.md` 的 ffmpeg 一节）。
 *
 * 另钉两件事：分片那次 open **必须真的发生过**（否则这条用例退化成上面那条顶层失败的重复，
 * 白跑）；交出去的每个 IO 句柄都**恰好**被 close 一次（少了是漏、多了是二次关闭）。
 */
class AvFormatNestedOpenFailureTest {
  /**
   * 假 IO：第一次 open（播放列表本身）给真数据，其余（分片 / key）按 [AvIO.Handler.open]
   * 的契约返回**恒失败**的 IO，并记录每个交出的句柄有没有被 close。
   */
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
            override fun read(buf: ByteArray): Int = -1

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

    // 分片那次 open 必须发生过：只开播放列表说明 HLS 压根没走到嵌套打开，
    // 这条用例就没覆盖到 io_close2 销账那一环。
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
    /**
     * 媒体播放列表（带分片，不是 master）：HLS 要走到「打开第一条分片」那一步才会触发
     * 嵌套 `io_open`。`#EXT-X-TARGETDURATION` 供 hls_probe 认格式，`#EXT-X-ENDLIST`
     * 让重载循环以 EOF 收场、不再空转。
     */
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
