package soko.ekibun.ffmpeg

import androidx.compose.runtime.mutableLongStateOf
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 桌面端播放实现：音频走 javax.sound.sampled；视频把 native 经 `SetByteArrayRegion` 拷进复用
 * `ByteArray` 的整帧，经 `Image.makeRaster` 复制成一份 Skia 自管的 [currentFrame]
 * （[Image]），由 `VideoSurface.jvm.kt` 取 skiaCanvas 直接画。
 *
 * [frame] 是 Long 计数器（每帧自增），Compose 订阅它触发重绘 —— 与 mediamp 的 `frameTick`
 * 同一思路，但本端走 **CPU 上传** 路径：每帧 2 次 CPU 拷贝（makeRaster 一次 memcpy +
 * 绘制时一次 GPU 上传），与 Android 端持平；合并成单次传输的方向见 TODO B26。
 * `buf` 下一帧会被 native 就地覆写，故 makeRaster 必须**复制**而非 alias —— 换帧时旧
 * [Image] 被 [close]，不进 Cleaner；绘制方经 [getImage] 在锁内取当前帧、画完即弃，
 * 不得把 [Image] 引用留到绘制之外。
 *
 * [soko.ekibun.ffmpeg.AvSurfaceContext] 的 native 侧会按构造时传入的 audioFormat 用 swr_convert
 * 转码输出，因此这里的 audioFormat = AV_SAMPLE_FMT_FLT 意味着拿到的是 float32。
 * 桌面声卡用 16bit PCM，故在这里转换成 16bit：转换后每帧 4 字节，正好等于 48kHz 立体声
 * 16bit 的消耗速率，播放速度与真实时间一致。
 */
class DesktopSurfaceContext : SurfaceContext() {
  override val sampleRate: Int = 48000
  override val channels: Int = 2
  override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_FLT

  override suspend fun flushFrameImpl(
    codecType: Int,
    frame: AvFrame,
    getBuffer: () -> ByteArray?,
  ): Long {
    when (codecType) {
      AVMediaType.AUDIO -> {
        val offset = flushAudioBuffer(getBuffer() ?: return -1)
        return if (offset < 0) -1 else frame.timeStamp - offset * AvFormat.AV_TIME_BASE / sampleRate
      }
      AVMediaType.VIDEO -> {
        if (frame.width <= 0 || frame.height <= 0) return -1
        updateAspectRatio(frame.width, frame.height)
        val buf = getBuffer() ?: return -1
        val image =
          Image.makeRaster(
            ImageInfo(frame.width, frame.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE),
            buf,
            frame.width * 4,
          )
        synchronized(lock) {
          val oldImage = currentFrame
          currentFrame = image
          oldImage?.close()
        }
        frameState.value++
      }
    }
    return -1
  }

  private var lineRef: SourceDataLine? = null

  private val line: SourceDataLine
    get() = lineRef ?: openLine().also { lineRef = it }

  private fun openLine(): SourceDataLine {
    val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
    val info = DataLine.Info(SourceDataLine::class.java, format)
    return (AudioSystem.getLine(info) as SourceDataLine).also {
      it.open(format)
      it.start()
    }
  }

  var frameWrite = 0L

  fun flushAudioBuffer(buf: ByteArray): Int {
    val out = line
    // 对应 Android 端的 audio.playState != PLAYSTATE_PLAYING 时自动 play()。
    // 必须用 isRunning：向未 start 的 line 写入，缓冲区满后会永久阻塞。
    if (!out.isRunning) out.start()

    // `buf` 里是 native 拷出来的 float32；顺序必须显式设成 LITTLE_ENDIAN
    // （native 侧给的是主机字节序），否则 float 会被读反。
    val floats = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val frames = floats.limit() / channels
    if (frames > 0) {
      val pcm = ByteArray(frames * channels * 2)
      val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
      for (i in 0 until frames) {
        val l = floats.get(i * channels)
        val r = if (channels > 1) floats.get(i * channels + 1) else l
        shorts.put(i * channels, toPcm16(l))
        if (channels > 1) shorts.put(i * channels + 1, toPcm16(r))
      }
      out.write(pcm, 0, pcm.size)
    }
    frameWrite += frames
    // 与 Android 的 AudioTimestamp.framePosition 等价：已播放的帧数
    return (frameWrite - out.longFramePosition).toInt()
  }

  private fun toPcm16(value: Float): Short = (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

  private val frameState = mutableLongStateOf(0L)

  val frame get() = frameState.value

  var currentFrame: Image? = null

  private val lock = Any()

  fun getImage(block: (Image) -> Unit) {
    synchronized(lock) {
      val frame = currentFrame ?: return
      block(frame)
    }
  }

  override suspend fun resume() = line.start()

  override suspend fun pause() = line.stop()

  override suspend fun stop() {
    val out = line
    out.stop()
    out.flush()
    // DataLine 的帧计数「自 open 以来」累计，flush 不重置（AudioTrack 的会归零，别照搬）：
    // 这里重取基准，否则 flushFrame 算出的 offset 变负、seek 后 PTS 校正失效。
    frameWrite = out.longFramePosition
  }

  override fun close() {
    super.close()
    frameState.value = 0
    lineRef?.let {
      it.stop()
      it.flush()
      it.close()
    }
    lineRef = null
  }
}
