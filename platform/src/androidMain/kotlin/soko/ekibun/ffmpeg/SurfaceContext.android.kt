package soko.ekibun.ffmpeg

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.view.Surface
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class AndroidSurfaceContext(
  var surfaceTexture: SurfaceTexture,
) : SurfaceContext() {
  companion object {
    const val DEFAULT_RATE = 48000
    const val DEFAULT_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO

    /**
     * AudioTrack 的编码格式。native 按它转码，改这个值就能整体切音频位宽，不用动转换代码。
     * `ENCODING_PCM_8BIT` 并非不可用：AudioTrack 接受为合法 linear PCM，且 byte[] 写入路径
     * 不经过 short[] 那条 `> ENCODING_LEGACY_SHORT_ARRAY_THRESHOLD` 的限制分支
     * （只是「不保证所有设备支持」）。
     */
    const val DEFAULT_FORMAT = AudioFormat.ENCODING_PCM_8BIT
  }

  override val sampleRate: Int by lazy { audio.sampleRate }
  override val channels: Int by lazy { audio.channelCount }

  /**
   * 交给 native 的输出采样格式（AVSampleFormat）。必须由 [audio] 的编码反推、不能写常量：
   * 两者不一致时 AudioTrack 会把字节按错误的位宽解释（8bit 轨收到 float32 就是 4 倍速的噪声）。
   * lazy 是为了避开属性声明顺序（[audio] 声明在后面）；取值只发生在首次 postFrame 之前，
   * 那时 AudioTrack 早已构造完成。
   */
  override val audioFormat: Int by lazy {
    when (audio.audioFormat) {
      AudioFormat.ENCODING_PCM_8BIT -> AvFormat.AV_SAMPLE_FMT_U8
      AudioFormat.ENCODING_PCM_FLOAT -> AvFormat.AV_SAMPLE_FMT_FLT
      else -> AvFormat.AV_SAMPLE_FMT_S16
    }
  }

  val audio by lazy {
    val audioMode = AudioTrack.MODE_STREAM
    AudioTrack(
      AudioAttributes.Builder().build(),
      AudioFormat
        .Builder()
        .setSampleRate(DEFAULT_RATE)
        .setChannelMask(DEFAULT_CHANNEL)
        .setEncoding(DEFAULT_FORMAT)
        .build(),
      AudioTrack.getMinBufferSize(
        DEFAULT_RATE,
        DEFAULT_CHANNEL,
        DEFAULT_FORMAT,
      ),
      audioMode,
      AudioManager.AUDIO_SESSION_ID_GENERATE,
    )
  }

  var frameWrite = 0L

  override suspend fun flushAudioBuffer(buf: ByteArray): Int {
    val size = buf.size
    if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
    // 走 write(byte[], offset, size, mode) 这条：native 已经把数据拷进 buf，不再包一层
    if (size > 0) audio.write(buf, 0, size, AudioTrack.WRITE_BLOCKING)
    // AudioTrack 的 framePosition 以采样帧为单位，与 channels 无关，不要再除
    frameWrite += size / (audio.channelCount * bytesPerSampleOf(audio.audioFormat))
    val timestamp = AudioTimestamp()
    return if (audio.getTimestamp(timestamp)) {
      (frameWrite - timestamp.framePosition).toInt()
    } else {
      -1
    }
  }

  private fun bytesPerSampleOf(encoding: Int): Int =
    when (encoding) {
      AudioFormat.ENCODING_PCM_8BIT -> 1
      AudioFormat.ENCODING_PCM_FLOAT -> 4
      else -> 2
    }

  var bitmap: Bitmap? = null

  // 显式持有而不是 `by lazy`：close() 要能只还**已经建过**的 Surface ——
  // `by lazy` 读一次才会建，为了释放去读它就变成"没画过也建一个"。
  private var surfaceRef: Surface? = null

  val surface: Surface
    get() = surfaceRef ?: Surface(surfaceTexture).also { surfaceRef = it }

  val paint by lazy { Paint() }

  override suspend fun flushVideoBuffer(
    buf: ByteArray,
    width: Int,
    height: Int,
  ) {
    // 与桌面端同款早退：非正尺寸喂不进 createBitmap（它要求宽高为正）
    if (width <= 0 || height <= 0) return
    updateAspectRatio(width, height)
    surfaceTexture.setDefaultBufferSize(width, height)
    if (bitmap == null || bitmap?.width != width || bitmap?.height != height) {
      val oldBitmap = bitmap
      bitmap = createBitmap(width, height)
      oldBitmap?.recycle()
    }
    // copyPixelsFromBuffer 同步拷完，不留引用（native 下一帧会覆写 buf）
    bitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
    val canvas = surface.lockCanvas(null)
    canvas.drawBitmap(bitmap!!, 0f, 0f, paint)
    surface.unlockCanvasAndPost(canvas)
  }

  override suspend fun resume() = audio.play()

  override suspend fun pause() = audio.pause()

  override suspend fun stop() {
    audio.pause()
    frameWrite = 0
    audio.flush()
  }

  override fun close() {
    super.close()
    CoroutineScope(Dispatchers.IO).launch {
      stop()
      audio.release()
    }
    // Surface 是本类从 surfaceTexture 包出来的（TextureView 那侧
    // onSurfaceTextureDestroyed 恒 false、不代放），所有权在这里收口。
    surfaceRef?.release()
    surfaceRef = null
  }
}
