package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvSurfaceContext
import soko.ekibun.ffmpeg.FFPlayer
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单帧步进（[FFPlayer.stepForward] / [FFPlayer.stepBack]）前后的**显示帧序列**。
 *
 * 观测点是 [FFPlayer.Event.Frame]（`FFPlayer` 每显示一帧回调帧 PTS、播完回调 `null`）——
 * 这是播放器的**公开输出**，不是内部字段。判据直接落在「画面上换成了哪一帧」上：
 * 前进时时间戳单调推进，后退时沿原路一帧一帧走回去。
 *
 * 为什么值得留：这条路走错时的症状全是**不报错**的 —— 每按一次「前进一帧」跳过一帧、
 * 或退帧落到隔一帧的位置，光看画面看不出是代码错了。实测踩过一次（播放轮次结束时，
 * 队列把「已取到但没显示」的那一帧丢掉，于是只有一半的帧能靠步进走到），所以留个用例。
 *
 * 素材用随源码入库的 [TestMedia.BBB_640X360_12S_FASTSTART]（h264 25fps），换台机器 / CI 也真跑。
 */
class FFPlayerStepTest {
  /**
   * 空播放设备：不送显不送声；[FFPlayer.Event.Frame] 序列由测试侧的回调收进自己的列表。
   */
  private class Sink : AvSurfaceContext() {
    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    override suspend fun flushAudioBuffer(buf: ByteBuffer): Int = 0

    override fun flushVideoBuffer(
      buf: ByteBuffer,
      width: Int,
      height: Int,
    ) = Unit

    override suspend fun resume() = Unit

    override suspend fun pause() = Unit

    override suspend fun stop() = Unit
  }

  /** 从 `frames` 里截出**本次动作新增**的那些非 null 时间戳。 */
  private fun List<Long?>.since(mark: Int): List<Long> = drop(mark).filterNotNull()

  @Test(timeout = 120_000)
  fun stepForwardAndBackWalkFrames() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val sink = Sink()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    runBlocking {
      val player =
        FFPlayer(media.toString(), FileIO.Handler(), sink) {
          if (it is FFPlayer.Event.Frame) frames.add(it.pts)
        }
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // play() 要一直挂到整个播放轮次结束（withContext 会等自己的子协程，而轮次就是那个
        // 子协程），所以不能 await —— UI 里也是 fire-and-forget 地 launch。
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
        // 等到至少显示过一帧（Frame 事件非 null），再暂停 —— 步进要有「当前帧」才能走。
        withTimeout(10_000) { while (frames.none { it != null }) delay(5) }
        player.pause()
        playJob.join()

        val beforeSteps = frames.size

        // 连续前进：每一次「新增的显示帧」都要接得上，且时间戳必须单调前进。
        val forward =
          (0 until 5).map {
            val mark = frames.size
            player.stepForward()
            frames.since(mark).singleOrNull()
              ?: error("stepForward 应当恰好显示一帧，实际新增 ${frames.since(mark)}")
          }
        forward.zipWithNext().forEach { (a, b) ->
          assertTrue(b > a, "步进的时间戳必须前进：$a -> $b")
        }
        // 步长应当是恒定的帧间隔（这条素材 25fps ⇒ 40000µs）。
        val step = forward[1] - forward[0]
        forward.zipWithNext().forEach { (a, b) ->
          assertEquals(step, b - a, "帧间隔应当恒定：$forward")
        }

        // 连续后退：沿原路一帧一帧走回去，每一步的显示帧都要等于前进时的对应帧。
        val back =
          (0 until 4).map {
            val mark = frames.size
            assertTrue(player.stepBack(), "stepBack 应当成功")
            frames.since(mark).singleOrNull()
              ?: error("stepBack 应当恰好显示一帧，实际新增 ${frames.since(mark)}")
          }
        assertEquals(
          forward.dropLast(1).asReversed(),
          back,
          "后退应当沿前进的原路一帧一帧走回去：forward=$forward back=$back",
        )
        assertTrue(frames.size > beforeSteps, "步进应当持续产生 Frame 事件")
      } finally {
        player.closeAsync()
      }
    }
  }
}
