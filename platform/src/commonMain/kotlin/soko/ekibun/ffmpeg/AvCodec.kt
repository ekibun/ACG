package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.loadLibrary

/** 一个解码器实例，绑定在 [stream] 上；每实例独占一条归属线程。 */
class AvCodec(
  private val stream: AvStream,
  /**
   * 视频解码优先走硬件（桌面 D3D11VA、Android MediaCodec）。这只是偏好：native 侧
   * 拿不到设备、或这个 codec 没有加速版本时自动回落软解，不报错。默认关 —— 直接构造
   * 解码器的地方（测试）要的是确定性，播放侧由 [FFPlayer] 显式打开。
   */
  private val hardware: Boolean = false,
  /**
   * 上屏目标；null = 没有。给了就走零拷贝：桌面在解码线程里把像素写进它的共享纹理，
   * Android 指一份 `Surface`，MediaCodec 直接渲染进去。见 [AvSurfaceContext.hardwareOutput]。
   *
   * 只在 open 时用一次：native 把它记在 codec 自己身上（桌面存 `AVCodecContext.opaque`）。
   * 帧的形态在 open 时就按它定死，所以之后收发帧都不传它；软解回落时 native 不记。
   */
  private val hardwareOutput: Any? = null,
) : Pointer(dispatcher = ThreadDispatcher("avcodec"), closeDispatcherOnClose = true) {
  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun initNative(
      stream: Long,
      hardware: Boolean,
      output: Any?,
    ): Long

    /** 送包：0 = 收下，1 = EAGAIN（输入缓冲满，先去收帧），<0 = 失败（native 已记日志）。 */
    @JvmStatic
    private external fun sendPacketNative(
      ctx: Long,
      stream: Long,
      packet: Long,
    ): Int

    /** 收帧：null = 解码器已经吐干净；空数组 = 现在没有。 */
    @JvmStatic
    private external fun receiveFramesNative(
      ctx: Long,
      stream: Long,
    ): Array<AvFrame>?

    @JvmStatic
    private external fun flushNative(ctx: Long)

    @JvmStatic
    private external fun closeNative(ctx: Long)
  }

  val streamIndex = stream.index

  override fun initPtr(): Long = initNative(stream.ptr, hardware, hardwareOutput)

  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)

  suspend fun sendPacketAndGetFrames(packet: AvPacket? = null): Pair<Boolean, List<AvFrame>?> =
    withPtr { ptr ->
      val accepted = sendPacketNative(ptr, stream.ptr, packet?.ptr ?: 0L) != 1
      accepted to receiveFramesNative(ptr, stream.ptr)?.toList()
    }

  suspend fun flush() =
    withPtr { ptr ->
      flushNative(ptr)
    }
}
