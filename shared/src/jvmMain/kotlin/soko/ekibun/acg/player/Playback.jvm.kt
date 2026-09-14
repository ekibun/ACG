package soko.ekibun.acg.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import soko.ekibun.ffmpeg.AvFormat

/**
 * 桌面端播放实现：音频走 javax.sound.sampled，视频渲染为 ImageBitmap 交给 Compose 绘制。
 *
 * [soko.ekibun.ffmpeg.AvPlayback] 的 native 侧会按构造时传入的 audioFormat 用 swr_convert
 * 转码输出，因此这里的 audioFormat = AV_SAMPLE_FMT_FLT 意味着拿到的是 float32。
 * 桌面声卡用 16bit PCM，故在这里转换成 16bit：转换后每帧 4 字节，正好等于 48kHz 立体声
 * 16bit 的消耗速率，播放速度与真实时间一致。
 */
class DesktopPlayback(onFrame: (Long?) -> Unit) : Playback(onFrame) {
  private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

  override val sampleRate: Int = 48000
  override val channels: Int = 2
  override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_FLT
  override val videoFormat: Int = AvFormat.AV_PIX_FMT_RGBA

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

  override suspend fun flushAudioBuffer(buf: ByteArray): Int = withContext(dispatcher) {
    val out = line
    // 对应 Android 端的 audio.playState != PLAYSTATE_PLAYING 时自动 play()。
    // 必须用 isRunning：向未 start 的 line 写入，缓冲区满后会永久阻塞。
    if (!out.isRunning) out.start()

    val floats = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val frames = floats.limit() / channels
    if (frames > 0) {
      val mute = channels == 2 && isMuteVoice
      val pcm = ByteArray(frames * channels * 2)
      val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
      for (i in 0 until frames) {
        val l = floats.get(i * channels)
        val r = if (channels > 1) floats.get(i * channels + 1) else l
        // 人声消除：左右声道相减
        val outL = if (mute) l - r else l
        val outR = if (mute) l - r else r
        shorts.put(i * channels, toPcm16(outL))
        if (channels > 1) shorts.put(i * channels + 1, toPcm16(outR))
      }
      out.write(pcm, 0, pcm.size)
    }
    frameWrite += frames
    // 与 Android 的 AudioTimestamp.framePosition 等价：已播放的帧数
    (frameWrite - out.longFramePosition).toInt()
  }

  private fun toPcm16(value: Float): Short =
    (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

  private val imageState = mutableStateOf<ImageBitmap?>(null)

  /** 最新一帧，供 Compose 绘制 */
  val image: ImageBitmap? get() = imageState.value

  override fun flushVideoBuffer(buf: ByteArray, width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    updateAspectRatio(width, height)
    // makeRaster 会复制像素，得到不可变快照，可安全跨线程发布
    val frame = Image.makeRaster(
      ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE),
      buf,
      width * 4
    )
    try {
      imageState.value = frame.toComposeImageBitmap()
    } finally {
      frame.close()
    }
  }

  override suspend fun resume() = withContext(dispatcher) {
    line.start()
  }

  override suspend fun pause() = withContext(dispatcher) {
    line.stop()
  }

  override suspend fun stop() = withContext(dispatcher) {
    val out = line
    out.stop()
    out.flush()
    // 注意：Android 的 AudioTrack.flush() 会把播放头归零，但 DataLine 的帧计数
    // 是「自 open 以来」累计的，flush 不会重置。这里重新取基准，否则 flushFrame
    // 算出的 offset 会变成负数（返回 -1），seek 之后 PTS 校正就失效了。
    frameWrite = out.longFramePosition
  }

  override fun close() {
    super.close()
    imageState.value = null
    lineRef?.let {
      it.stop()
      it.flush()
      it.close()
    }
    lineRef = null
    dispatcher.close()
  }
}
