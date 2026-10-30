package soko.ekibun.ffmpeg

import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import java.awt.Window
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面端播放实现：音频走 javax.sound.sampled。
 *
 * 视频有两条路，优先零拷贝：[attachWindow] 在窗口的 Skia 画布上建好共享纹理输出端
 * （[HwVideoSink]），native 硬解出的帧在解码线程里就直接成了 Skia 的 GPU 图像，本类只把
 * 它画出来；建不起来时退回 `Image.makeRaster` 逐帧拷贝 —— native 把整帧经复用 `ByteArray`
 * 交给这里，`makeRaster` 复制成 Skia 自管的 [currentFrame]，由 `VideoSurface.jvm.kt` 画。
 *
 * [frame] 是每帧自增的计数器，Compose 订阅它触发重绘。`buf` 下一帧会被 native 就地覆写，
 * 所以 makeRaster 必须**复制**而非 alias；换帧时旧 [Image] 被 [close]。绘制方经 [drawFrame]
 * 取当前帧、画完即弃，不得把引用留到绘制之外。
 *
 * native 侧按构造时传入的 audioFormat 用 swr_convert 转码，所以这里拿到的
 * `AV_SAMPLE_FMT_FLT` 是 float32；桌面声卡用 16bit PCM，在本类里转换。
 */
class DesktopSurfaceContext : SurfaceContext() {
  override val sampleRate: Int = 48000
  override val channels: Int = 2
  override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_FLT

  /** 零拷贝输出端；null = 还没建起来，视频走 CPU 拷贝。 */
  private var sink: HwVideoSink? = null
  private var hardwareRenderer: HwFrameRenderer? = null

  override val hardwareOutput: Any? get() = sink?.nativeHandle

  /** 视频是否走零拷贝（共享纹理）路径；面板不支持的机器上为 false，退回落帧拷贝。 */
  val isHardwareVideo: Boolean get() = sink != null

  /** 共享纹理是否已经成功画到画布上过（零拷贝路径真的通了，而不只是建起来了）。 */
  @Volatile
  var isHardwareVideoDrawn: Boolean = false
    private set

  /**
   * 在当前窗口的 Skia 画布上建零拷贝输出端。窗口还没画出第一帧时 Skiko 的渲染设备
   * 还不存在，所以轮询到 [timeoutMs]；建不起来就一直是 CPU 路径。
   *
   * 必须在播放器建立之前完成：解码器的输出形态在 open 时定死。
   */
  suspend fun attachWindow(
    window: Window,
    timeoutMs: Long = 3000,
  ) {
    if (sink != null) return
    var waited = 0L
    while (waited <= timeoutMs) {
      if (tryAttach(window)) return
      delay(16.milliseconds)
      waited += 16
    }
  }

  private fun tryAttach(window: Window): Boolean {
    if (sink != null) return true
    val layer = window.findSkiaLayer() ?: return false
    val renderer = HwFrameRenderer(layer)
    val devicePtr = renderer.devicePtr()
    if (devicePtr == 0L) {
      renderer.close()
      return false
    }
    val created = HwVideoSink.create(devicePtr)
    if (created == null) {
      renderer.close()
      return false
    }
    sink = created
    hardwareRenderer = renderer
    return true
  }

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
        // 有缓冲就是内存形态的帧（软解回落、或没有输出端），转成 Skia 图像；
        // 零拷贝帧在 renderSurfaceFrameNative 里已经画进共享纹理，这里只触发重绘。
        val buf = getBuffer()
        if (buf != null) {
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

  /**
   * 把当前帧画到画布上。零拷贝路径交给 [HwFrameRenderer]（GPU 图像，不复制）；退路画
   * [currentFrame]。只能在 Compose 的绘制调用里用 —— 共享纹理的包装依赖画布当前的 Skia 上下文。
   */
  fun drawFrame(
    canvas: org.jetbrains.skia.Canvas,
    width: Float,
    height: Float,
  ) {
    val hardwareSink = sink
    val renderer = hardwareRenderer
    if (hardwareSink != null && renderer != null) {
      val image = renderer.image(hardwareSink)
      if (image != null) {
        isHardwareVideoDrawn = true
        canvas.drawImageRect(image, Rect(0f, 0f, width, height))
        return
      }
    }
    getImage { image -> canvas.drawImageRect(image, Rect(0f, 0f, width, height)) }
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
    // 画布侧的对象先放，再放 native 的共享纹理环。
    hardwareRenderer?.close()
    hardwareRenderer = null
    sink?.close()
    sink = null
    lineRef?.let {
      it.stop()
      it.flush()
      it.close()
    }
    lineRef = null
  }
}
