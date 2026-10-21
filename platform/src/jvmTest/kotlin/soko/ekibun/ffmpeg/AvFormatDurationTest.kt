package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** 格式级与流级总时长都取自 `find_stream_info` 之后的元信息，单位微秒。 */
class AvFormatDurationTest {
  @Test
  fun formatAndStreamDurationsAreMicroseconds() {
    runBlocking {
      withTimeout(30_000) {
        AvFormat("memory:test.wav", TestIo(WavMedia.bytes(durationSec = 2))).use { format ->
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
}
