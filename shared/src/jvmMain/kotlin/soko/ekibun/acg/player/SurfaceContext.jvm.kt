package soko.ekibun.acg.player

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import soko.ekibun.ffmpeg.AvFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 桌面端播放实现：音频走 javax.sound.sampled；视频写进**复用的 skiko 位图**（[frame]），
 * 由 `VideoSurface.jvm.kt` 取 skiaCanvas 直接画 —— 不每帧新建对象，理由见 [nextFrameBitmap]。
 *
 * [soko.ekibun.ffmpeg.AvSurfaceContext] 的 native 侧会按构造时传入的 audioFormat 用 swr_convert
 * 转码输出，因此这里的 audioFormat = AV_SAMPLE_FMT_FLT 意味着拿到的是 float32。
 * 桌面声卡用 16bit PCM，故在这里转换成 16bit：转换后每帧 4 字节，正好等于 48kHz 立体声
 * 16bit 的消耗速率，播放速度与真实时间一致。
 */
class DesktopSurfaceContext : SurfaceContext() {
  private val playbackDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

  private companion object {
    /** 送显位图池深度：写入块与「刚发布出去的那块」至少隔这么多帧才可能撞上。 */
    const val FRAME_POOL = 4
  }

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

  override suspend fun flushAudioBuffer(buf: ByteBuffer): Int =
    withContext(playbackDispatcher) {
      val out = line
      // 对应 Android 端的 audio.playState != PLAYSTATE_PLAYING 时自动 play()。
      // 必须用 isRunning：向未 start 的 line 写入，缓冲区满后会永久阻塞。
      if (!out.isRunning) out.start()

      // `buf` 是 native 内存上的 direct buffer（零拷贝来的），这里只读它；顺序必须显式设成
      // LITTLE_ENDIAN（native 侧给的是主机字节序），否则 float 会被读反。
      val floats = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
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

  private fun toPcm16(value: Float): Short = (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

  /**
   * 送显位图池 —— **复用**：skiko 位图是 native 内存、靠 `Cleaner` 回收，而本进程 GC 十秒才来
   * 一次，每帧 3.67 MB 的位图会攒到 GB 级、RSS 不还（实测见 `silent-failures.md`
   * 「skiko 对象泄漏」条）。池一直强引用 ⇒ 不进 Cleaner、RSS 有上界；池深 [FRAME_POOL] 即撕裂
   * 窗口（写入块与「刚发布出去的那块」至少隔 [FRAME_POOL] 帧 = 池深 × 帧间隔）。
   */
  private val framePool = ArrayList<Bitmap>()
  private var framePoolWidth = 0
  private var framePoolHeight = 0
  private var framePoolIndex = 0

  private val frameState = mutableStateOf<Bitmap?>(null)

  /**
   * 最新一帧，供 Compose 绘制。
   *
   * 它是**复用**的位图（内容每帧被覆写），不是快照 —— 绘制方要留住像素必须自己拷一份
   * （见 `VideoSurface.jvm.kt`）。靠「换块」让引用变化，Compose 据此重组 + 重绘。
   */
  val frame: Bitmap? get() = frameState.value

  private fun nextFrameBitmap(
    width: Int,
    height: Int,
  ): Bitmap {
    if (framePoolWidth != width || framePoolHeight != height) {
      framePool.forEach { it.close() }
      framePool.clear()
      framePoolWidth = width
      framePoolHeight = height
      framePoolIndex = 0
    }
    if (framePool.isEmpty()) {
      repeat(FRAME_POOL) {
        framePool +=
          Bitmap().apply {
            allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))
          }
      }
    }
    framePoolIndex = (framePoolIndex + 1) % framePool.size
    return framePool[framePoolIndex]
  }

  override fun flushVideoBuffer(
    buf: ByteBuffer,
    width: Int,
    height: Int,
  ) {
    if (width <= 0 || height <= 0) return
    updateAspectRatio(width, height)
    val src = addressOf(buf)
    if (src == 0L) return
    val target = nextFrameBitmap(width, height)
    val dst = target.peekPixels()?.addr ?: return
    // 一次 memcpy 写进复用位图（native 输出就是 RGBA_8888/不透明，与池里那块的 ImageInfo 一致）
    copyPixels(src, dst, width * 4 * height)
    frameState.value = target
  }

  override suspend fun resume() =
    withContext(playbackDispatcher) {
      line.start()
    }

  override suspend fun pause() =
    withContext(playbackDispatcher) {
      line.stop()
    }

  override suspend fun stop() =
    withContext(playbackDispatcher) {
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
    framePool.forEach { it.close() }
    framePool.clear()
    lineRef?.let {
      it.stop()
      it.flush()
      it.close()
    }
    lineRef = null
    playbackDispatcher.close()
  }
}
