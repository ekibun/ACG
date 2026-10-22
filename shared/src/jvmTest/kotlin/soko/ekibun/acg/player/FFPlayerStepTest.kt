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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FFPlayer.stepForward] / [FFPlayer.stepBack] 前后的显示帧序列：前进单调推进，后退沿原路走回。 */
class FFPlayerStepTest {
  private fun List<Long?>.since(mark: Int): List<Long> = drop(mark).filterNotNull()

  @Test(timeout = 120_000)
  fun stepForwardAndBackWalkFrames() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val sink = TestSurface()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    runBlocking {
      val player =
        FFPlayer(media.toString(), IOHandler(), sink) {
          if (it is FFPlayer.Event.Frame) frames.add(it.pts)
        }
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // play() 挂到整轮结束，所以只 launch 不 await
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
        // 等到显示过至少一帧再暂停
        withTimeout(10_000) { while (frames.none { it != null }) delay(5) }
        player.pause()
        playJob.join()

        val beforeSteps = frames.size

        // 连续前进：每次恰好新增一帧，且时间戳单调前进
        val forward =
          (0 until 5).map {
            val mark = frames.size
            player.stepForward()
            frames.since(mark).singleOrNull()
              ?: error("stepForward should show exactly one frame, got ${frames.since(mark)}")
          }
        forward.zipWithNext().forEach { (a, b) ->
          assertTrue(b > a, "step timestamps must move forward: $a -> $b")
        }
        // 步长是恒定帧间隔（这条素材 25fps ⇒ 40000µs）
        val step = forward[1] - forward[0]
        forward.zipWithNext().forEach { (a, b) ->
          assertEquals(step, b - a, "frame interval must be constant: $forward")
        }

        // 连续后退：沿原路一帧一帧走回去
        val back =
          (0 until 4).map {
            val mark = frames.size
            assertTrue(player.stepBack(), "stepBack should succeed")
            frames.since(mark).singleOrNull()
              ?: error("stepBack should show exactly one frame, got ${frames.since(mark)}")
          }
        assertEquals(
          forward.dropLast(1).asReversed(),
          back,
          "stepping back should retrace the forward path one frame at a time: forward=$forward back=$back",
        )
        assertTrue(frames.size > beforeSteps, "stepping should keep producing Frame events")
      } finally {
        player.closeAsync()
      }
    }
  }
}
