package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary

abstract class AvPlayback(
  val onFrame: (Long?) -> Unit,
) : Pointer() {
  abstract val sampleRate: Int
  abstract val channels: Int
  abstract val audioFormat: Int
  abstract val videoFormat: Int

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }
  }

  private external fun speedRatioNative(
    ctx: Long,
    new: Float,
  ): Float

  /** 读播放倍速。挂起：句柄要经 [Pointer.withPtr] 取，属性形态表达不了。 */
  suspend fun speedRatio(): Float = withPtr { ptr -> speedRatioNative(ptr, 0f) }

  /** 写播放倍速。 */
  suspend fun setSpeedRatio(value: Float) =
    withPtr { ptr ->
      speedRatioNative(ptr, value)
    }

  private external fun initNative(
    sampleRate: Int,
    channels: Int,
    audioFormat: Int,
    videoFormat: Int,
  ): Long

  /**
   * native 上下文句柄 —— 覆写基类的句柄来源。
   *
   * 句柄要读 [sampleRate] 这些 **abstract** 成员，而构造期它们还没有值，所以只能等
   * **首次读句柄**时才建（这是基类 [Pointer.initPtr] 的时机约定，见那边的类文档）；
   * 一次都没用过就 `close()` 也没关系：[Pointer.closeDeferred] 不会为了「还」去现建一个。
   */
  override fun initPtr(): Long = initNative(sampleRate, channels, audioFormat, videoFormat)

  private external fun postFrameNative(
    ctx: Long,
    codecType: Int,
    frame: Long,
  ): Int

  suspend fun postFrame(
    codecType: Int,
    frame: AvFrame,
  ): Int {
    if (isClosed) return -1
    return withPtr { ptr -> postFrameNative(ptr, codecType, frame.ptr) }
  }

  private external fun getBuffer(
    ctx: Long,
    codecType: Int,
  ): ByteArray

  suspend fun flushFrame(
    codecType: Int,
    frame: AvFrame,
  ): Long {
    if (isClosed) return -1
    return withPtr { ptr ->
      when (codecType) {
        AVMediaType.AUDIO -> {
          val offset = flushAudioBuffer(getBuffer(ptr, codecType))
          println("PTS ${frame.timeStamp} ${frame.timeStamp - offset * AvFormat.AV_TIME_BASE / sampleRate}")
          return@withPtr if (offset < 0) -1 else frame.timeStamp - offset * AvFormat.AV_TIME_BASE / sampleRate
        }
        AVMediaType.VIDEO -> flushVideoBuffer(getBuffer(ptr, codecType), frame.width, frame.height)
      }
      -1
    }
  }

  abstract suspend fun flushAudioBuffer(buf: ByteArray): Int

  abstract fun flushVideoBuffer(
    buf: ByteArray,
    width: Int,
    height: Int,
  )

  abstract suspend fun resume()

  abstract suspend fun pause()

  abstract suspend fun stop()

  private external fun closeNative(ctx: Long)

  /**
   * 释放 native 上下文。幂等 —— 两个平台的 `Playback` 子类都会在 `super.close()`
   * 之后再关自己的资源，重复调用不能变成第二次 `closeNative`。
   *
   * 句柄的「只释放一次」由基类 [Pointer.markClosed] 保证；这一层只管把 native 上下文
   * 还回去。
   */
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
