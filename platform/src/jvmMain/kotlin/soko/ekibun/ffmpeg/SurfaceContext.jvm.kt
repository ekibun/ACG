package soko.ekibun.ffmpeg

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 桌面端播放实现：音频走 javax.sound.sampled；视频写进**复用的 skiko 位图**（[frame]），
 * 由 `VideoSurface.jvm.kt` 取 skiaCanvas 直接画 —— 不每帧新建对象，理由见 [getBitmap]。
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

  override suspend fun flushAudioBuffer(buf: ByteArray): Int {
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

  private val frameState = mutableStateOf<Bitmap?>(null, policy = neverEqualPolicy())

  /**
   * 最新一帧，供 Compose 绘制。
   *
   * 它是**复用**的位图（内容每帧被覆写），不是快照 —— 绘制方要留住像素必须自己拷一份
   * （见 `VideoSurface.jvm.kt`）。靠「换块」让引用变化，Compose 据此重组 + 重绘。
   */
  val frame: Bitmap? get() = frameState.value

  var currentFrame: Bitmap? = null

  private fun getBitmap(
    width: Int,
    height: Int,
  ): Bitmap {
    val bitmap = currentFrame
    if (bitmap == null || bitmap.width != width || bitmap.height != height) {
      val newBitmap =
        Bitmap().apply {
          allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))
        }
      currentFrame = newBitmap
      bitmap?.close()
      return newBitmap
    }
    return bitmap
  }

  override suspend fun flushVideoBuffer(
    buf: ByteArray,
    width: Int,
    height: Int,
  ) = withContext(Dispatchers.Main) {
    if (width <= 0 || height <= 0) return@withContext
    updateAspectRatio(width, height)
    val target = getBitmap(width, height)
    target.installPixels(target.imageInfo, buf, width * 4)
    frameState.value = target
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
    frameState.value = null
    lineRef?.let {
      it.stop()
      it.flush()
      it.close()
    }
    lineRef = null
  }
}
