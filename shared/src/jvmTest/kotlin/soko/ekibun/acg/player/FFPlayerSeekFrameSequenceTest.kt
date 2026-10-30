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
import soko.ekibun.ffmpeg.FFPlayer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 跳转之后再走（连续播放 / 前进一帧 / 后退一帧）时的显示帧序列与送显像素。 */
class FFPlayerSeekFrameSequenceTest {
  /** 送显侧的观测：每次送显记下整帧 RGBA 的哈希，顺序与 [FFPlayer.Event.Frame] 一一对应。 */
  private class HashSink {
    val hashes: MutableList<Int> = Collections.synchronizedList(ArrayList<Int>())

    val size: Int get() = hashes.size

    fun hashAt(index: Int): Int = hashes[index]
  }

  private fun newSink(): Pair<TestSurface, HashSink> {
    val recorded = HashSink()
    return TestSurface(onVideo = { buf -> recorded.hashes += fnv1a(buf) }) to recorded
  }

  private fun fnv1a(buf: ByteArray): Int {
    var h = 0x811c9dc5.toInt()
    for (b in buf) {
      h = (h xor b.toInt()) * 0x01000193
    }
    return h
  }

  /** 像素正本（帧时间戳 → 整帧哈希），跨用例共用。 */
  private val referencePixelsLock = Any()

  @Volatile
  private var referencePixelsMemo: Map<Long, Int>? = null

  /** 从 `frames` 里截出**本次动作新增**的那些非 null 时间戳。 */
  private fun List<Long?>.since(mark: Int): List<Long> = drop(mark).filterNotNull()

  /**
   * 开播放器。解码路径显式钉在 FFPlayer 的默认值上：像素正本必须与播放同源，
   * 见 [referencePixels]。
   */
  private fun openPlayer(
    surface: TestSurface,
    frames: MutableList<Long?>,
  ): FFPlayer =
    FFPlayer(
      TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(),
      IOHandler(),
      surface,
      hardware = true,
    ) { if (it is FFPlayer.Event.Frame) frames.add(it.pts) }

  /**
   * 顺序播到「显示过 [count] 帧」为止，随即暂停。
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

  /**
   * 顺序解完整条视频流，返回「帧时间戳 → 整帧哈希」的正本。
   *
   * 必须与播放走**同一条解码路径**：硬解出来是 NV12、软解是 YUV420P，经 sws 到 RGBA 有
   * 1~3 LSB 的舍入差（见 `AGENTS.md` §2.5 的 asm 判据），跨路径的整帧哈希不可能相等。
   * 判据「这一帧的像素属于它自己的时间戳」不受影响——错帧的差远大于 3 LSB。
   */
  private fun referencePixels(): Map<Long, Int> =
    runBlocking {
      val (sink, hashes) = newSink()
      val format =
        AvFormat(
          TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(),
          IOHandler(),
        )
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val codec = AvCodec(video, hardware = true)
        try {
          val pixels = HashMap<Long, Int>()

          suspend fun collect(frames: List<AvFrame>) =
            frames.forEach { frame ->
              sink.postFrame(AVMediaType.VIDEO, frame)
              sink.flushFrame(AVMediaType.VIDEO, frame)
              pixels[frame.timeStamp] = hashes.hashAt(hashes.size - 1)
              frame.close()
            }
          while (true) {
            val packet = format.getPacket(listOf(video)) ?: break
            collect(codec.sendPacketAndGetFrames(packet) ?: emptyList())
          }
          // 解码器重排缓冲里还压着尾帧：送个空包冲刷，一直收到吐干净
          while (true) {
            val tail = codec.sendPacketAndGetFrames() ?: break
            if (tail.isEmpty()) break
            collect(tail)
          }
          pixels
        } finally {
          codec.closeDeferred().join()
        }
      } finally {
        format.closeDeferred().join()
        sink.closeDeferred().join()
      }
    }

  /** 像素正本只解一遍，跨用例共用同一张表。 */
  private fun referencePixelsOnce(): Map<Long, Int> {
    synchronized(referencePixelsLock) {
      referencePixelsMemo?.let { return it }
      val computed = referencePixels()
      referencePixelsMemo = computed
      return computed
    }
  }

  /** 每一帧送显的像素都属于这一帧自己的时间戳。 */
  private fun assertPixelsMatch(
    shown: List<Long>,
    pixels: List<Int>,
    reference: Map<Long, Int>,
  ) {
    assertEquals(shown.size, pixels.size, "every flush should correspond to one Frame event")
    val bad = shown.zip(pixels).filter { (ts, hash) -> reference[ts] != hash }
    assertTrue(
      bad.isEmpty(),
      "these frames carry pixels from another frame's timestamp (corrupt picture): ${bad.map { it.first }}",
    )
  }

  /** 落点正好是 GOP 首帧时，后退一帧仍能退动（帧间隔已量到）。 */
  @Test(timeout = 180_000)
  fun stepBackFromKeyframeLanding() {
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    runBlocking {
      val player = openPlayer(newSink().first, frames)
      try {
        playUntilFramesShown(player, frames, count = 3)

        // 4_000_000µs 正好是关键帧（GOP 50 帧 @25fps ⇒ 每 2 秒一个）
        val landing = 4_000_000L
        val mark = frames.size
        player.seekTo(landing)
        assertEquals(listOf(landing), frames.since(mark), "seek should land on the keyframe and show only that frame")

        // 空探那一跳会先把当前帧再显示一次，取这一轮最后显示的那帧
        val back =
          (0 until 2).map {
            val m = frames.size
            assertTrue(player.stepBack(), "stepBack must succeed when the landing is a keyframe")
            frames.since(m).lastOrNull()
              ?: error("stepBack should show at least one frame, got ${frames.since(m)}")
          }
        assertEquals(
          listOf(landing - 40_000, landing - 80_000),
          back,
          "stepping back should walk one frame at a time (frame interval 40_000us)",
        )
      } finally {
        player.closeAsync()
      }
    }
  }

  /** 冷启动（还没量到帧间隔）时，落点在关键帧上的退帧也要退得动。 */
  @Test(timeout = 180_000)
  fun stepBackFromKeyframeLandingOnColdStart() {
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    val (sink, _) = newSink()
    runBlocking {
      val player = openPlayer(sink, frames)
      try {
        // 降到 1% 速度：第二帧要等 4 秒才送显，只显示过一帧就不必跟暂停抢时间
        sink.setSpeedRatio(0.01f)
        playUntilFramesShown(player, frames, count = 1)
        // 「还没量到间隔」是本用例的前提，当场钉住
        assertEquals(1, frames.count { it != null }, "cold start requires exactly one frame shown, got $frames")

        val landing = 4_000_000L
        player.seekTo(landing)

        // 空探那一跳会先把当前帧再显示一次，取这一轮最后显示的那帧
        val back =
          (0 until 2).map {
            val m = frames.size
            assertTrue(player.stepBack(), "stepBack must succeed on cold start when the landing is a keyframe")
            frames.since(m).lastOrNull()
              ?: error("stepBack should show at least one frame, got ${frames.since(m)}")
          }
        assertEquals(
          listOf(landing - 40_000, landing - 80_000),
          back,
          "stepping back should walk one frame at a time (frame interval 40_000us)",
        )
      } finally {
        player.closeAsync()
      }
    }
  }

  /** 跳转之后连续播放：帧序列一帧不落，且每帧像素都属于它自己的时间戳。 */
  @Test(timeout = 180_000)
  fun playAfterSeekShowsEachOwnFrame() {
    val reference = referencePixelsOnce()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    val (sink, hashes) = newSink()
    runBlocking {
      val player = openPlayer(sink, frames)
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
        withTimeout(10_000) { while (frames.count { it != null } < 3) delay(5) }

        // 播放中跳转：seekTo 自己会挂到这一轮结束，所以也要 launch
        val from = 4_000_000L
        val mark = frames.size
        val hashMark = hashes.size
        val seekJob = launch { player.seekTo(from) }
        withTimeout(60_000) { while ((frames.lastOrNull { it != null } ?: 0L) < 6_000_000L) delay(5) }
        player.pause()
        seekJob.join()
        playJob.join()

        // 跳转前那一轮可能还漏出几帧，两条列表一起丢掉以保持一一对应
        val stale = frames.since(mark).takeWhile { it < from }.size
        val shown = frames.since(mark).drop(stale)
        val pixels = (hashMark + stale until hashes.size).map { hashes.hashAt(it) }
        assertEquals(
          (100..150).map { it * 40_000L },
          shown.take(51),
          "continuous playback after the seek must not drop a frame (4.0s..6.0s is exactly 51 frames)",
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
    val reference = referencePixelsOnce()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    val (sink, hashes) = newSink()
    runBlocking {
      val player = openPlayer(sink, frames)
      try {
        playUntilFramesShown(player, frames, count = 3)

        val from = 4_000_000L
        player.seekTo(from)
        val steps =
          (1..30).map {
            val mark = frames.size
            val hashMark = hashes.size
            player.stepForward()
            val ts =
              frames.since(mark).singleOrNull()
                ?: error("stepForward should show exactly one frame, got ${frames.since(mark)}")
            ts to hashes.hashAt(hashMark)
          }
        assertEquals(
          (1..30).map { from + it * 40_000L },
          steps.map { it.first },
          "each stepForward must advance exactly one frame",
        )
        assertPixelsMatch(steps.map { it.first }, steps.map { it.second }, reference)
      } finally {
        player.closeAsync()
      }
    }
  }
}
