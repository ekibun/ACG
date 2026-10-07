package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 总时长的取数路径：总时长只在**格式层**（`AVFormatContext.duration`），HLS 这类容器
 * 把分片 EXTINF 求和写在那里、每条流上恒为 NOPTS —— JNI 侧把 NOPTS 记 0（乘出去是
 * 垃圾值）。这里用 WAV 钉住 [AvFormat.getDurationUs] 的值域与单位（微秒）；流时长
 * （`AvStream.duration`）对真有流级时长的容器的语义由 `RealHttpReadTest` 钉着。
 */
class AvFormatDurationTest {
  @Test
  fun formatDurationIsMicroseconds() {
    runBlocking {
      withTimeout(30_000) {
        AvFormat("memory:test.wav", TestIo(WavMedia.bytes(durationSec = 2))).use { format ->
          // WAV 的时长要 find_stream_info（getStreams）估算完才有；HLS 在 open 时就有。
          format.getStreams()
          val duration = format.getDurationUs()
          assertTrue(
            duration in 1_900_000..2_100_000,
            "format duration should be ~2s in µs, got $duration",
          )
        }
      }
    }
  }
}
