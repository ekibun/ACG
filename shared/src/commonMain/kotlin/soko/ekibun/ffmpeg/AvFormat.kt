package soko.ekibun.ffmpeg

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import soko.ekibun.jniLoadLibrary
import java.util.concurrent.Executors

open class AvFormat(
  val url: String,
  val io: AvIO.Handler,
) {
  companion object {
    const val AVSEEK_SIZE = 0x10000
    const val AV_TIME_BASE = 1000000

    // libavformat/avformat.h 的 AVSEEK_FLAG_*（seek 时传给 seekTo 的 flags）

    /** 落点取「不晚于 ts」的关键帧。注意 `avformat_seek_file` 内部会 `flags &= ~BACKWARD`，容器级 seek 请改用 min/max 窗口表达方向。 */
    const val AVSEEK_FLAG_BACKWARD = 1

    /** 按字节位置 seek（ts 解释为字节偏移）。 */
    const val AVSEEK_FLAG_BYTE = 2

    /** 允许落在非关键帧上（仅在 demuxer 支持时有效）。 */
    const val AVSEEK_FLAG_ANY = 4

    /*
     * AVSampleFormat / AVPixelFormat 常量。
     * 直接照抄 FFmpeg 枚举值，避免各平台实现里散落 "0"/"25" 这类魔术数字
     * （视频侧原来把 videoFormat 写成 25，而 AVPixelFormat 只有 0..13，
     * sws_getContext 会因此走到 unknown 分支）。
     */
    const val AV_SAMPLE_FMT_NONE = -1
    const val AV_SAMPLE_FMT_U8 = 0
    const val AV_SAMPLE_FMT_S16 = 1
    const val AV_SAMPLE_FMT_S32 = 2
    const val AV_SAMPLE_FMT_FLT = 3
    const val AV_SAMPLE_FMT_DBL = 4

    const val AV_PIX_FMT_NONE = -1
    const val AV_PIX_FMT_YUV420P = 0
    const val AV_PIX_FMT_RGB24 = 2
    const val AV_PIX_FMT_BGR24 = 3
    const val AV_PIX_FMT_RGBA = 26
    const val AV_PIX_FMT_BGRA = 28

    init {
      jniLoadLibrary("ffmpeg")
    }
  }

  private var pctx: Long? = null
  private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val dispatcherThread = runBlocking(dispatcher) { Thread.currentThread() }

  private fun <T> runOnDispatcher(block: () -> T): T {
    if (Thread.currentThread() == dispatcherThread) return block()
    return runBlocking(dispatcher) { block() }
  }

  private var streams: List<AvStream>? = null

  private external fun initNative(url: String): Long

  private fun <T> runWithContext(
    create: Boolean = false,
    cb: (ctx: Long) -> T,
  ): T =
    runOnDispatcher {
      if (create && pctx == null) pctx = initNative(url)
      if ((pctx ?: 0L) == 0L) throw Exception("AvFormat closed")
      cb(pctx!!)
    }

  private external fun getStreamsNative(pctx: Long): Array<AvStream>

  fun getStreams(): List<AvStream> =
    runWithContext(true) { ctx ->
      if (streams == null) {
        streams = getStreamsNative(ctx).toList()
      }
      streams!!
    }

  private external fun seekToNative(
    pctx: Long,
    ts: Long,
    streamIndex: Int,
    minTs: Long,
    maxTs: Long,
    flags: Int,
  ): Int

  /**
   * 容器级 seek。
   *
   * `ts` / `minTs` / `maxTs` 的单位取决于 `stream`：
   * - `stream == null`（`stream_index = -1`）时是 **`AV_TIME_BASE` 微秒**；
   * - 传了具体 stream 时是**该 stream 的 `time_base` 单位**。
   *
   * ⚠️ `minTs`/`maxTs` 只在 demuxer 实现了 `read_seek2` 时才生效。
   * mp4/mov 只实现了旧的 `read_seek`，`avformat_seek_file` 会落进回退分支
   * 把窗口整个丢掉，只按 `dir` 推出 `AVSEEK_FLAG_BACKWARD` 调 `av_seek_frame`，
   * 结果必然是「不晚于 `ts` 的最近关键帧」。想精确到目标时间必须靠调用方
   * 自己丢帧收敛（见 [FFPlayer.seekTo]）。
   */
  open suspend fun seekTo(
    ts: Long,
    stream: AvStream? = null,
    minTs: Long = Long.MIN_VALUE,
    maxTs: Long = Long.MAX_VALUE,
    flags: Int = 0,
  ): Unit =
    runWithContext { ctx ->
      seekToNative(ctx, ts, stream?.index ?: -1, minTs, maxTs, flags)
    }

  // < 0: error
  // >=0: stream index
  private external fun getPacketNative(
    ctx: Long,
    packet: Long,
  ): Int

  fun getPacket(streams: Collection<AvStream>): AvPacket? =
    runWithContext { ctx ->
      val packet = AvPacket()
      while (true) {
        val ret = getPacketNative(ctx, packet.ptr)
        if (ret < 0) {
          return@runWithContext null
        }
        if (streams.isEmpty() || streams.firstOrNull { it.index == ret } != null) {
          packet.streamIndex = ret
          break
        }
      }
      packet
    }

  private external fun destroyNative(ctx: Long)

  open suspend fun close() =
    runWithContext {
      pctx?.let { ctx ->
        destroyNative(ctx)
      }
      pctx = null
    }

  protected fun finalize() =
    runBlocking {
      close()
    }
}
