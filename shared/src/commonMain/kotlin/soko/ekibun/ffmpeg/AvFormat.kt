package soko.ekibun.ffmpeg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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

    /**
     * 预读通道装多少个 packet。
     *
     * ⚠️ 这是**包数**口径，不是字节 —— mpv 的 `--demuxer-max-bytes` 与 ExoPlayer 的
     * `DEFAULT_MAX_BUFFER_SIZE` 都是字节口径。它同时是「暂停期间还能预读多少」的上界：
     * 通道满了，读作业就停在 `send` 上等消费侧来取（这就是回压）。
     */
    const val PREFETCH_PACKETS = 100

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
      resetChannel()
      seekToNative(ptr, ts, stream?.index ?: -1, minTs, maxTs, flags)
    }

  private var packetChannel: Channel<AvPacket?>? = null

  /**
   * 丢掉当前这条预读通道；下次 [getPacket] 会重建一条（从新位置重新读）。
   *
   * ⚠️ **必须用 `cancel()`、不能用 `close()`** —— 2026-10-01 实测两者的收尾完全不同：
   *
   * | | 正卡在 `send` 上的读作业 | 队列里已预读的包 |
   * |---|---|---|
   * | `close()` | **不唤醒**（随后那个包还会投递成功，落进一条已经没人再收的通道） | 还收得到 |
   * | `cancel()` | 以 `CancellationException` 收场，包由 `onUndeliveredElement` 归还 | 同样由回调归还 |
   *
   * 用 `close()` 的结果是**每次 seek 漏一个 `AVPacket`**（native，无 GC 兜底，见 [AvPacket]）。
   * 也正因为走 `cancel()`，**不能再**照旧「`close()` + 遍历队列关包」：`cancel()` 之后队列已被
   * 丢弃，而遍历会抛 `CancellationException`。
   *
   * ⚠️ 调用点必须先让消费侧停下（`FFPlayer` 的 `closeAsync` / `seekImpl` 都在 `pause()` 之后才
   * 调到这里），否则正挂在 `receive` 上的消费侧会以 `CancellationException` 收场。
   */
  suspend fun resetChannel() {
    val oldChannel = packetChannel
    packetChannel = null
    oldChannel?.cancel()
  }

  /**
   * 取一个 packet 交给上层。
   *
   * 读包在后台读作业里做：**首次调用**时建一条容量 [PREFETCH_PACKETS] 的通道，之后一直从它取
   * （通道满则读作业停在 `send` 上等消费侧来取 —— 这就是回压）。
   *
   * ⚠️ **[streams] 只在「建通道那一次」生效**：它决定这条通道读哪些流，之后同一通道存活期间的
   * 调用传什么都不看。换过滤条件 = 换通道，由 [seekTo] 里的 [resetChannel] 保证（FFPlayer 每轮
   * 传 `pts.streams.values`，而换位置一定先 seek ⇒ 实际踩不到）。
   *
   * 返回 `null` 表示 **EOF**：读作业读到流尾会把通道关掉，此后每次调用都**立刻返回 null**
   * （粘住的），不挂起、也不抛。
   */
  suspend fun getPacket(streams: Collection<AvStream>): AvPacket? =
    withPtr { ptr ->
      val channel =
        packetChannel ?: run {
          val channel =
            Channel<AvPacket?>(PREFETCH_PACKETS, onUndeliveredElement = { packet -> packet?.close() })
          packetChannel = channel
          @Suppress("DeferredResultUnused")
          CoroutineScope(formatDispatcher).async {
            // 手里那个包还没交出去时，谁都得负责归还它 —— 下面 finally 兜底。
            var inFlight: AvPacket? = null
            try {
              while (true) {
                val packet = AvPacket()
                inFlight = packet
                while (true) {
                  val ret = getPacketNative(ptr, packet.ptr)
                  if (ret < 0) {
                    // EOF / 出错：这个 packet 交不出去了，必须就地归还 —— native 侧
                    // 只有 av_packet_free 一个释放点，没有 GC 兜底（见 AvPacket）。
                    packet.close()
                    inFlight = null
                    return@async
                  }
                  // `streams.isEmpty()` 也收下：那是**下载器模式**（不看画面、只要数据），
                  // 调用方没有任何流可筛，此时要把每个包都读出来推进读取。
                  // 别把它当成冗余判断删掉，否则下载会一包都读不到。
                  if (streams.isEmpty() || streams.firstOrNull { it.index == ret } != null) {
                    packet.streamIndex = ret
                    channel.send(packet)
                    // 交出去了：所有权归通道（没被收走时由 onUndeliveredElement 归还）。
                    inFlight = null
                    break
                  }
                }
              }
            } finally {
              // 手里还攥着包（读出错 / 被取消）就地归还；再关掉通道 —— 消费侧那个 `for`
              // 靠它才会结束，不关就是**永久挂起**（挂着的那一方不会自己醒）。
              inFlight?.close()
              channel.close()
            }
          }
          channel
        }
      // 用 `for` 而不是 `receive()`：通道关闭后 `for` 会把缓冲收完再**正常结束**（不抛），
      // 于是「EOF 之后每次调用都立刻返回 null」是粘住的；`receive()` 则会抛
      // `ClosedReceiveChannelException`（那异常往上走会被 `playingJob.join()` 吞掉 = 静默失效）。
      for (v in channel) {
        return@withPtr v
      }
      null
    }

  override suspend fun releaseImpl(ptr: Long) {
    resetChannel()
    destroyNative(ptr)
  }
}
