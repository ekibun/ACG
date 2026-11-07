package soko.ekibun.ffmpeg

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** 播放设备假件：不接声卡不上屏，只把观测值交出来。要真画面走 `DesktopSurfaceContext`。 */
class TestSurface(
  rate: Int = 48_000,
  private val paceAudio: Boolean = false,
  private val onVideo: ((ByteArray) -> Unit)? = null,
  private val onAudio: ((ByteArray) -> Unit)? = null,
  /** 音频帧报自己的时间戳；关着时返回 0，播放时钟就停在那儿。 */
  private val reportAudioTs: Boolean = false,
) : AvSurfaceContext() {
  override val sampleRate: Int = rate
  override val channels: Int = 2
  override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

  private val videoFrames = AtomicInteger(0)
  private val audioFlushes = AtomicInteger(0)

  /** 已送显的视频帧数。 */
  val frameCount: Int get() = videoFrames.get()

  /** 已送到平台侧的音频帧数。 */
  val audioFrameCount: Int get() = audioFlushes.get()

  override suspend fun flushFrameImpl(
    codecType: Int,
    frame: AvFrame,
    getBuffer: () -> ByteArray?,
  ): Long =
    when (codecType) {
      AVMediaType.VIDEO -> {
        videoFrames.incrementAndGet()
        getBuffer()?.let { onVideo?.invoke(it) }
        -1
      }

      AVMediaType.AUDIO -> {
        audioFlushes.incrementAndGet()
        val buffer = getBuffer() ?: return -1
        onAudio?.invoke(buffer)
        val samples = buffer.size / (channels * 2)
        if (paceAudio && samples > 0) delay((samples * 1000L / sampleRate).coerceAtLeast(1).milliseconds)
        if (reportAudioTs) frame.timeStamp else 0
      }

      else -> -1
    }

  override suspend fun resume() = Unit

  override suspend fun pause() = Unit

  override suspend fun stop() = Unit
}
