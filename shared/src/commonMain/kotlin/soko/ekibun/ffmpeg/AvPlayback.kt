package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary
import java.util.concurrent.atomic.AtomicBoolean

abstract class AvPlayback(
  val onFrame: (Long?) -> Unit,
) : AutoCloseable {
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

  /** 读播放倍速。挂起：句柄要经持有者的 `ptrValue()` 取，属性形态表达不了。 */
  suspend fun speedRatio(): Float = speedRatioNative(ctxHandle.ptrValue(), 0f)

  /** 写播放倍速。 */
  suspend fun setSpeedRatio(value: Float) {
    speedRatioNative(ctxHandle.ptrValue(), value)
  }

  /**
   * façade 级的「关闭」标记 —— 这一份**不能**收进 [Pointer]，理由是本类的句柄是惰性的。
   *
   * [postFrame] / [flushFrame] 必须在**句柄还没建出来**的时候就回答「关过没有」：
   * 否则关闭之后再 [postFrame] 会惰性地建出一个 native 上下文，而那时已经没有人会去
   * 释放它了。[Pointer] 管的是**句柄自己**的关闭标记，句柄不存在时它无从回答。
   *
   * 与 [Handle] 那层（继承自 [Pointer]）是正交的两个问题：这一层答「这个播放器还能不能
   * 用」，那一层答「native 上下文还了没有」。
   */
  private val closed = AtomicBoolean(false)

  private external fun initNative(
    sampleRate: Int,
    channels: Int,
    audioFormat: Int,
    videoFormat: Int,
  ): Long

  /**
   * native 上下文句柄的 [Pointer] 视图。
   *
   * 句柄依赖 [sampleRate] 这些 **abstract** 成员 —— 基类构造期它们还没有值，所以只能
   * 等首次使用时才建。而 [Pointer] 的指针是构造参数（不能后填），于是句柄单独归一个
   * 内部子类持有：**惰性的是这个子类实例，不是指针槽位**。
   */
  private val ctxHandleLazy =
    lazy {
      Handle(initNative(sampleRate, channels, audioFormat, videoFormat))
    }

  /** [ctxHandleLazy] 的取值入口 —— 惰性的是**这个实例**，不是指针槽位。 */
  private val ctxHandle: Handle by ctxHandleLazy

  private inner class Handle(
    ptr: Long,
  ) : Pointer(ptr) {
    /** 本子类不带 dispatcher：释放就在调用线程上同步完成。 */
    override suspend fun releaseImpl() = closeNative(ptrValue())
  }

  private external fun postFrameNative(
    ctx: Long,
    codecType: Int,
    frame: Long,
  ): Int

  suspend fun postFrame(
    codecType: Int,
    frame: AvFrame,
  ): Int {
    if (closed.get()) return -1
    return postFrameNative(ctxHandle.ptrValue(), codecType, frame.ptr())
  }

  private external fun getBuffer(
    ctx: Long,
    codecType: Int,
  ): ByteArray

  suspend fun flushFrame(
    codecType: Int,
    frame: AvFrame,
  ): Long {
    if (closed.get()) return -1
    when (codecType) {
      AVMediaType.AUDIO -> {
        val offset = flushAudioBuffer(getBuffer(ctxHandle.ptrValue(), codecType))
        println("PTS ${frame.timeStamp} ${frame.timeStamp - offset * AvFormat.AV_TIME_BASE / sampleRate}")
        return if (offset < 0) -1 else frame.timeStamp - offset * AvFormat.AV_TIME_BASE / sampleRate
      }
      AVMediaType.VIDEO -> flushVideoBuffer(getBuffer(ctxHandle.ptrValue(), codecType), frame.width, frame.height)
    }
    return -1
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
   * 句柄的「只释放一次」由 [Handle]（即 [Pointer]）保证；这一层只管「播放器已关」。
   */
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    // 一次都没用过 ptr，就等于上下文还没建出来，没什么可归还的
    if (ctxHandleLazy.isInitialized()) ctxHandle.close()
  }
}
