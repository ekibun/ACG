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

/**
 * 一次**可能永久阻塞**的 [AvIO.read]：丢到**守护线程**上跑，`join` 带超时。
 *
 * 播放与 HTTP 用例里要测的正是「读可能永远不回来」（abort 叫醒、会话作废、卡在 socket 上），
 * 但一次没回来的读会让整个构建挂住。超时就把线程栈打出来再失败，报告里能直接看出卡在哪。
 *
 * 线程必须是守护线程：挂在非守护线程上的永久阻塞会让测试 JVM 退不出去。
 */
internal fun AvIO.readBounded(
  size: Int,
  timeoutMs: Long = 10_000,
): Int {
  val result = AtomicInteger(Int.MIN_VALUE)
  val reader =
    Thread { result.set(read(ByteArray(size))) }.apply {
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

/**
 * 播放用例共用的**空播放设备**：不接 SDL / 声卡，只把每个解码帧的观测值交给调用方。
 *
 * 为什么共用一个类而不是各文件各写一份：五个用例的 `AvSurfaceContext` 空实现逐字相同，
 * 差异只在「观测哪一列」—— 那正是构造参数该表达的东西。判据各不相同，但设备行为一致。
 *
 * 三条可配的行为：
 *
 * - [onVideo]：视频帧交给平台后调一次（像素就在 `getBuffer()` 里，调用方自己决定记什么）；
 * - [onAudio]：音频帧交给平台后调一次（参数是这一帧的样本数）；
 * - [paceAudio]：音频分支按样本数 `delay` 当**声卡节拍**，让播放按真实时长推进。
 *   只给「需要播放自然走一段墙钟」的用例用（如 stall 现场），其余用例关掉它以换速度。
 *
 * 返回值约定照 [AvSurfaceContext.flushFrame]：返回 0 = 没有重采样前导偏移、时间戳原样上屏。
 */
internal class TestSurface(
  /** 声明给 native 的重采样目标；只决定 native 输出什么格式，与判据无关。 */
  rate: Int = 48_000,
  private val paceAudio: Boolean = false,
  private val onVideo: ((ByteArray) -> Unit)? = null,
  private val onAudio: ((samples: Int) -> Unit)? = null,
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
        0
      }

      else -> -1
    }

  override suspend fun resume() = Unit

  override suspend fun pause() = Unit

  override suspend fun stop() = Unit
}
