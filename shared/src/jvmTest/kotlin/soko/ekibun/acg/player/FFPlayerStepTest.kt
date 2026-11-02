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

  /**
   * 有音频流时退帧同样要退得动。退帧轮把目标帧定在 `resyncTo`，音频帧比画面先越过它 ——
   * 那是音频时间轴在推进，不是「seek 落到了后面」，不能据此收掉整轮。
   */
  @Test(timeout = 120_000)
  fun stepBackSucceedsWithAudioStreamPlaying() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    runBlocking {
      // 音频帧报自己的时间戳、并按实时节奏放：画面才会走到片中，而不是停在第一帧
      val player =
        FFPlayer(media.toString(), IOHandler(), TestSurface(paceAudio = true, reportAudioTs = true)) {
          if (it is FFPlayer.Event.Frame) frames.add(it.pts)
        }
      try {
        val streams = player.getStreams().associateBy { it.codecType }
        assertTrue(
          streams.containsKey(AVMediaType.AUDIO),
          "用例前提：素材要带音频流，实际 ${streams.keys}",
        )
        val playJob = launch { player.play(streams, 0) }
        withTimeout(20_000) { while ((frames.lastOrNull() ?: 0) < 6_000_000) delay(10) }
        player.pause()
        playJob.join()
        val shown = frames.filterNotNull()
        assertTrue(shown.last() >= 5_000_000, "用例前提：暂停时要停在片中，实际 ${shown.takeLast(3)}")

        // 连退两次：每次都必须真的退动，而不是被音频帧提前收轮
        repeat(2) {
          assertTrue(player.stepBack(), "有音频流时退帧不该失败（第 $it 次）")
        }
      } finally {
        player.closeAsync()
      }
    }
  }

  /**
   * 片尾退一帧：解码器重排缓冲里的尾帧必须能在「是不是前一帧」判定之前进队列（见
   * `playback-invariants` 1.8）。drain 排在读循环之后、而循环以「队列空」为退出条件时，退帧轮里
   * 队列不会空（有一帧正等着判），尾帧永远出不来 —— 判据等满超时后把倒数第三帧当成前一帧，
   * 或者说末尾两帧之间留出一个帧间隔的空洞。
   */
  @Test(timeout = 120_000)
  fun stepBackAtEndOfStreamShowsTheFrameBeforeLast() {
    val media = TestMedia.path(TestMedia.HLS_AD_PLAYLIST)
    val sink = TestSurface()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())

    fun snapshot() = synchronized(frames) { frames.toList() }

    runBlocking {
      val player =
        FFPlayer(media.toString(), IOHandler(), sink) {
          if (it is FFPlayer.Event.Frame) frames.add(it.pts)
        }
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // 从片尾前 5 秒起播，别为了一帧播满整段
        val playJob =
          launch { player.play(mapOf(AVMediaType.VIDEO to video), 24_000_000) }
        // 播到片尾：末尾会发一条 Frame(null)
        withTimeout(60_000) { while (snapshot().none { it == null }) delay(10) }
        playJob.join()

        val shown = snapshot().filterNotNull()
        val last = shown.last()
        val previous = shown[shown.size - 2]
        // 常规帧间隔：取末尾若干次相邻差值的众数（24fps 下是 41666/41667 交替，允许一格的抖动）
        val interval =
          shown
            .takeLast(21)
            .zipWithNext { a, b -> b - a }
            .groupingBy { it }
            .eachCount()
            .maxBy { it.value }
            .key
        assertTrue(
          last - previous <= interval + interval / 2,
          "末尾两帧之间有一个帧间隔的空洞（帧间隔 $interval）: $previous -> $last",
        )
        val mark = snapshot().size

        assertTrue(player.stepBack(), "stepBack at the end of the stream must succeed")
        assertEquals(
          listOf(previous),
          snapshot().drop(mark).filterNotNull(),
          "退帧必须只退一帧：末帧=$last 次末帧=$previous",
        )
      } finally {
        player.closeAsync()
      }
    }
  }
}
