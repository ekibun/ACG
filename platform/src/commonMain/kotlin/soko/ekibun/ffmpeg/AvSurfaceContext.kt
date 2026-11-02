package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import soko.ekibun.Pointer
import soko.ekibun.loadLibrary

/**
 * native 输出端（`ffmpeg.cpp` 的 `SWContext`）的门面：[postFrame] 把解码帧喂进 sws_scale /
 * swr_convert，[flushFrame] 把转出来的缓冲交给平台 —— 视频固定 RGBA、音频按 [audioFormat]。
 * 送显 / 送声卡由平台子类实现。
 */
abstract class AvSurfaceContext : Pointer() {
  abstract val sampleRate: Int
  abstract val channels: Int
  abstract val audioFormat: Int

  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun speedRatioNative(
      ctx: Long,
      new: Float,
    ): Float

    @JvmStatic
    private external fun initNative(
      sampleRate: Int,
      channels: Int,
      audioFormat: Int,
      output: Any?,
    ): Long

    @JvmStatic
    private external fun postFrameNative(
      ctx: Long,
      codecType: Int,
      frame: Long,
    ): Int

    /**
     * 把这一帧画进共享纹理环并定为「当前显示帧」；没有输出端、或这帧不是硬解纹理时返回 false。
     *
     * 必须在 [flushFrame] 里调，不能放进 [postFrame]：postFrame 之后还有丢帧判定
     * （收敛、过期），提前顶上去的帧会被判掉的帧盖住，画面于是跑到时间轴前面 ——
     * 播放时看不出来，步进 / 退帧时就是画面不动。
     */
    @JvmStatic
    private external fun renderSurfaceFrameNative(
      ctx: Long,
      codecType: Int,
      frame: Long,
    ): Boolean

    /**
     * 取本轮输出缓冲 —— native 把输出**拷进** `buf`，长度对得上就就地覆写，对不上才新建。
     * 下一帧同样会覆写它，调用方**必须同步消费**、不得留存引用。
     */
    @JvmStatic
    private external fun getBufferNative(
      ctx: Long,
      codecType: Int,
      buf: ByteArray?,
    ): ByteArray?

    @JvmStatic
    private external fun closeNative(ctx: Long)
  }

  /** 按流类型留住上一轮那块数组，native 侧于是能就地覆写、不必每帧分配。 */
  private val buffers = HashMap<Int, ByteArray>()

  var speedRatio = 1f
    private set

  suspend fun setSpeedRatio(value: Float) =
    withPtr { ptr ->
      speedRatio = speedRatioNative(ptr, value)
    }

  /**
   * 句柄要读 [sampleRate] 这些 **abstract** 成员，构造期还没有值 —— 等首次读句柄才建。
   *
   * 视频输出格式不在参数里：native 侧固定按 `AV_PIX_FMT_RGBA` 转码。
   */
  override fun initPtr(): Long = initNative(sampleRate, channels, audioFormat, hardwareOutput)

  /**
   * 上屏目标；null = 没有，视频走逐帧拷贝的路径。形态按平台定死、只在 open 时交出去一次：
   * 桌面是 `Long`（`VideoOutput*`），Android 是 `Surface` 对象（ffmpeg 在
   * `av_mediacodec_default_init` 里自己留 global ref）。必须在解码器建立之前就绪：
   * 帧的输出形态在解码器 open 时定死。
   */
  internal open val hardwareOutput: Any? get() = null

  suspend fun postFrame(
    codecType: Int,
    frame: AvFrame,
  ): Int {
    if (isClosed) return -1
    return withPtr { ptr -> postFrameNative(ptr, codecType, frame.ptr) }
  }

  /**
   * 把一帧交给平台消费（音频写声卡 / 视频送显）。返回值只对**音频**有意义 —— 折算掉重采样前导
   * 静音后真正上屏的时间戳；视频恒 `-1`，丢帧收敛用 [FFPlayer] 那边的 `AvFrame.timeStamp`。
   *
   * 站到这一步的帧才算真的被显示：桌面零拷贝在这里才把这一帧 blit 进共享纹理环，
   * 之前的 [postFrame] 什么都不做（硬解帧没有内存形态，它也不该被画）。
   *
   * 交给平台的 `buf` 是复用的那块数组，必须**同一调用里同步消费**：下一帧会就地覆写，
   * 要留住内容自己拷一份。
   */
  suspend fun flushFrame(
    codecType: Int,
    frame: AvFrame,
  ): Long {
    if (isClosed) return -1
    return withPtr { ptr ->
      // true = 这一帧已经画进共享纹理（桌面零拷贝），没有内存缓冲可取
      val drawn = renderSurfaceFrameNative(ptr, codecType, frame.ptr)
      flushFrameImpl(codecType, frame) {
        if (drawn) {
          null
        } else {
          getBufferNative(ptr, codecType, buffers[codecType])?.also { buffers[codecType] = it }
        }
      }
    }
  }

  abstract suspend fun flushFrameImpl(
    codecType: Int,
    frame: AvFrame,
    getBuffer: () -> ByteArray?,
  ): Long

  abstract suspend fun resume()

  abstract suspend fun pause()

  abstract suspend fun stop()

  /** 正在用这个输出端的播放器：它还在解码时，这个输出端一块资源都不能放。 */
  @Volatile
  private var player: FFPlayer? = null

  internal fun attachPlayer(player: FFPlayer) {
    this.player = player
  }

  /** 已经被下一个播放器顶替就不动。 */
  internal fun detachPlayer(player: FFPlayer) {
    if (this.player === player) this.player = null
  }

  /**
   * 先停用它的播放器再拆：子类 `close()` 后半段放的是解码线程正在写的平台资源，而 `super.close()`
   * 先于它们执行。视图比页面先 Dispose，调用方排不出这个顺序（`playback-invariants.md` §2.8）。
   *
   * 只能在 UI 侧的拆除路径上调；在播放器自己的线程上关它会等死。
   */
  override fun close() {
    val active = player
    player = null
    if (active != null) runBlocking { active.closeAsync() }
    super.close()
  }

  /** 释放 native 上下文；「只释放一次」由 [Pointer.markClosed] 保证，子类在 `super.close()` 之后再关自己的资源。 */
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
