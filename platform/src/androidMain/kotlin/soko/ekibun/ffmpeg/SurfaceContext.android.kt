package soko.ekibun.ffmpeg

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.view.Surface
import kotlinx.coroutines.delay

class AndroidSurfaceContext(
  var surfaceTexture: SurfaceTexture,
) : SurfaceContext() {
  companion object {
    const val DEFAULT_RATE = 48000
    const val DEFAULT_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO

    /**
     * AudioTrack 的编码格式。native 按它转码，改这个值就整体切音频位宽。
     *
     * `ENCODING_PCM_8BIT` 可用：AudioTrack 接受为合法 linear PCM，且 byte[] 写入路径不走
     * short[] 那条长度限制分支（只是不保证所有设备支持）。
     */
    const val DEFAULT_FORMAT = AudioFormat.ENCODING_PCM_8BIT

    @JvmStatic
    private external fun copyBufferToSurface(
      ctx: Long,
      surface: Surface,
    ): Boolean
  }

  override val sampleRate: Int by lazy { audio.sampleRate }
  override val channels: Int by lazy { audio.channelCount }

  /**
   * 交给 native 的输出采样格式（AVSampleFormat），必须由 [audio] 的编码反推、不能写常量：
   * 不一致时 AudioTrack 会按错误位宽解释字节（8bit 轨收到 float32 就是 4 倍速的噪声）。
   * lazy 是为了避开属性声明顺序；取值只发生在首次 postFrame 之前。
   */
  override val audioFormat: Int by lazy {
    when (audio.audioFormat) {
      AudioFormat.ENCODING_PCM_8BIT -> AvFormat.AV_SAMPLE_FMT_U8
      AudioFormat.ENCODING_PCM_FLOAT -> AvFormat.AV_SAMPLE_FMT_FLT
      else -> AvFormat.AV_SAMPLE_FMT_S16
    }
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
        copyBufferToSurface(ptr, surface)
      }
    }
    return -1
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

  suspend fun flushAudioBuffer(buf: ByteArray): Int {
    val size = buf.size
    if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
    // 非阻塞写。音频与视频的送显作业跑在同一条播放线程上，阻塞写会在音频队列满时把整条线程
    // 卡住几百毫秒 —— 画面跟着停一下再连补几帧，时钟读数的锯齿也是它造成的。
    // 写不进去就返回短计数，让出线程（delay 会把线程让给视频作业）再接着写。
    var offset = 0
    while (offset < size) {
      val written = audio.write(buf, offset, size - offset, AudioTrack.WRITE_NON_BLOCKING)
      if (written < 0) break
      if (written == 0) {
        // 暂停 / 停止已落地：缓冲区不再被消费，再等就是死等
        if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) break
        delay(1)
        continue
      }
      offset += written
    }
    // 只记真写进去的量：被打断时剩下的没进声卡
    // AudioTrack 的 framePosition 以采样帧为单位，与 channels 无关，不要再除
    frameWrite += offset / (audio.channelCount * bytesPerSampleOf(audio.audioFormat))
    if (offset < size) return -1
    val timestamp = AudioTimestamp()
    if (!audio.getTimestamp(timestamp)) return -1
    // framePosition 每 10ms 左右才走一格，直接拿它算延迟就是台阶：时钟、以及被时钟
    // 门控的画面都会跟着跳。把「采样时刻之后又播了多少帧」按名义采样率补回去，
    // 延迟才是连续的（与 animix 的 PlaybackImpl 同款做法）。
    val elapsedNs = System.nanoTime() - timestamp.nanoTime
    val played = timestamp.framePosition + elapsedNs * audio.sampleRate / 1_000_000_000L
    // 补出来的值理论上不会超过写进去的量；超过说明这一拍插值偏了，钳住别让时钟倒着跳。
    return (frameWrite - played).coerceAtLeast(0L).toInt()
  }

  private fun bytesPerSampleOf(encoding: Int): Int =
    when (encoding) {
      AudioFormat.ENCODING_PCM_8BIT -> 1
      AudioFormat.ENCODING_PCM_FLOAT -> 4
      else -> 2
    }

  // 显式持有而不是 `by lazy`：close() 只能还已经建过的 Surface，为释放去读 lazy 反而会建一个
  private var surfaceRef: Surface? = null

  val surface: Surface
    get() = surfaceRef ?: Surface(surfaceTexture).also { surfaceRef = it }

  override suspend fun resume() = audio.play()

  override suspend fun pause() {
    if (audio.state == AudioTrack.STATE_UNINITIALIZED) return
    audio.pause()
  }

  override suspend fun stop() {
    if (audio.state == AudioTrack.STATE_UNINITIALIZED) return
    audio.pause()
    frameWrite = 0
    audio.flush()
  }

  override fun close() {
    super.close()
    audio.release()
    // Surface 是本类从 surfaceTexture 包出来的（TextureView 那侧不代放），所有权在这里收口
    surfaceRef?.release()
    surfaceRef = null
  }
}
