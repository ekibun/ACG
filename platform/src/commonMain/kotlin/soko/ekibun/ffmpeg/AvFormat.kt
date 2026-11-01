package soko.ekibun.ffmpeg

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.loadLibrary

open class AvFormat(
  val url: String,
  val io: AvIO.Handler,
) : Pointer(dispatcher = formatDispatcher) {
  companion object {
    private val formatDispatcher = ThreadDispatcher("avformat")
    const val AVSEEK_SIZE = 0x10000
    const val AV_TIME_BASE = 1000000

    // libavformat/avformat.h 的 AVSEEK_FLAG_*

    /** 落点取「不晚于 ts」的关键帧。容器级 seek 的方向请用 min/max 窗口表达。 */
    const val AVSEEK_FLAG_BACKWARD = 1

    /** 按字节位置 seek（ts 解释为字节偏移）。 */
    const val AVSEEK_FLAG_BYTE = 2

    /** 允许落在非关键帧上（仅在 demuxer 支持时有效）。 */
    const val AVSEEK_FLAG_ANY = 4

    // libavcodec/packet.h 的 AV_PKT_FLAG_*

    /** 关键帧。跳过广告之后接上的第一包必须是它，否则解码器没有参考帧。 */
    const val AV_PKT_FLAG_KEY = 1

    // AVSampleFormat 枚举值。
    const val AV_SAMPLE_FMT_NONE = -1
    const val AV_SAMPLE_FMT_U8 = 0
    const val AV_SAMPLE_FMT_S16 = 1
    const val AV_SAMPLE_FMT_S32 = 2
    const val AV_SAMPLE_FMT_FLT = 3
    const val AV_SAMPLE_FMT_DBL = 4

    /** libavutil/error.h 的 AVERROR_EOF。 */
    const val AVERROR_EOF = -541478725

    /** AVERROR_EXIT：操作被要求立即中止。 */
    const val AVERROR_EXIT = -1414092869

    /** 预读通道容量（按包数）；通道满了读作业就停在 `send` 上。 */
    const val PREFETCH_PACKETS = 100

    init {
      loadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun getStreamsNative(pctx: Long): Array<AvStream>

    @JvmStatic
    private external fun getDurationNative(pctx: Long): Long

    @JvmStatic
    private external fun seekToNative(
      pctx: Long,
      ts: Long,
      streamIndex: Int,
      minTs: Long,
      maxTs: Long,
      flags: Int,
    ): Int

    // < 0：出错；>= 0：流索引。`out[0]` 回填包时间戳（微秒，容器没给为 -1），
    // `out[1]` 回填 `AV_PKT_FLAG_*`。
    @JvmStatic
    private external fun getPacketNative(
      ctx: Long,
      packet: Long,
      ptsOut: LongArray,
    ): Int

    @JvmStatic
    private external fun destroyNative(ctx: Long)

    /** 关掉 HLS 插播广告的自动跳过（默认开）。 */
    @JvmStatic
    private external fun setSkipAdNative(
      ctx: Long,
      on: Boolean,
    ): Int
  }

  /** 建上下文；本类唯一留在类体里的 `external fun`（要用 `thiz`），别挪进 companion。 */
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

  /** 容器总时长（微秒）；容器没给返回 0。须在 [getStreams] 之后调。 */
  suspend fun getDurationUs(): Long = withPtr { ptr -> getDurationNative(ptr) }

  /** 当前的广告跳过开关；步进 / 退帧轮临时关掉后按它恢复。 */
  var isSkipAd = true
    private set

  /**
   * 关掉 HLS 插播广告的自动跳过（默认开）。只对 HLS 有效，非 HLS 返回非 0。
   * 误判时用：先关掉再 `seekTo` 回广告位置重播，整条流都不再跳过。
   */
  suspend fun setSkipAd(on: Boolean): Int =
    withPtr { ptr ->
      isSkipAd = on
      setSkipAdNative(ptr, on)
    }

  /** 容器级 seek；`stream` 为 null 时单位是 `AV_TIME_BASE` 微秒，否则用该流的 `time_base`。 */
  open suspend fun seekTo(
    ts: Long,
    stream: AvStream? = null,
    minTs: Long = Long.MIN_VALUE,
    maxTs: Long = Long.MAX_VALUE,
    flags: Int = 0,
  ): Unit =
    withPtr { ptr ->
      resetChannel()
      seekToNative(ptr, ts, stream?.index ?: -1, minTs, maxTs, flags)
    }

  private var packetChannel: Channel<AvPacket?>? = null

  /** 当前预读读作业（[getPacket] 首次调用时建）。 */
  private var readerJob: Job? = null

  /**
   * 作废当前读轮：`abort` → `cancel` 通道 → `join` [readerJob]，顺序固定。
   * **必须在归属线程之外调**（唯一例外是 [seekTo]）；细节见 `playback-invariants.md` 第六节。
   */
  suspend fun resetChannel() {
    io.abort()
    val oldChannel = packetChannel
    packetChannel = null
    oldChannel?.cancel()
    readerJob?.join()
  }

  /**
   * 取一个 packet；读包在后台作业里做，首次调用建通道。
   * 返回 `null` 表示 EOF（粘住）；读出错同样折叠成 null。详见 `playback-invariants.md` 第一节。
   */
  suspend fun getPacket(streams: Collection<AvStream>): AvPacket? {
    val channel =
      packetChannel ?: run {
        val channel =
          Channel<AvPacket?>(PREFETCH_PACKETS, onUndeliveredElement = { packet -> packet?.close() })
        packetChannel = channel
        readerJob =
          submit {
            // 未交出去的包由下面的 finally 归还。声明在 withPtr 外面：守卫抛异常时
            // lambda 根本没跑，只有放到这层才能让 finally 覆盖到那条路径。
            var inFlight: AvPacket? = null
            try {
              withPtr { ptr ->
                // 复用同一个数组，避免每包一次分配。
                val ptsOut = LongArray(2)
                while (true) {
                  val packet = AvPacket()
                  inFlight = packet
                  while (true) {
                    val ret = getPacketNative(ptr, packet.ptr, ptsOut)
                    if (ret < 0) {
                      // 交不出去就地归还；作废收场（AVERROR_EXIT）不出声。
                      packet.close()
                      inFlight = null
                      if (ret != AVERROR_EOF && ret != AVERROR_EXIT) {
                        println("[AvFormat] av_read_frame error ret=$ret, folded to EOF")
                      }
                      return@withPtr
                    }
                    // `streams.isEmpty()` 是下载器模式，别当冗余删掉。
                    if (streams.isEmpty() || streams.firstOrNull { it.index == ret } != null) {
                      packet.streamIndex = ret
                      packet.ptsUs = ptsOut[0]
                      packet.flags = ptsOut[1].toInt()
                      channel.send(packet)
                      // 所有权归通道（没被收走时由 onUndeliveredElement 归还）。
                      inFlight = null
                      break
                    }
                  }
                }
              }
            } finally {
              // 手里还攥着包就地归还；再关通道（含上面守卫抛出的那条路径）。
              inFlight?.close()
              channel.close()
            }
          }
        channel
      }
    // 用 `for` 而不是 `receive()`：通道关闭后 for 收完缓冲就正常结束。
    for (v in channel) {
      return v
    }
    return null
  }

  /** 只销毁 native 上下文，不负责作废读轮；调用方须先让读作业死透。 */
  override suspend fun releaseImpl(ptr: Long) {
    destroyNative(ptr)
  }
}
