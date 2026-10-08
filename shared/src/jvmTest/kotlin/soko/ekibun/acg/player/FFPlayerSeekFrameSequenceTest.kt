package soko.ekibun.acg.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvFrame
import soko.ekibun.ffmpeg.AvSurfaceContext
import soko.ekibun.ffmpeg.FFPlayer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跳转之后再走（连续播放 / 前进一帧 / 后退一帧）时的**显示帧序列**与**送显像素**。
 *
 * 观测点全是播放器的**公开输出**：[FFPlayer.Event.Frame] 的帧时间戳，与
 * [AvSurfaceContext.flushVideoBuffer] 拿到的 RGBA。像素的参考是「同一条流顺序解一遍」的整帧哈希 ——
 * 从 IDR 起解码是确定的，所以「这一帧该长什么样」不依赖它之前播过什么。素材帧栅格已知
 * （25fps、GOP 50 ⇒ 时间戳恒为 40_000µs 的整数倍）⇒ 序列连续可直接对期望序列下断言。
 *
 * 边界：只到送显为止（再往后的 Skia / Compose 看不见），素材也只有 8bit H.264 一条；
 * 「跳转时丢包」那条老缺陷现在复现不出来 —— 任何丢弃都伴随换位置（会重读）。
 */
class FFPlayerSeekFrameSequenceTest {
  /**
   * 空播放设备：不送显不送声，只把每次送显的 RGBA 整帧哈希记下来。
   *
   * 顺序与 [FFPlayer.Event.Frame] 一一对应 —— 送显之后必回调一次，回调的正是刚送显那一帧。
   */
  private class Sink : AvSurfaceContext() {
    private val hashes = Collections.synchronizedList(ArrayList<Int>())

    val size: Int get() = hashes.size

    fun hashAt(index: Int): Int = hashes[index]

    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    override suspend fun flushAudioBuffer(buf: ByteArray): Int = 0

    override suspend fun flushVideoBuffer(
      buf: ByteArray,
      width: Int,
      height: Int,
    ) {
      hashes += fnv1a(buf)
    }

    override suspend fun resume() = Unit

    override suspend fun pause() = Unit

    override suspend fun stop() = Unit

    private fun fnv1a(buf: ByteArray): Int {
      var h = 0x811c9dc5.toInt()
      for (b in buf) {
        h = (h xor b.toInt()) * 0x01000193
      }
      return h
    }
  }

  /** 从 `frames` 里截出**本次动作新增**的那些非 null 时间戳。 */
  private fun List<Long?>.since(mark: Int): List<Long> = drop(mark).filterNotNull()

  private fun openPlayer(
    sink: Sink,
    frames: MutableList<Long?>,
  ): FFPlayer =
    FFPlayer(
      TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(),
      FileIO.Handler(),
      sink,
    ) { if (it is FFPlayer.Event.Frame) frames.add(it.pts) }

  /**
   * 顺序播到「显示过 [count] 帧」为止，随即暂停。
   *
   * [FFPlayer.play] 要挂到整个轮次结束，所以不能 await —— UI 里也是 fire-and-forget 地 launch。
   * [count] 决定退帧空探走哪条路：帧间隔只在「真看到连续两帧」时才量得到，所以 **1 帧 = 冷启动**
   * （步长只能靠流时基推），**≥3 帧 = 量到了间隔**（步长取间隔的 3/4）。
   */
  private suspend fun CoroutineScope.playUntilFramesShown(
    player: FFPlayer,
    frames: List<Long?>,
    count: Int,
  ) {
    val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
    val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
    withTimeout(10_000) { while (frames.count { it != null } < count) delay(5) }
    player.pause()
    playJob.join()
  }

  /** 顺序解完整条视频流，返回「帧时间戳 → 该帧 RGBA 整帧哈希」—— 像素身份的正本。 */
  private fun referencePixels(): Map<Long, Int> =
    runBlocking {
      val sink = Sink()
      val format =
        AvFormat(
          TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(),
          FileIO.Handler(),
        )
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val codec = AvCodec(video)
        try {
          val pixels = HashMap<Long, Int>()

          suspend fun collect(frames: List<AvFrame>) =
            frames.forEach { frame ->
              sink.postFrame(AVMediaType.VIDEO, frame)
              sink.flushFrame(AVMediaType.VIDEO, frame)
              pixels[frame.timeStamp] = sink.hashAt(sink.size - 1)
              frame.close()
            }
          while (true) {
            val packet = format.getPacket(listOf(video)) ?: break
            collect(codec.sendPacketAndGetFrames(packet))
          }
          // 解码器重排缓冲里还压着尾帧（有 B 帧时末两帧就是），不 drain 参考里就没有它们。
          collect(codec.drain())
          pixels
        } finally {
          codec.closeDeferred().join()
        }
      } finally {
        format.closeDeferred().join()
        sink.closeDeferred().join()
      }
    }

  /** 断言每一帧送显的像素都是**这一帧自己的** —— 对不上就是「画面与时间戳不符」（花屏）。 */
  private fun assertPixelsMatch(
    shown: List<Long>,
    pixels: List<Int>,
    reference: Map<Long, Int>,
  ) {
    assertEquals(shown.size, pixels.size, "每次送显都应当对应一个 Frame 事件")
    val bad = shown.zip(pixels).filter { (ts, hash) -> reference[ts] != hash }
    assertTrue(
      bad.isEmpty(),
      "这些帧送显的像素不属于它自己的时间戳（花屏）：${bad.map { it.first }}",
    )
  }

  /**
   * 后退一帧在「落点正好是 GOP 首帧」时也要能退动。
   *
   * 落点就是关键帧 ⇒ 收敛一帧都没丢 ⇒ 「前一帧」只能靠空探一跳补回来；空探的步长先取自量到的帧
   * 间隔（本素材 40_000µs 的 3/4 = 30_000µs），远大于时基的半格（tb 1/12800 ⇒ 39µs）。
   * 冷启动（还没量到间隔）那条路单独由 [stepBackFromKeyframeLandingOnColdStart] 钉着。
   */
  @Test(timeout = 180_000)
  fun stepBackFromKeyframeLanding() {
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    runBlocking {
      val player = openPlayer(Sink(), frames)
      try {
        playUntilFramesShown(player, frames, count = 3)

        // 4_000_000µs 正好是关键帧（GOP 50 帧 @25fps ⇒ 每 2 秒一个）。
        val landing = 4_000_000L
        val mark = frames.size
        player.seekTo(landing)
        assertEquals(listOf(landing), frames.since(mark), "seek 应当落在关键帧上、且只显示那一帧")

        // 空探那一跳会先把当前帧再显示一次（画面不动），所以取这一轮**最后**显示的那帧。
        val back =
          (0 until 2).map {
            val m = frames.size
            assertTrue(player.stepBack(), "落点在关键帧上时退帧也要成功")
            frames.since(m).lastOrNull()
              ?: error("stepBack 应当至少显示一帧，实际新增 ${frames.since(m)}")
          }
        assertEquals(
          listOf(landing - 40_000, landing - 80_000),
          back,
          "退帧应当一帧一帧往回走（帧间隔 40_000µs）",
        )
      } finally {
        player.closeAsync()
      }
    }
  }

  /**
   * 冷启动（还没量到帧间隔）时，落点在关键帧上的退帧也要退得动。
   *
   * 与 [stepBackFromKeyframeLanding] 只差一处：**只显示一帧就暂停** ⇒ `frameIntervalUs` 仍是 null ⇒
   * 空探的步长只能由流时基推出来（tb 1/12800 ⇒ 一格 78.125µs、取 3/4 格 ≈ 59µs，刚好卡在
   * 「半格 39µs」与「一个帧间隔 40_000µs」之间）。老行为是退回 1µs —— 不足半格会被折回原格，
   * 一帧都不丢 ⇒ 前一帧探不出来 ⇒ 退帧返回 `false`、画面原地不动。
   */
  @Test(timeout = 180_000)
  fun stepBackFromKeyframeLandingOnColdStart() {
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    val sink = Sink()
    runBlocking {
      val player = openPlayer(sink, frames)
      try {
        // 顺序播放的送显节奏是「等主时钟走到这一帧的时间戳」，而主时钟按 speedRatio 走 —— 调到 1%
        // ⇒ 第二帧（40_000µs）要等 4 秒才送显，「刚好只显示过一帧」就不必跟暂停抢那几十毫秒。
        // 步进 / 收敛轮不走这个节奏（FFPlayer 里那段等待只在 onNextFrame 不活跃时才进），后面的退帧不受影响。
        sink.setSpeedRatio(0.01f)
        playUntilFramesShown(player, frames, count = 1)
        // 「还没量到间隔」是本用例的全部前提，当场钉住：多显示一帧就退化成上面那条用例了。
        assertEquals(1, frames.count { it != null }, "冷启动要求只显示过一帧，实际 $frames")

        val landing = 4_000_000L
        player.seekTo(landing)

        // 空探那一跳会先把当前帧再显示一次（画面不动），所以取这一轮**最后**显示的那帧。
        val back =
          (0 until 2).map {
            val m = frames.size
            assertTrue(player.stepBack(), "冷启动 + 落点在关键帧上时退帧也要成功")
            frames.since(m).lastOrNull()
              ?: error("stepBack 应当至少显示一帧，实际新增 ${frames.since(m)}")
          }
        assertEquals(
          listOf(landing - 40_000, landing - 80_000),
          back,
          "退帧应当一帧一帧往回走（帧间隔 40_000µs）",
        )
      } finally {
        player.closeAsync()
      }
    }
  }

  /**
   * 跳转之后连续播放：帧序列要一帧不落，且**每一帧的像素都属于它自己的时间戳**。
   *
   * 跳转的窗口里读包与换轮是并行的：包一旦从 demuxer 读出来就没有「放回去」这一说，
   * 丢一个就少一个参考帧，其后一路错到下一个 IDR —— 症状是跳帧加花屏，只在跳转后这一段出现。
   */
  @Test(timeout = 180_000)
  fun playAfterSeekShowsEachOwnFrame() {
    val reference = referencePixels()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    val sink = Sink()
    runBlocking {
      val player = openPlayer(sink, frames)
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
        withTimeout(10_000) { while (frames.count { it != null } < 3) delay(5) }

        // 播放中跳转：上一轮的读包还在飞，新一轮就起跑。seekTo 自己会挂到这一轮结束，所以也要 launch。
        val from = 4_000_000L
        val mark = frames.size
        val hashMark = sink.size
        val seekJob = launch { player.seekTo(from) }
        withTimeout(60_000) { while ((frames.lastOrNull { it != null } ?: 0L) < 6_000_000L) delay(5) }
        player.pause()
        seekJob.join()
        playJob.join()

        // 跳转前那一轮可能还漏出几帧（时间戳小于落点），两条列表一起丢掉，保持一一对应。
        val stale = frames.since(mark).takeWhile { it < from }.size
        val shown = frames.since(mark).drop(stale)
        val pixels = (hashMark + stale until sink.size).map { sink.hashAt(it) }
        assertEquals(
          (100..150).map { it * 40_000L },
          shown.take(51),
          "跳转后连续播放应当一帧不落（4.0s..6.0s 正好 51 帧）",
        )
        assertPixelsMatch(shown, pixels, reference)
      } finally {
        player.closeAsync()
      }
    }
  }

  /** 跳转之后连续前进一帧：每按一次只走一帧，且送显的像素属于那一帧。 */
  @Test(timeout = 180_000)
  fun stepForwardAfterSeekShowsEachOwnFrame() {
    val reference = referencePixels()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    val sink = Sink()
    runBlocking {
      val player = openPlayer(sink, frames)
      try {
        playUntilFramesShown(player, frames, count = 3)

        val from = 4_000_000L
        player.seekTo(from)
        val steps =
          (1..30).map {
            val mark = frames.size
            val hashMark = sink.size
            player.stepForward()
            val ts =
              frames.since(mark).singleOrNull()
                ?: error("stepForward 应当恰好显示一帧，实际新增 ${frames.since(mark)}")
            ts to sink.hashAt(hashMark)
          }
        assertEquals(
          (1..30).map { from + it * 40_000L },
          steps.map { it.first },
          "每按一次前进一帧都应当只走一帧",
        )
        assertPixelsMatch(steps.map { it.first }, steps.map { it.second }, reference)
      } finally {
        player.closeAsync()
      }
    }
  }
}
