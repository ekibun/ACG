package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.FFPlayer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 只把音频流交给 `play()` 时能跑到 EOF：音频帧到达平台侧，并以 `Event.Frame(null)` 收场。
 */
class FFPlayerAudioOnlyTest {
  @Test(timeout = 120_000)
  fun audioOnlyPlayRunsToEof() {
    val events: MutableList<Long?> = Collections.synchronizedList(mutableListOf<Long?>())
    // 重采样目标取素材自己的规格（44.1kHz 立体声）
    val playback = TestSurface(rate = 44_100)
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    runBlocking {
      val player =
        FFPlayer(media.toString(), IOHandler(), playback) {
          if (it is FFPlayer.Event.Frame) {
            events.add(it.pts)
          }
        }
      try {
        val audio = player.getStreams().first { it.codecType == AVMediaType.AUDIO }
        // 只把音频流传进去
        // play() 要 fire-and-forget：整轮挂在 playingJob 上
        launch { player.play(mapOf(AVMediaType.AUDIO to audio), 0) }
        withTimeout(30_000.milliseconds) {
          while (events.isEmpty() || events.last() != null) delay(10.milliseconds)
        }
        assertTrue(playback.audioFrameCount > 0, "audio frames must reach the platform side")
        assertTrue(events.contains(null), "EOF must be signaled via Event.Frame(null)")
      } finally {
        player.closeAsync()
      }
    }
  }
}
