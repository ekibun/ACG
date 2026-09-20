package soko.ekibun.ffmpeg

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary
import java.util.concurrent.Executors

open class AvFormat(
  val url: String,
  val io: AvIO.Handler,
) : AutoCloseable {
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

    // AVSampleFormat / AVPixelFormat 常量。
    // 直接照抄 FFmpeg 枚举值，避免各平台实现里散落 "0"/"26" 这类魔术数字。
    // 别凭「枚举只到 13」这类印象改这里：pixfmt.h 里 ARGB=25、RGBA=26、BGRA=28
    // 都是合法值，写错不会报错，只会让拿到帧的平台按别的通道序解释 ——
    // 症状是「能播但颜色不对」（R/B 互换），不是取不到帧。
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

  /**
   * 解封装上下文句柄的 [Pointer] 视图。
   *
   * 句柄要等首次 [runWithContext] 才建，而 [Pointer] 的指针是**构造参数**（创建即
   * 确定、之后不可更改）—— 后填不进去。于是句柄单独归一个内部子类持有，本类只负责
   * 转发；「还没建」与「已经销毁」两种状态统一表达为 `ctx == null`。
   */
  private var ctx: Context? = null

  private inner class Context(
    ptr: Long,
  ) : Pointer(ptr, formatDispatcher) {
    override val releaseHint: String
      get() = "它由 AvFormat.closeAsync() 统一销毁：销毁必须回到 formatDispatcher 线程"

    /**
     * 销毁，只跑一次。只能由外层在 [formatDispatcher] 线程上调用 ——
     * 句柄用基类的 [Pointer.ptrValue] 读，此时已在归属 dispatcher 上，就地返回。
     */
    suspend fun destroyNow() {
      if (markClosed()) destroyNative(ptrValue())
    }

    override suspend fun releaseImpl() = destroyNow()
  }

  private val formatDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

  private var streams: List<AvStream>? = null

  private external fun initNative(url: String): Long

  /**
   * 在归属线程上跑一段用到上下文句柄的代码。
   *
   * 挂起版（原先是一条 `runOnDispatcher` 同步桥）：句柄通过基类的 [Pointer.ptrValue]
   * 读取，此时已经在归属线程上，它是就地返回、不做多余调度。
   */
  private suspend fun <T> runWithContext(
    create: Boolean = false,
    cb: suspend (ctx: Long) -> T,
  ): T =
    withContext(formatDispatcher) {
      if (create && ctx == null) ctx = Context(initNative(url))
      cb((ctx ?: throw Exception("AvFormat closed")).ptrValue())
    }

  private external fun getStreamsNative(pctx: Long): Array<AvStream>

  suspend fun getStreams(): List<AvStream> =
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

  // < 0：出错
  // >= 0：流索引
  private external fun getPacketNative(
    ctx: Long,
    packet: Long,
  ): Int

  suspend fun getPacket(streams: Collection<AvStream>): AvPacket? =
    runWithContext { ctx ->
      val packet = AvPacket()
      while (true) {
        val ret = getPacketNative(ctx, packet.ptr())
        if (ret < 0) {
          // EOF / 出错：这个 packet 交不出去了，必须就地归还 —— native 侧
          // 只有 av_packet_free 一个释放点，没有 GC 兜底（见 AvPacket）。
          packet.close()
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

  // 这里原先有个 `protected fun finalize()` 当 GC 兜底（它意外覆写了 `Object.finalize`，
  // javap 里就是 `protected final void finalize()`），已移除：
  // - finalize 跑在 JVM 的 finalizer 线程上，JDK 18 起已被标记为待移除；而它里面是
  //   `runBlocking { close() }` —— 在 finalizer 线程上阻塞等另一条线程，时机与死锁
  //   风险都不可控。
  //
  // 名字为什么是 `closeAsync` 而不是 `close`：销毁必须回到 dispatcher 线程，而 Kotlin
  // 不允许 `suspend fun close()` 与非挂起的 `fun close()` 共存（实测报
  // "Conflicting overloads" + "Suspend function cannot override non-suspend
  // function"）。于是挂起那版只能改名。
  //
  // 同步的 `close()` 则刻意保留成**抛异常**：走 `use {}` 会立刻吵出来，而不是假装
  // 关掉了。这就是「要等线程」那类资源的预期行为 —— 必须自己 await。
  private val releaseHint: String =
    "销毁必须回到 formatDispatcher 线程上执行，请在用完后显式 await suspend 的 closeAsync()"

  override fun close(): Unit = throw UnsupportedOperationException(releaseHint)

  open suspend fun closeAsync() =
    runWithContext {
      ctx?.destroyNow()
      ctx = null
    }
}
