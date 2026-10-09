package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.loadLibrary

/**
 * native 输出端（ffmpeg.cpp 的 `SWContext`，Context 即指它）的门面：[postFrame] 把解码帧喂进
 * sws_scale / swr_convert，[flushFrame] 把转出来的缓冲交给平台 —— 视频固定 RGBA、音频按
 * [audioFormat]；送显 / 送声卡由平台子类实现（`acg.player` 的两个 `SurfaceContext` 子类）。
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
    ): Long

    @JvmStatic
    private external fun postFrameNative(
      ctx: Long,
      codecType: Int,
      frame: Long,
    ): Int

    /**
     * 取本轮输出缓冲 —— native 把输出**拷进** `buf`：长度对得上就就地覆写那块，对不上才新建。
     * 下一帧同样会覆写它，所以调用方**必须同步消费**，不得留存引用（见 [flushFrame]）。
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

  /** 按流类型留住上一轮那块数组，好让 native 侧能就地覆写、不每帧分配。 */
  private val buffers = HashMap<Int, ByteArray>()

  private fun getBuffer(
    ctx: Long,
    codecType: Int,
  ): ByteArray? =
    getBufferNative(
      ctx,
      codecType,
      buffers[codecType],
    )?.also { buffers[codecType] = it }

  var speedRatio = 1f
    private set

  suspend fun setSpeedRatio(value: Float) =
    withPtr { ptr ->
      speedRatio = speedRatioNative(ptr, value)
    }

  /**
   * native 上下文句柄。句柄要读 [sampleRate] 这些 **abstract** 成员，构造期还没有值，
   * 所以等**首次读句柄**才建（[Pointer.initPtr] 的时机约定）；一次没用过就 `close()` 也不会
   * 现建一个（[Pointer.closeDeferred] 不为「还」而建）。
   *
   * 视频输出格式不在参数里：native 侧固定按 `AV_PIX_FMT_RGBA` 转码。
   */
  override fun initPtr(): Long = initNative(sampleRate, channels, audioFormat)

  suspend fun postFrame(
    codecType: Int,
    frame: AvFrame,
  ): Int {
    if (isClosed) return -1
    return withPtr { ptr -> postFrameNative(ptr, codecType, frame.ptr) }
  }

  /**
   * 把一帧交给平台消费（音频写声卡 / 视频送显）。返回值只对**音频**有意义：折算掉重采样前导
   * 静音后「真正上屏的时间戳」；视频恒 `-1`，丢帧收敛要用 [FFPlayer] 那边的 `AvFrame.timeStamp`
   * （单位同样是 `AV_TIME_BASE` 微秒）。
   *
   * 交给平台的 `buf` 是**复用的那块数组**，必须**同一调用里同步消费** —— 下一帧
   * `sws_scale` / `swr_convert` 会就地覆写（同 [getBuffer] 的约束）；要留住内容自己拷一份。
   */
  suspend fun flushFrame(
    codecType: Int,
    frame: AvFrame,
  ): Long {
    if (isClosed) return -1
    return withPtr { ptr ->
      when (codecType) {
        AVMediaType.AUDIO -> {
          val offset = flushAudioBuffer(getBuffer(ptr, codecType) ?: return@withPtr -1)
          return@withPtr if (offset < 0) -1 else frame.timeStamp - offset * AvFormat.AV_TIME_BASE / sampleRate
        }
        AVMediaType.VIDEO ->
          getBuffer(ptr, codecType)?.let { flushVideoBuffer(it, frame.width, frame.height) }
      }
      -1
    }
  }

  /** 见 [flushFrame] 那条「同步消费」约束：`buf` 会被下一帧覆写。 */
  abstract suspend fun flushAudioBuffer(buf: ByteArray): Int

  /** 见 [flushFrame] 那条「同步消费」约束：`buf` 会被下一帧覆写。 */
  abstract suspend fun flushVideoBuffer(
    buf: ByteArray,
    width: Int,
    height: Int,
  )

  abstract suspend fun resume()

  abstract suspend fun pause()

  abstract suspend fun stop()

  /**
   * 释放 native 上下文，幂等：两个平台的 `SurfaceContext` 子类都在 `super.close()` 之后再关
   * 自己的资源，重复调用不能变成第二次 `closeNative`（「只释放一次」由 [Pointer.markClosed] 保证）。
   */
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
