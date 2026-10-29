package soko.ekibun.ffmpeg

import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面端播放实现：音频走 javax.sound.sampled；视频把 native 拷进复用 `ByteArray` 的整帧经
 * `Image.makeRaster` 复制成 Skia 自管的 [currentFrame]，由 `VideoSurface.jvm.kt` 取 skiaCanvas 画。
 *
 * [frame] 是每帧自增的计数器，Compose 订阅它触发重绘（CPU 上传路径，每帧两次拷贝）。
 * `buf` 下一帧会被 native 就地覆写，所以 makeRaster 必须**复制**而非 alias；换帧时旧 [Image] 被
 * [close]。绘制方经 [getImage] 在锁内取当前帧、画完即弃，不得把引用留到绘制之外。
 *
 * native 侧按构造时传入的 audioFormat 用 swr_convert 转码，所以这里拿到的
 * `AV_SAMPLE_FMT_FLT` 是 float32；桌面声卡用 16bit PCM，在本类里转换。
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

  /**
   * 播放意图。写循环靠它判断暂停是否已落地 —— `SourceDataLine.isRunning` 不能拿来当这个判据，
   * 它跟的是 `started` 字段，缓冲区一欠载就翻成 `false`（JDK `AbstractDataLine.java:216-232`）。
   */
  @Volatile
  private var wantPlaying = false

  /** 一帧 PCM 的字节数（16 bit）。`write` 只收整帧，否则抛 `IllegalArgumentException`。 */
  private val frameBytes get() = channels * 2

  suspend fun flushAudioBuffer(buf: ByteArray): Int {
    val out = line
    // 声卡欠载会自己停住，写进去也播不出来
    if (wantPlaying && !out.isRunning) out.start()

    // 显式按 LITTLE_ENDIAN 读。
    val floats = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val frames = floats.limit() / channels
    val pcm = ByteArray(frames * frameBytes)
    if (frames > 0) {
      val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
      for (i in 0 until frames) {
        val l = floats.get(i * channels)
        val r = if (channels > 1) floats.get(i * channels + 1) else l
        shorts.put(i * channels, toPcm16(l))
        if (channels > 1) shorts.put(i * channels + 1, toPcm16(r))
      }
    }
    val written = writePcm(out, pcm)
    // 只记真写进去的量：被打断时剩下的没进声卡
    frameWrite += written / frameBytes
    // 没写完说明这一拍被暂停打断，不给时钟
    if (written < pcm.size) return -1
    // 与 Android 的 framePosition 等价：已播放的帧数
    return (frameWrite - out.longFramePosition).toInt()
  }

  /**
   * 分片写：一次只写 [SourceDataLine.available] 那么多，写不进去就让出线程。
   *
   * 阻塞写会把整条播放线程钉在声卡上 —— 音频与视频的送显作业共用这条线程，音频队列满的那
   * 几百毫秒里视频一帧都送不出去，画面停一下再连补几帧，时钟读数也跟着台阶化。它还是条死路：
   * [pause] / [stop] 之后缓冲区不再被消费，阻塞中的写永远等不到空间。
   *
   * @return 实际写进去的字节数；被暂停打断时小于 [pcm] 的长度。
   */
  private suspend fun writePcm(
    out: SourceDataLine,
    pcm: ByteArray,
  ): Int {
    var offset = 0
    while (offset < pcm.size) {
      // 暂停 / 停止已落地：剩下的不写了，收场归 pause() / stop()
      if (!wantPlaying) return offset
      // available() 按字节报、不保证整帧，向下取整到帧边界
      val room = out.available() / frameBytes * frameBytes
      if (room <= 0) {
        delay(1.milliseconds)
        continue
      }
      val wrote = out.write(pcm, offset, minOf(room, pcm.size - offset))
      if (wrote <= 0) {
        delay(1.milliseconds)
        continue
      }
      offset += wrote
    }
    return offset
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

  override suspend fun resume() {
    wantPlaying = true
    line.start()
  }

  override suspend fun pause() {
    wantPlaying = false
    line.stop()
  }

  override suspend fun stop() {
    wantPlaying = false
    val out = line
    out.stop()
    out.flush()
    // 重取帧计数基准。校正失效
    frameWrite = out.longFramePosition
  }

  override fun close() {
    wantPlaying = false
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
