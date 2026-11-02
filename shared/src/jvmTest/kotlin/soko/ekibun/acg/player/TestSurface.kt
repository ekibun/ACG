package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvFrame
import soko.ekibun.ffmpeg.AvIO
import soko.ekibun.ffmpeg.AvSurfaceContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** 丢到守护线程上带超时地跑一次 [AvIO.read]；超时打印线程栈再失败。 */
internal fun AvIO.readBounded(
  size: Int,
  timeoutMs: Long = 10_000,
): Int {
  val result = AtomicInteger(Int.MIN_VALUE)
  val reader =
    Thread { result.set(read(ByteArray(size), size)) }.apply {
      isDaemon = true
      start()
    }
  reader.join(timeoutMs)
  if (reader.isAlive) {
    println("[diag] read($size) did not return within ${timeoutMs}ms, stack:\n" + reader.stackTrace.joinToString("\n"))
    throw AssertionError("read($size) did not return within ${timeoutMs}ms (see stack on stdout)")
  }
  return result.get()
}

/** 播放用例共用的空播放设备：不接 SDL / 声卡，只把每个解码帧的观测值交给调用方。 */
internal class TestSurface(
  /** 声明给 native 的重采样目标。 */
  rate: Int = 48_000,
  private val paceAudio: Boolean = false,
  private val onVideo: ((ByteArray) -> Unit)? = null,
  private val onAudio: ((samples: Int) -> Unit)? = null,
  /**
   * 音频帧回报它自己的时间戳（平台侧的真实行为）。默认 `false` 时返回 0，
   * 播放时钟就停在那儿 —— 只有视频轴被判据用到的用例才需要打开它。
   */
  private val reportAudioTs: Boolean = false,
) : AvSurfaceContext() {
  override val sampleRate: Int = rate
  override val channels: Int = 2
  override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

  private val videoFrames = Collections.synchronizedList(ArrayList<Long>())
  private val audioFlushes = AtomicInteger(0)

  /** 已送显的视频帧数。 */
  val frameCount: Int get() = synchronized(videoFrames) { videoFrames.size }

  /** 已送到平台侧的音频帧数。 */
  val audioFrameCount: Int get() = audioFlushes.get()

  override suspend fun flushFrameImpl(
    codecType: Int,
    frame: AvFrame,
    getBuffer: () -> ByteArray?,
  ): Long =
    when (codecType) {
      AVMediaType.VIDEO -> {
        videoFrames += System.nanoTime()
        getBuffer()?.let { onVideo?.invoke(it) }
        -1
      }

      AVMediaType.AUDIO -> {
        audioFlushes.incrementAndGet()
        val buffer = getBuffer() ?: return -1
        val samples = buffer.size / (channels * 2)
        onAudio?.invoke(samples)
        if (paceAudio && samples > 0) delay((samples * 1000L / sampleRate).coerceAtLeast(1).milliseconds)
        if (reportAudioTs) frame.timeStamp else 0
      }

      else -> -1
    }

  override suspend fun resume() = Unit

  override suspend fun pause() = Unit

  override suspend fun stop() = Unit
}
