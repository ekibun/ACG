package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvFormat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO.read` 的契约：网络源解出的帧序列与本地文件逐帧相同（认与不认 Range 两种服务端），并守住 seek 的会话数判据。
 */
class HttpReadContractTest {
  @Test
  fun rangedServerReadsEveryFrame() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val reference = decodeVideoPts(AvFormat(media.toString(), IOHandler()))
    TestMediaServer(Files.readAllBytes(media), chunkDelayMs = 10).use { server ->
      val overHttp = decodeVideoPts(AvFormat(server.url, IOHandler()))
      assertEquals(
        reference,
        overHttp,
        "an HTTP source must decode the exact same video frame sequence as the local file",
      )
    }
  }

  /** 服务端不认 Range 时向前 seek：先顺序读一小段，再向前跳到 3 s。 */
  @Test
  fun forwardSeekOverRangeIgnoringServerReadsEveryFrame() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val reference = decodeVideoPts(AvFormat(media.toString(), IOHandler()))
    TestMediaServer(Files.readAllBytes(media), chunkDelayMs = 10, honorRange = false).use { server ->
      val afterSeek =
        runBlocking {
          val format = AvFormat(server.url, IOHandler())
          try {
            val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
            val codec = AvCodec(video)
            try {
              // 先顺序读一点，让第一条响应与缓存往前推
              repeat(3) { format.getPacket(listOf(video))?.close() }
              // 容器级 seek 只落关键帧（每 2s 一个），落点之后全靠 IO 往前推
              format.seekTo(3_000_000)
              val pts = ArrayList<Long>()
              while (true) {
                val packet = format.getPacket(listOf(video)) ?: break
                packet.use { p ->
                  codec.sendPacketAndGetFrames(p).second?.forEach { frame ->
                    pts += frame.timeStamp
                    frame.close()
                  }
                }
              }
              pts
            } finally {
              codec.closeDeferred().join()
            }
          } finally {
            format.closeDeferred().join()
          }
        }
      // seek 落到 2s 关键帧，此后还剩 10s × 25fps = 250 帧。
      assertTrue(afterSeek.size > 200, "more than 200 frames should remain after the seek, got ${afterSeek.size}")
      // 必须正好是参考序列的一个连续后缀
      val startIndex = reference.indexOf(afterSeek.first())
      assertTrue(startIndex >= 0, "the first frame after seek ${afterSeek.first()} is not in the reference sequence")
      assertEquals(
        reference.drop(startIndex),
        afterSeek,
        "an HTTP source must not drop a single frame after a forward seek",
      )
    }
  }

  /** 向前 seek 落在通道存量之内时保住会话，越过存量才重开。 */
  @Test
  fun forwardSeekWithinBufferedRangeKeepsSessionButFarSeekDoesNot() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    TestMediaServer(Files.readAllBytes(media), chunkDelayMs = 10).use { server ->
      runBlocking {
        val format = AvFormat(server.url, IOHandler())
        try {
          val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
          val codec = AvCodec(video)
          try {
            // 顺序解到 1s（25 帧）：网络游标推到 1s 附近，通道里留下存量
            var decoded = 0
            while (decoded < 25) {
              val packet = format.getPacket(listOf(video)) ?: break
              packet.use { p -> codec.sendPacketAndGetFrames(p).second?.forEach { f -> f.close() } }
              decoded++
            }
            assertEquals(25, decoded, "sequential reads should decode all 25 frames (this media has 300)")
            assertEquals(1, server.requests, "sequential reads must not reopen the session")

            // 收益：2s 落在存量内 ⇒ 零网络成本 ⇒ 不该重开
            format.seekTo(2_000_000)
            format.getPacket(listOf(video))?.close()
            assertEquals(
              1,
              server.requests,
              "a 2s landing point is inside the buffered range with zero network cost and must not reopen " +
                "the session -- a new request means the criterion counted the already-buffered bytes as network cost.",
            )

            // 边界：再多跳 1s 越过存量 ⇒ 真要从网络取 ⇒ 必须重开一条会话
            // 先清掉这条流的块缓存：2s 那次读顺带把 3s 附近的字节也留住了（MP4 的 pb 缓冲是 MB 级），
            // 不清的话这次读会由内存满足、根本不碰会话 —— 那是缓存起作用的正确结果，但这条用例要守的
            // 是「越过存量必须重开会话」这条判据，得在无缓存干扰的状态下测。
            HttpCache.clear(server.url)
            format.seekTo(3_000_000)
            format.getPacket(listOf(video))?.close()
            assertEquals(
              2,
              server.requests,
              "a 3s landing point is beyond the buffered range and really needs the network, so a new " +
                "session must be opened (this is the 2nd one).",
            )
          } finally {
            codec.closeDeferred().join()
          }
        } finally {
          format.closeDeferred().join()
        }
      }
    }
  }

  /** 顺序解完整条视频流，返回解码序的全部帧 PTS（微秒）。 */
  private fun decodeVideoPts(format: AvFormat): List<Long> =
    runBlocking {
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val codec = AvCodec(video)
        try {
          val pts = ArrayList<Long>()
          while (true) {
            val packet = format.getPacket(listOf(video)) ?: break
            packet.use { p ->
              codec.sendPacketAndGetFrames(p).second?.forEach { frame ->
                pts += frame.timeStamp
                frame.close()
              }
            }
          }
          pts
        } finally {
          codec.closeDeferred().join()
        }
      } finally {
        format.closeDeferred().join()
      }
    }
}
