package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvPlayback
import soko.ekibun.ffmpeg.FFPlayer
import soko.ekibun.ffmpeg.WavMedia
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 纯音频文件的 `play()` 真路径回归。
 *
 * 读循环只由 [FFPlayer.resume] 启动，而续播判据曾经闸在「有无视频轨」上 ——
 * 纯音频文件 `play()` 不读任何包（无声）、`seekTo()` 停住不续播（seek 的续播
 * 与 play 走同一个门）。现有素材（`.workbuddy/test.mp4`）都带视频轨，测试抓不到，
 * 所以这里现场合成一个纯音频 WAV（见 [WavMedia]，不进仓库）。
 *
 * 假声卡 [FakePlayback] 只数「音频帧到达平台侧」的次数，不排真声卡 —— 音频
 * 路径没有实时节流（等时钟的 `delay` 只在视频分支），整轮会以解码速度跑完。
 */
class FFPlayerAudioOnlyTest {
  // `events` 收**普通参数**（不带 val）：super 实参位置的 lambda 若引用构造
  // 属性会隐式捕获还没初始化的 `this`（Pointer 类文档记过的坑）；测试侧持有
  // 同一份列表，照样读得到。
  private class FakePlayback(
    events: MutableList<Long?>,
  ) : AvPlayback({ events.add(it) }, { _ -> }) {
    val flushes = AtomicInteger(0)

    override val sampleRate: Int = WavMedia.SAMPLE_RATE
    override val channels: Int = WavMedia.CHANNELS
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    override suspend fun flushAudioBuffer(buf: ByteBuffer): Int {
      flushes.incrementAndGet()
      // 返回 0 = 没有重采样前导偏移，时间戳原样上屏（见 AvPlayback.flushFrame）。
      return 0
    }

    override fun flushVideoBuffer(
      buf: ByteBuffer,
      width: Int,
      height: Int,
    ) {}

    override suspend fun resume() {}

    override suspend fun pause() {}

    override suspend fun stop() {}
  }

  @Test
  fun audioOnlyPlayRunsToEof() {
    val events: MutableList<Long?> = Collections.synchronizedList(mutableListOf<Long?>())
    val playback = FakePlayback(events)
    val wav = WavMedia.write(durationSec = 2)
    runBlocking {
      val player = FFPlayer(wav.toString(), FileIO.Handler(), playback)
      try {
        val audio = player.getStreams().first { it.codecType == AVMediaType.AUDIO }
        // play() 要 fire-and-forget：withContext 只等到 seek/resume 排程完，
        // 整轮挂在 playingJob 上 —— UI 里也是这么调的。
        launch { player.play(mapOf(AVMediaType.AUDIO to audio), 0) }
        withTimeout(30_000) {
          while (events.isEmpty() || events.last() != null) delay(10)
        }
        assertTrue(playback.flushes.get() > 0, "audio frames must reach the platform side")
        assertTrue(events.contains(null), "EOF must be signaled via onFrame(null)")
      } finally {
        player.closeAsync()
      }
    }
  }
}
