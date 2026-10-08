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

    // libavformat/avformat.h 的 AVSEEK_FLAG_*（seek 时传给 seekTo 的 flags）

    /** 落点取「不晚于 ts」的关键帧。注意 `avformat_seek_file` 内部会 `flags &= ~BACKWARD`，容器级 seek 请改用 min/max 窗口表达方向。 */
    const val AVSEEK_FLAG_BACKWARD = 1

    /** 按字节位置 seek（ts 解释为字节偏移）。 */
    const val AVSEEK_FLAG_BYTE = 2

    /** 允许落在非关键帧上（仅在 demuxer 支持时有效）。 */
    const val AVSEEK_FLAG_ANY = 4

    // AVSampleFormat 常量，直接照抄 FFmpeg 枚举值，避免各平台实现里散落 "0"/"1" 这类魔术数字。
    // 视频输出格式不在这里：native 侧已把转码目标钉死成 AV_PIX_FMT_RGBA（`cxx/ffmpeg/ffmpeg.cpp`
    // 的 postFrameVideo），平台侧没有可选项。
    const val AV_SAMPLE_FMT_NONE = -1
    const val AV_SAMPLE_FMT_U8 = 0
    const val AV_SAMPLE_FMT_S16 = 1
    const val AV_SAMPLE_FMT_S32 = 2
    const val AV_SAMPLE_FMT_FLT = 3
    const val AV_SAMPLE_FMT_DBL = 4

    /**
     * libavutil/error.h 的 AVERROR_EOF（FFERRTAG('E','O','F',' ')），照抄枚举值：
     * getPacket 的读作业要区分「读到头」与「读出错」，错误要出声（见 getPacket）。
     */
    const val AVERROR_EOF = -541478725

    /**
     * libavutil/error.h 的 AVERROR_EXIT（`FFERRTAG('E','X','I','T')`）：**「操作被要求立即中止」**，
     * 用在 [resetChannel] 作废读轮那条路上 —— 等待被打断**不是故障**，不该出声（见 [getPacket]）。
     * 值照 `FFERRTAG` 的定义算出，与上面那个 AVERROR_EOF 同一个式子。
     */
    const val AVERROR_EXIT = -1414092869

    /**
     * 预读通道装多少个 packet。**包数**口径，不是字节（mpv / ExoPlayer 的同名上限都是字节）。
     * 它同时是「暂停期间还能预读多少」的上界：通道满了，读作业就停在 `send` 上等消费侧来取。
     */
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
   * **本类唯一一个留在类体里的 `external fun`，别挪进 companion。** 本函数要 JNI 第二个实参
   * （`thiz`）当**实例**用（存进 `AVFormatContext::opaque`），而 `@JvmStatic` 会把它变成 `jclass`
   * ⇒ 静态化后 JVM 直接崩。规则与逐条判据见 `cxx/AGENTS.md` 的 JNI 一节。
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
   * 容器总时长（微秒）；容器没给（直播流等）返回 0。
   *
   * **在 [getStreams]（`avformat_find_stream_info`）之后调**：HLS 在 `read_header` 里就把
   * 分片 EXTINF 求和写进 `AVFormatContext.duration`，而 WAV / MP4 这类把时长记在流上的
   * 容器要等 find_stream_info 估算完格式层才有。总时长一律优先走这里；[AvStream.duration]
   * 只是这些容器的兜底。
   */
  suspend fun getDurationUs(): Long = withPtr { ptr -> getDurationNative(ptr) }

  /**
   * 容器级 seek。`ts` / `minTs` / `maxTs` 的单位取决于 `stream`：`stream == null`
   * （`stream_index = -1`）时是 **`AV_TIME_BASE` 微秒**；传了具体 stream 时是**该 stream 的
   * `time_base` 单位**。
   *
   * `minTs`/`maxTs` 只在 demuxer 实现了 `read_seek2` 时才生效，mp4/mov 会落进回退分支把窗口整个丢掉
   * ⇒ 结果必然是「不晚于 `ts` 的最近关键帧」。想精确到目标时间得靠调用方自己丢帧收敛
   * （见 [FFPlayer.seekTo]）。
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
   * 当前预读读作业（[getPacket] 首次调用时建；通道被换后由下一次 getPacket 换新）。
   *
   * 它**不是**轮次的子作业（挂在自己这个 scope 上，轮次结束不等它）—— [resetChannel] 要的恰恰是
   * 「等它死透」，所以必须攥着引用。结束后引用仍指向已完成的 Job（join 立即返回），无需置空。
   */
  private var readerJob: Job? = null

  /**
   * 作废当前读轮：唤醒卡在读里的读作业、掀掉预读通道、**等它收尾**。三步顺序固定，换序即死等 ——
   * `abort` → `cancel` 通道 → `join` [readerJob]。**必须 `cancel()`、不能 `close()`**（队列已丢弃，
   * 两个后果见 `silent-failures.md`）。
   *
   * **必须在归属线程（[dispatcher]）之外调**，否则 `io.abort()` 排在它要叫醒的读作业后面、
   * 永远跑不到 ⇒ join 永久挂起（实测 `closeAsync` 冻住 5 s 超时）。唯一合法例外是 [seekTo]：
   * 它在 [withPtr] 块内调，而 `withPtr` 认得自己已在归属线程、派发过去就地执行、不排队。
   * 推导与实测见 `http-streaming.md` 第二节。
   *
   * 通道是**粘住**的（之后 [getPacket] 立刻拿到 null = EOF），所以置空。不需要「清旗标」：
   * 作废是**一次性事件**、绑在这条 IO 自己那次读的私有信号上，新轮 = 新 IO = 新信号。**幂等**。
   *
   * [packetChannel] / [readerJob] 跨线程读写但**刻意不加 `@Volatile`**（没有数据竞争，可见性靠
   * `FFPlayer.pause` 那个 join 与 `withPtr` 的恢复边）。那条不变量**不局部**，拆它得换
   * `kotlin.concurrent.atomics.AtomicReference` —— 访问点表与理由见 `silent-failures.md`。
   */
  suspend fun resetChannel() {
    io.abort()
    val oldChannel = packetChannel
    packetChannel = null
    oldChannel?.cancel()
    readerJob?.join()
  }

  /**
   * 取一个 packet 交给上层。读包在后台读作业里做：**首次调用**时建一条容量 [PREFETCH_PACKETS] 的通道，
   * 之后一直从它取（通道满则读作业停在 `send` 上等消费侧来取 —— 这就是回压）。
   *
   * **[streams] 只在「建通道那一次」生效**，之后同一通道存活期间的调用传什么都不看。换过滤条件 = 换通道，
   * 由 [seekTo] 里的 [resetChannel] 保证（FFPlayer 每轮传 `pts.streams.values`，而换位置一定先 seek）。
   *
   * 返回 `null` 表示 **EOF**（粘住的，不挂起也不抛）。读**出错**同样折叠成 null —— aviobuf 把 IO 错误
   * 毒化成 EOF、API 层分不开，但错误会出声（native 两条 av_log + 这里那条诊断行），不是静默的「播完了」。
   */
  suspend fun getPacket(streams: Collection<AvStream>): AvPacket? {
    val channel =
      packetChannel ?: run {
        val channel =
          Channel<AvPacket?>(PREFETCH_PACKETS, onUndeliveredElement = { packet -> packet?.close() })
        packetChannel = channel
        readerJob =
          submit {
            withPtr { ptr ->
              // 手里那个包还没交出去时，谁都得负责归还它 —— 下面 finally 兜底。
              var inFlight: AvPacket? = null
              try {
                while (true) {
                  val packet = AvPacket()
                  inFlight = packet
                  while (true) {
                    val ret = getPacketNative(ptr, packet.ptr)
                    if (ret < 0) {
                      // EOF / 出错：这个 packet 交不出去，必须就地归还（native 只有 `av_packet_free`
                      // 一个释放点、没有 GC 兜底，见 AvPacket），随即把在飞的那个也清掉 —— 两者在这里
                      // 同形收场，API 层分不开，只能靠出声（[AVERROR_EXIT] 例外：作废收场，别出声）。
                      packet.close()
                      inFlight = null
                      if (ret != AVERROR_EOF && ret != AVERROR_EXIT) {
                        println("[AvFormat] av_read_frame error ret=$ret, folded to EOF")
                      }
                      return@withPtr
                    }
                    // `streams.isEmpty()` 也收下：那是**下载器模式**（不看画面、只要数据），
                    // 调用方没有任何流可筛。别把这个判断当冗余删掉，否则下载一包都读不到。
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
          }
        channel
      }
    // 用 `for` 而不是 `receive()`：通道关闭后 `for` 会把缓冲收完再**正常结束**（不抛），
    // 于是「EOF 之后每次调用都立刻返回 null」是粘住的；`receive()` 则会抛
    // `ClosedReceiveChannelException`（那异常往上走会被 `playingJob.join()` 吞掉 = 静默失效）。
    for (v in channel) {
      return v
    }
    return null
  }

  /**
   * 只销毁 native 上下文，**不负责作废读轮** —— [resetChannel] 是调用方的责任，且**必须在
   * [dispatcher] 之外的线程调**（[FFPlayer.closeAsync] 就在它的 `playerDispatcher` 上先调）。
   *
   * **调用方必须先确认读作业已死**（[resetChannel] 的第 3 步 `readerJob.join()` 就是干这个的）：
   * [destroyNative] 走 `avformat_close_input`，而读作业正拿着 `AVFormatContext` 在
   * `av_read_frame` 里 —— 两者相遇就是 use-after-free。
   *
   * **别把 [resetChannel] 加回这里兜底**：`releaseImpl` 经 `closeDeferred()` 的 `submit { }`
   * 排在 [dispatcher] 队列后面，而那条线程彼时正被读作业的 `runBlocking` 占死 ⇒「唯一能叫醒读的
   * 那行代码排在它要叫醒的东西后面」。实测与修法见 `http-streaming.md` 第二节。
   */
  override suspend fun releaseImpl(ptr: Long) {
    destroyNative(ptr)
  }
}
