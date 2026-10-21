package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** `AVStream::time_base` 的暴露：一个 tick = [AvStream.timeBaseNum] / [AvStream.timeBaseDen] 秒。 */
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
