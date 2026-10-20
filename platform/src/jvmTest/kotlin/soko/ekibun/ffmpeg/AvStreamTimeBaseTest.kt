package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `AVStream::time_base` 的暴露 —— **流时间戳的最小刻度**（一个 tick = [AvStream.timeBaseNum] /
 * [AvStream.timeBaseDen] 秒）。
 *
 * 非暴露不可的理由只有一个：容器级 seek 传 `stream_index = -1` 时会把微秒折回这条刻度，折回是
 * **四舍五入** —— 偏移量不足半格就落回原格，等于没挪（[AvFormat.seekTo] 的 KDoc 记了 mp4 那条路）。
 * 上层要算出「比半格大」的步长，就非得先知道一格多大。
 *
 * 用合成 WAV 钉：PCM 走 `avpriv_set_pts_info(st, 64, 1, sample_rate)`（libavformat/wavdec.c），
 * 时基就是 **1 / 采样率**，刻度算得出来，不用依赖任何成品素材。素材时长为 1 s，与
 * [AvFormatDurationTest] 的 2 s 各用各的（后者要的是「总时长那段窗口足够宽」）。
 */
class AvStreamTimeBaseTest {
  @Test
  fun streamExposesItsTimeBase() {
    runBlocking {
      withTimeout(30_000) {
        AvFormat("memory:test.wav", TestIo(WavMedia.bytes(durationSec = 1))).use { format ->
          val stream = format.getStreams().single()
          assertEquals(1, stream.timeBaseNum, "a PCM WAV time base numerator is always 1")
          assertEquals(
            WavMedia.SAMPLE_RATE,
            stream.timeBaseDen,
            "a PCM WAV time base denominator is the sample rate (8 kHz means one tick is 125us)",
          )
        }
      }
    }
  }
}
