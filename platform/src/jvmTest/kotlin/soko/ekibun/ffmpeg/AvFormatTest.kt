package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `AvFormat` 的整条 demux 面：开流 → 元信息 → 读包 → 失败收场，外加 HLS 广告分片。
 * 开流失败不许漏进 JVM（`testing.md` §2.3 / §2.4），读包出错折 EOF（§2.2），广告分片不交上层。
 */
class AvFormatTest {
  private companion object {
    /** 广告的声明区间：Σ EXTINF(主片 6×2.0) 到 +（1.7 × 3）。 */
    const val AD_FROM = 12_000_000L
    const val AD_TO = 17_100_000L

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

  /** 假 IO：首次 open 给真数据（播放列表），其余恒失败，并记录每个交出的句柄有没有被 close。 */
  private class HlsSegmentFailureHandler(
    private val playlist: ByteArray,
  ) : AvIO.Handler() {
    /** 每次 open 收到的 URL，按到达顺序。 */
    val openedUrls = mutableListOf<String>()
    var openedCount = 0
    var closedCount = 0

    override fun open(url: String): AvIO {
      openedUrls += url
      val isPlaylist = openedCount++ == 0
      val delegate: AvIO = if (isPlaylist) TestIO(playlist).open(url) else FailingAvIO
      return object : AvIO by delegate {
        override fun close() {
          closedCount++
          delegate.close()
        }
      }
    }
  }

  /** 包一层「第 [failAfter] 次成功 read 之后就开始返回 -1」的 Handler。 */
  private class FailingAfterHandler(
    private val failAfter: Int,
    private val inner: AvIO.Handler,
  ) : AvIO.Handler() {
    var reads = 0

    override fun open(url: String): AvIO {
      val delegate = inner.open(url)
      return object : AvIO by delegate {
        override fun read(
          buf: ByteArray,
          len: Int,
        ): Int {
          if (++reads > failAfter) return -1
          return delegate.read(buf, len)
        }
      }
    }
  }

  /** 正常流程：开流之后格式级与流级总时长都是微秒，值域按 `AV_TIME_BASE` 给。 */
  @Test(timeout = 30_000)
  fun durationsAreMicroseconds() {
    runBlocking {
      withTimeout(30_000) {
        AvFormat("memory:test.wav", TestIO(WavMedia.bytes(durationSec = 2))).use { format ->
          // 时长在 getStreams 之后才可读
          val stream = format.getStreams().single()
          val duration = format.getDurationUs()
          assertTrue(
            duration in 1_900_000..2_100_000,
            "format duration should be ~2s in µs, got $duration",
          )
          assertTrue(
            stream.duration in 1_900_000..2_100_000,
            "stream duration should be ~2s in µs (AV_TIME_BASE, not stream time base), got ${stream.duration}",
          )
        }
      }
    }
  }

  /** 嵌套 `io_open` 失败：分片那次 open 真的发生过，每个交出去的句柄恰好关一次。 */
  @Test(timeout = 60_000)
  fun nestedOpenFailureClosesEveryHandle() {
    val handler = HlsSegmentFailureHandler(PLAYLIST)
    assertFailsWith<IllegalStateException>("open must fail as a handle-less init, not crash native") {
      runBlocking {
        withTimeout(30_000) {
          AvFormat("memory:test.m3u8", handler).getStreams()
        }
      }
    }
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

  /** `open` 抛异常：由 JNI 就地消化（`Describe` + `Clear`），不许 pending 到下一次 JNI 调用。 */
  @Test(timeout = 60_000)
  fun throwingOpenIsFoldedByJni() {
    assertFailsWith<IllegalStateException>("a throwing open must be folded by JNI, not crash the JVM") {
      runBlocking {
        withTimeout(30_000) {
          AvFormat(
            "memory:test.wav",
            object : AvIO.Handler() {
              override fun open(url: String): AvIO = error("open must not escape into JNI")
            },
          ).getStreams()
        }
      }
    }
  }

  /** `open` 不抛、返回恒失败的 IO：由 `avformat_open_input` 自己失败，Kotlin 侧收成 `IllegalStateException`。 */
  @Test(timeout = 60_000)
  fun failingOpenFailsAtOpenInput() {
    assertFailsWith<IllegalStateException>("a handle-less source must fail at avformat_open_input") {
      runBlocking {
        withTimeout(30_000) {
          AvFormat(
            "memory:nope.mp4",
            object : AvIO.Handler() {
              override fun open(url: String): AvIO = FailingAvIO
            },
          ).getStreams()
        }
      }
    }
  }

  /** 读包出错：`getPacket` 以 null 收场（不挂死、不抛），且必须留下那条 error 日志。 */
  @Test(timeout = 60_000)
  fun ioErrorFoldsToEofButIsLogged() {
    // 第 4 次 read 起返回 -1：前 3 次留给 find_stream_info，错误落在 getPacket 阶段
    val wav = WavMedia.bytes(durationSec = 10)
    val out = ByteArrayOutputStream()
    val originalOut = System.out
    System.setOut(PrintStream(out, true, Charsets.UTF_8))
    try {
      runBlocking {
        withTimeout(30_000) {
          AvFormat("memory:test.wav", FailingAfterHandler(failAfter = 3, inner = TestIO(wav))).use { player ->
            player.getStreams()
            while (true) {
              // downloader 模式：包拿到就还，所有权归调用方
              val packet = player.getPacket(emptyList()) ?: break
              packet.close()
            }
          }
        }
      }
    } finally {
      System.setOut(originalOut)
    }
    assertTrue(
      out.toString(Charsets.UTF_8).contains("[AvFormat] av_read_frame error"),
      "IO error must be logged instead of folding silently; stdout was: $out",
    )
  }

  /** 广告分片的包根本不交给上层，接上的第一包仍是关键帧（否则解码器没有参考帧）。 */
  @Test(timeout = 120_000)
  fun adSegmentsNeverReachTheCaller() =
    runBlocking {
      val pts = ArrayList<Long>()
      // 时间戳 → 标志位。跳过广告后接上的那一包必须还是关键帧。
      val packetFlags = HashMap<Long, Int>()
      val format = AvFormat(TestMedia.file(TestMedia.HLS_AD_PLAYLIST).absolutePath, TestLocalIO())
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        while (true) {
          val packet = format.getPacket(listOf(video)) ?: break
          if (packet.ptsUs >= 0) {
            pts.add(packet.ptsUs)
            packetFlags[packet.ptsUs] = packet.flags
          }
          packet.close()
        }
      } finally {
        format.resetChannel()
        format.closeDeferred().join()
      }

      assertTrue(pts.size > 200, "only ${pts.size} video packets received")

      // 包序是解码序（B 帧会让 pts 前后跳一帧），找空档要先排序
      val ordered = pts.sorted()
      val gaps = ordered.zipWithNext().filter { (a, b) -> b - a > 1_000_000 }
      assertEquals(1, gaps.size, "exactly one ad gap expected, got $gaps")
      val (lastBefore, firstAfter) = gaps.single()
      // 分片首包是音频，视频帧因此在声明轴上整体偏后一帧多一点（实测 +0.167 s），边界吃这个余量
      assertTrue(
        lastBefore in (AD_FROM - 1_000_000)..(AD_FROM + 500_000),
        "the gap must start before the ad, got $lastBefore",
      )
      assertTrue(
        firstAfter in AD_TO..(AD_TO + 1_000_000),
        "the gap must end past the ad end, got $firstAfter",
      )

      // 分片边界会带出相邻段的余帧（±一帧），所以只查广告中段
      val leaked = ordered.filter { it > AD_FROM + 500_000 && it < AD_TO - 500_000 }
      assertTrue(
        leaked.isEmpty(),
        "ad segment packets must not reach the caller, got ${leaked.size}: ${leaked.take(10)}",
      )

      assertTrue(
        ordered.last() > 24_500_000,
        "the main feature must play to the end after the ad, last is ${ordered.last()}",
      )

      // 跳段之后接上的第一包必须是关键帧。丢首帧时从第二包开始读，整片都没有参考帧（花屏）。
      val resumed = ordered.first { it >= AD_TO - 200_000 }
      assertTrue(
        (packetFlags[resumed] ?: 0) and AvFormat.AV_PKT_FLAG_KEY != 0,
        "the first packet after the ad skip must be a keyframe, got pts=$resumed flags=${packetFlags[resumed]}",
      )
    }
}
