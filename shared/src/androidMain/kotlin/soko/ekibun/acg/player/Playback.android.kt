package soko.ekibun.acg.player

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
import soko.ekibun.ffmpeg.AvFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class AndroidPlayback(
  var surfaceTexture: SurfaceTexture,
  onFrame: (Long?) -> Unit,
) : Playback(
    onFrame,
  ) {
  private val playbackDispatcher by lazy {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  }

  companion object {
    const val DEFAULT_RATE = 48000
    const val DEFAULT_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO

    /**
     * AudioTrack 的编码格式。native 会按它转码，所以改这个值就能整体切换音频位宽，
     * 无需动 Kotlin 侧任何转换代码。
     *
     * 注意 ENCODING_PCM_8BIT 只是"不保证所有设备支持"，并非不可用：AudioTrack 会把
     * 它接受为合法的 linear PCM（AudioFormat.isEncodingLinearPcm 返回 true），
     * 且 byte[] 写入路径不经过 short[] 那条 `> ENCODING_LEGACY_SHORT_ARRAY_THRESHOLD`
     * 的限制分支。
     */
    const val DEFAULT_FORMAT = AudioFormat.ENCODING_PCM_8BIT
  }

  override val sampleRate: Int by lazy { audio.sampleRate }
  override val channels: Int by lazy { audio.channelCount }

  /**
   * 交给 native 的输出采样格式（AVSampleFormat）。
   *
   * 必须由 AudioTrack 的编码格式反推，不能写常量：native 侧 postFrameAudio 用
   * swr_alloc_set_opts2 把解码结果转成这个格式后才交给 AudioTrack，两者不一致时
   * AudioTrack 会把字节按错误的位宽解释（8bit 轨收到 float32 就是 4 倍速的噪声）。
   *
   * 用 lazy 而不是直接初始化，是为了在属性声明顺序上避开 [audio]（它声明在后面）。
   * 取值只发生在首次 postFrame 之前，那时 AudioTrack 早已构造完成。
   */
  override val audioFormat: Int by lazy {
    when (audio.audioFormat) {
      AudioFormat.ENCODING_PCM_8BIT -> AvFormat.AV_SAMPLE_FMT_U8
      AudioFormat.ENCODING_PCM_FLOAT -> AvFormat.AV_SAMPLE_FMT_FLT
      else -> AvFormat.AV_SAMPLE_FMT_S16
    }
  }
  override val videoFormat: Int = AvFormat.AV_PIX_FMT_RGBA

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

  override suspend fun flushAudioBuffer(buf: ByteArray): Int =
    withContext(playbackDispatcher) {
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
        while (i + frameBytes <= buf.size) {
          for (b in 0 until bytesPerSample) {
            val diff = (buf[i + b].toInt() and 0xFF) - (buf[i + bytesPerSample + b].toInt() and 0xFF)
            // 8bit 是 unsigned，以 128 为零点，消声后要加回偏置
            val v = if (bytesPerSample == 1) diff + 128 else diff
            buf[i + b] = v.toByte()
            buf[i + bytesPerSample + b] = v.toByte()
          }
          i += frameBytes
        }
      }
      if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
      if (buf.isNotEmpty()) audio.write(buf, 0, buf.size)
      // AudioTrack 的 framePosition 以采样帧为单位，与 channels 无关，不要再除
      frameWrite += buf.size / (audio.channelCount * bytesPerSampleOf(audio.audioFormat))
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

  val surface by lazy { Surface(surfaceTexture) }

  val paint by lazy { Paint() }

  override fun flushVideoBuffer(
    buf: ByteArray,
    width: Int,
    height: Int,
  ) {
    updateAspectRatio(width, height)
    surfaceTexture.setDefaultBufferSize(width, height)
    if (bitmap == null || bitmap?.width != width || bitmap?.height != height) {
      val oldBitmap = bitmap
      bitmap = createBitmap(width, height)
      oldBitmap?.recycle()
    }
    bitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
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
  }
}
