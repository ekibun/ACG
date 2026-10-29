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

/**
 * 带音频的素材做单帧步进时，本轮仍要报 `Event.Frame`。
 *
 * 步进轮把音频整轮静音（静音的帧在送显之前就返回，不会报时间戳），若时间轴又被限定成
 * 「只由音频推进」，这一轮就没有任何事件，画面动了进度条却停住。
 */
class FFPlayerStepTimelineTest {
  @Test(timeout = 120_000)
  fun stepReportsFrameWithAudioPresent() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val sink = TestSurface(paceAudio = true)
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    runBlocking {
      val player =
        FFPlayer(media.toString(), IOHandler(), sink) {
          if (it is FFPlayer.Event.Frame) frames.add(it.pts)
        }
      try {
        val streams = player.getStreams()
        val picked =
          mapOf(
            AVMediaType.VIDEO to streams.first { it.codecType == AVMediaType.VIDEO },
            AVMediaType.AUDIO to streams.first { it.codecType == AVMediaType.AUDIO },
          )
        val playJob = launch { player.play(picked, 0) }
        withTimeout(20_000) { while (frames.none { it != null }) delay(5) }
        player.pause()
        playJob.join()

        val mark = frames.size
        player.stepForward()
        val shown = frames.drop(mark).filterNotNull()
        assertTrue(shown.isNotEmpty(), "步进一帧后必须报一次 Event.Frame，实际新增 ${frames.drop(mark)}")
      } finally {
        player.closeAsync()
      }
    }
  }
}
