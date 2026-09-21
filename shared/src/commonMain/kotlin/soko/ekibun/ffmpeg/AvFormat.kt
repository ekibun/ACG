package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.jniLoadLibrary

open class AvFormat(
  val url: String,
  val io: AvIO.Handler,
) : Pointer(dispatcher = formatDispatcher) {
  companion object {
    private val formatDispatcher = ThreadDispatcher("avformat")
    const val AVSEEK_SIZE = 0x10000
    const val AV_TIME_BASE = 1000000

    // libavformat/avformat.h 的 AVSEEK_FLAG_*（seek 时传给 seekTo 的 flags）

    /** 落点取「不晚于 ts」的关键帧。注意 `avformat_seek_file` 内部会 `flags &= ~BACKWARD`，容器级 seek 请改用 min/max 窗口表达方向。 */
    const val AVSEEK_FLAG_BACKWARD = 1

    /** 按字节位置 seek（ts 解释为字节偏移）。 */
    const val AVSEEK_FLAG_BYTE = 2

    /** 允许落在非关键帧上（仅在 demuxer 支持时有效）。 */
    const val AVSEEK_FLAG_ANY = 4

    // AVSampleFormat 常量。
    // 直接照抄 FFmpeg 枚举值，避免各平台实现里散落 "0"/"1" 这类魔术数字。
    //
    // 视频输出格式不在这里：native 侧已把转码目标钉死成 AV_PIX_FMT_RGBA
    // （`cxx/ffmpeg/ffmpeg.cpp` 的 postFrameVideo），平台侧没有可选项。
    const val AV_SAMPLE_FMT_NONE = -1
    const val AV_SAMPLE_FMT_U8 = 0
    const val AV_SAMPLE_FMT_S16 = 1
    const val AV_SAMPLE_FMT_S32 = 2
    const val AV_SAMPLE_FMT_FLT = 3
    const val AV_SAMPLE_FMT_DBL = 4

    init {
      jniLoadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun getStreamsNative(pctx: Long): Array<AvStream>

    @JvmStatic
    private external fun seekToNative(
      pctx: Long,
      ts: Long,
      streamIndex: Int,
      minTs: Long,
      maxTs: Long,
      flags: Int,
    ): Int

    // < 0：出错
    // >= 0：流索引
    @JvmStatic
    private external fun getPacketNative(
      ctx: Long,
      packet: Long,
    ): Int

    @JvmStatic
    private external fun destroyNative(ctx: Long)
  }

  /**
   * 打开输入、建 `AVFormatContext`。
   *
   * ⚠️ **本包唯一一个留在类体里的 `external fun`，别挪进 companion。**
   *
   * 本包的约定是「`external fun` 放 companion + `@JvmStatic`」（理由见 [AvFrame.closeNative]）——
   * 但 `@JvmStatic` 有第二个后果：原生方法变成**静态**，于是第二个 JNI 实参从**实例**
   * 变成**类对象**（`jclass`）。本函数恰好要用那个实参当实例：
   * `cxx/ffmpeg/ffmpeg.cpp` 把 `thiz` 存进 `AVFormatContext::opaque`，再在 `io_open`
   * 回调里对它 `GetObjectClass` 后读 `io` 字段。静态化之后 `GetObjectClass` 拿到的是
   * `java.lang.Class`，`GetFieldID(..."io"...)` 抛 `NoSuchFieldError`，随后以 null
   * fieldID 继续走 → **JVM 直接崩**（`EXCEPTION_ACCESS_VIOLATION`）。
   * 2026-09-21 实测踩过，hs_err 里紧邻崩溃的那条事件就是
   * `NoSuchFieldError: java.lang.Class.io Lsoko/ekibun/ffmpeg/AvIO$Handler;`。
   *
   * 留在类体里没有代价：类体的 `external fun` 是**实例**原生方法，JNI 名同样是外层类名
   * `Java_soko_ekibun_ffmpeg_AvFormat_initNative`，与 companion + `@JvmStatic` 得到的名一样；
   * 差别只在那第二个实参。全包其余 16 个原生方法都不碰 `thiz`，所以可以放心静态化。
   */
  private external fun initNative(url: String): Long

  private var streams: List<AvStream>? = null

  override fun initPtr(): Long = initNative(url)

  suspend fun getStreams(): List<AvStream> =
    withPtr { ptr ->
      if (streams == null) {
        streams = getStreamsNative(ptr).toList()
      }
      streams!!
    }

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
    withPtr { ptr ->
      seekToNative(ptr, ts, stream?.index ?: -1, minTs, maxTs, flags)
    }

  suspend fun getPacket(streams: Collection<AvStream>): AvPacket? =
    withPtr { ptr ->
      val packet = AvPacket()
      while (true) {
        val ret = getPacketNative(ptr, packet.ptr)
        if (ret < 0) {
          // EOF / 出错：这个 packet 交不出去了，必须就地归还 —— native 侧
          // 只有 av_packet_free 一个释放点，没有 GC 兜底（见 AvPacket）。
          packet.close()
          return@withPtr null
        }
        if (streams.isEmpty() || streams.firstOrNull { it.index == ret } != null) {
          packet.streamIndex = ret
          break
        }
      }
      packet
    }

  override suspend fun releaseImpl(ptr: Long) = destroyNative(ptr)
}
