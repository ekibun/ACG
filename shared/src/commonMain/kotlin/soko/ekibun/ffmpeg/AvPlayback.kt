package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary
import java.nio.ByteBuffer

abstract class AvPlayback(
  val onFrame: (Long?) -> Unit,
) : Pointer() {
  abstract val sampleRate: Int
  abstract val channels: Int
  abstract val audioFormat: Int

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
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
     * 取本轮输出缓冲 —— **DirectByteBuffer，零拷贝零分配**，直接指向 native 那块内存
     * （`SWContext::videoBuffer` / `audioBuffer`）。下一帧会就地覆写它，所以调用方
     * **必须同步消费**，不得留存引用（见 [flushFrame]）。
     */
    @JvmStatic
    private external fun getBuffer(
      ctx: Long,
      codecType: Int,
    ): ByteBuffer?

    /** DirectByteBuffer 背后的 native 地址（0 = 不是 direct）。给「零拷贝包成 Skia Data」用。 */
    @JvmStatic
    private external fun bufferAddress(buffer: ByteBuffer): Long

    /** [bufferAddress] 的包装：桌面端拿它取 native 缓冲的地址。 */
    internal fun addressOf(buffer: ByteBuffer): Long = bufferAddress(buffer)

    /** native→native 原样拷贝（给桌面端把 RGBA 写进复用位图，见 `DesktopPlayback`）。 */
    @JvmStatic
    private external fun copyPixelsNative(
      src: Long,
      dst: Long,
      bytes: Int,
    )

    internal fun copyPixels(
      src: Long,
      dst: Long,
      bytes: Int,
    ) = copyPixelsNative(src, dst, bytes)

    @JvmStatic
    private external fun closeNative(ctx: Long)
  }

  /** 读播放倍速。挂起：句柄要经 [Pointer.withPtr] 取，属性形态表达不了。 */
  suspend fun speedRatio(): Float = withPtr { ptr -> speedRatioNative(ptr, 0f) }

  /** 写播放倍速。 */
  suspend fun setSpeedRatio(value: Float) =
    withPtr { ptr ->
      speedRatioNative(ptr, value)
    }

  /**
   * native 上下文句柄 —— 覆写基类的句柄来源。
   *
   * 句柄要读 [sampleRate] 这些 **abstract** 成员，而构造期它们还没有值，所以只能等
   * **首次读句柄**时才建（这是基类 [Pointer.initPtr] 的时机约定，见那边的类文档）；
   * 一次都没用过就 `close()` 也没关系：[Pointer.closeDeferred] 不会为了「还」去现建一个。
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
   * 把一帧交给平台消费（音频写声卡 / 视频送显）。
   *
   * 返回值只对**音频**有意义：它是折算掉重采样前导静音后"真正上屏的时间戳"。
   * 视频没有这个概念 —— 视频路径恒返回 `-1`，送显只是它的副作用。
   * 所以调用方**不能**用这个返回值去判断视频帧的时间戳：丢帧收敛要用
   * [FFPlayer] 那边的 `AvFrame.timeStamp`（单位同样是 `AV_TIME_BASE` 微秒）。
   *
   * ⚠️ 交给平台的 `ByteBuffer` 是 **native 内存上的 direct buffer**（零拷贝）：实现必须
   * **在同一调用里同步消费**，不能留存、不能跨帧使用 —— 下一帧 `sws_scale` / `swr_convert`
   * 会就地覆写这块内存。要留住像素，自己拷一份。
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

  /** 见 [flushFrame] 的 ⚠️：`buf` 是 direct buffer，必须同步消费。 */
  abstract suspend fun flushAudioBuffer(buf: ByteBuffer): Int

  /** 见 [flushFrame] 的 ⚠️：`buf` 是 direct buffer，必须同步消费。 */
  abstract fun flushVideoBuffer(
    buf: ByteBuffer,
    width: Int,
    height: Int,
  )

  abstract suspend fun resume()

  abstract suspend fun pause()

  abstract suspend fun stop()

  /**
   * 释放 native 上下文。幂等 —— 两个平台的 `Playback` 子类都会在 `super.close()`
   * 之后再关自己的资源，重复调用不能变成第二次 `closeNative`。
   *
   * 句柄的「只释放一次」由基类 [Pointer.markClosed] 保证；这一层只管把 native 上下文
   * 还回去。
   */
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
