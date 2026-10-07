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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class AndroidSurfaceContext(
  var surfaceTexture: SurfaceTexture,
) : SurfaceContext() {
  private val playbackDispatcher by lazy {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  }

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

  override suspend fun flushAudioBuffer(buf: ByteBuffer): Int =
    withContext(playbackDispatcher) {
      val size = buf.remaining()
      if (channels == 2 && isMuteVoice) {
        // 左右声道相减（人声消除）。步长是每个采样点的字节数，不是固定 2：
        // native 可能给 8bit(1) / 16bit(2) / float32(4)，按 2 走会串位。
        val bytesPerSample =
          when (audio.audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
          }
        val frameBytes = bytesPerSample * 2
        var i = 0
        while (i + frameBytes <= size) {
          for (b in 0 until bytesPerSample) {
            val diff =
              (buf.get(i + b).toInt() and 0xFF) - (buf.get(i + bytesPerSample + b).toInt() and 0xFF)
            // 8bit 是 unsigned，以 128 为零点，消声后要加回偏置
            val v = if (bytesPerSample == 1) diff + 128 else diff
            buf.put(i + b, v.toByte())
            buf.put(i + bytesPerSample + b, v.toByte())
          }
          i += frameBytes
        }
      }
      if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
      // `buf` 是 direct buffer，走 write(ByteBuffer, size, mode) 这条（省一次 Java 数组拷贝）
      if (size > 0) audio.write(buf, size, AudioTrack.WRITE_BLOCKING)
      // AudioTrack 的 framePosition 以采样帧为单位，与 channels 无关，不要再除
      frameWrite += size / (audio.channelCount * bytesPerSampleOf(audio.audioFormat))
      val timestamp = AudioTimestamp()
      if (audio.getTimestamp(timestamp)) {
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

  override fun flushVideoBuffer(
    buf: ByteBuffer,
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
    // buf 是 direct buffer，copyPixelsFromBuffer 原地收下（同步拷完，不留引用）
    bitmap!!.copyPixelsFromBuffer(buf)
    val canvas = surface.lockCanvas(null)
    canvas.drawBitmap(bitmap!!, 0f, 0f, paint)
    surface.unlockCanvasAndPost(canvas)
  }

  override suspend fun resume() =
    withContext(playbackDispatcher) {
      audio.play()
    }

  override suspend fun pause() =
    withContext(playbackDispatcher) {
      audio.pause()
    }

  override suspend fun stop() =
    withContext(playbackDispatcher) {
      audio.pause()
      frameWrite = 0
      audio.flush()
    }

  override fun close() {
    super.close()
    MainScope().launch {
      stop()
      audio.release()
    }
    // Surface 是本类从 surfaceTexture 包出来的（TextureView 那侧
    // onSurfaceTextureDestroyed 恒 false、不代放），所有权在这里收口。
    surfaceRef?.release()
    surfaceRef = null
  }
}
