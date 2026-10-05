package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真远程 HTTPS 源的端到端回归（生产路径，即 [soko.ekibun.acg.ui.screen.PlayScreen] 走的那条）。
 * 与本地服务端的几个用例（[HttpReadContractTest] / [HttpStreamingTest] / [HttpAbortTest]）互补：那些把
 * **契约**钉死，这个用**真实网络**回答另一个问题 —— 整条链路（DNS / TCP / TLS / 重定向 / 代理 /
 * 真实 moov 布局 / Range 语义）在现实里真能跑起来吗。
 *
 * 本工程把 ffmpeg 的 URL 访问换成了 `io_open` 回调（`AVFMT_FLAG_CUSTOM_IO`），而 ffmpeg 自己**不带
 * 网络协议** ⇒「网络到底通不通」完全取决于 Kotlin 侧这一层。素材是 W3C 的公开测试片
 * （`Accept-Ranges: bytes`，直接 200 无重定向）⇒ **有网才行**，离线失败是预期行为。断言的是「形状」
 * 而不是精确包数 / 帧数；`timeout` 给 180 s —— 远程慢起来没有上限，宁可慢也不假死。
 */
class RealHttpReadTest {
  private val url = "https://media.w3.org/2010/05/sintel/trailer.mp4"

  /** 读 [count] 次包、解码全部帧，返回 (包数, 帧数, 最后一个视频帧的时间戳µs)。 */
  private suspend fun drain(
    format: AvFormat,
    codec: AvCodec,
    video: AvStream,
    count: Int,
  ): Triple<Int, Int, Long> {
    var packets = 0
    var frames = 0
    var lastTs = -1L
    repeat(count) {
      val packet = format.getPacket(listOf(video)) ?: return@repeat
      packets++
      codec.sendPacketAndGetFrames(packet).forEach {
        frames++
        lastTs = it.timeStamp
        it.close()
      }
      packet.close()
    }
    return Triple(packets, frames, lastTs)
  }

  @Test(timeout = 180_000)
  fun opensAndDecodesOverRealHttps() {
    runBlocking {
      val format = AvFormat(url, HttpIO.Handler())
      try {
        val streams = format.getStreams()
        // 真实源的形状：至少一视频、一音频，视频是 854x480。
        // 尺寸写死是安全的 —— 这是固定素材的公开测试片，且尺寸变了这条用例本来就该报警。
        val video = streams.firstOrNull { it.codecType == AVMediaType.VIDEO }
        assertTrue(video != null, "must find a video stream over real HTTPS")
        assertEquals(854, video.width, "video width")
        assertEquals(480, video.height, "video height")
        assertEquals(
          1,
          streams.count { it.codecType == AVMediaType.AUDIO },
          "exactly one audio stream",
        )
        // duration 是**按 stream time base 折算**的（AvStream 的构造参数），
        // 不是 AV_TIME_BASE。素材 52.2 s，这里只卡一个宽区间：0 或负数说明 moov 没读全。
        assertTrue(video.duration > 40_000_000L, "duration should be ~52s, got ${video.duration}")

        val codec = AvCodec(video)
        try {
          val (packets, frames, _) = drain(format, codec, video, 40)
          assertTrue(packets > 0, "must read at least one packet over real HTTPS")
          assertTrue(frames > 0, "must decode at least one frame over real HTTPS")
        } finally {
          codec.closeDeferred().join()
        }
      } finally {
        format.closeDeferred().join()
      }
    }
  }

  @Test(timeout = 180_000)
  fun seeksOverRealHttps() {
    runBlocking {
      val format = AvFormat(url, HttpIO.Handler())
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val codec = AvCodec(video)
        try {
          val (_, headFrames, headTs) = drain(format, codec, video, 20)
          assertTrue(headFrames > 0, "must decode frames before the seek")

          format.seekTo(20_000_000L) // 20 s，AV_TIME_BASE

          val (_, tailFrames, tailTs) = drain(format, codec, video, 30)
          assertTrue(tailFrames > 0, "must decode frames after the seek over real HTTPS")
          // 这条才是「seek 真的生效」的判据：没 seek 的话 tail 会接在 head 后面（20 包 ≈ 1 s 内），
          // 到不了 15 s。只断言「seek 后能解出帧」的话，seek 被整个忽略也能过。
          assertTrue(
            tailTs > 15_000_000L,
            "timestamp after seek should be near 20s, got $tailTs (head was $headTs)",
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
