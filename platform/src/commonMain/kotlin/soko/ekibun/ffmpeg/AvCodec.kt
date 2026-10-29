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
) : Pointer(dispatcher = ThreadDispatcher("avcodec"), closeDispatcherOnClose = true) {
  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun initNative(
      stream: Long,
      hardware: Boolean,
    ): Long

    @JvmStatic
    private external fun sendPacketAndGetFramesNative(
      ctx: Long,
      stream: Long,
      packet: Long,
    ): Array<AvFrame>

    @JvmStatic
    private external fun flushNative(ctx: Long)

    @JvmStatic
    private external fun closeNative(ctx: Long)
  }

  override fun initPtr(): Long = initNative(stream.ptr, hardware)

  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)

  /** 喂入一个 packet 并取出它触发的所有帧。 */
  suspend fun sendPacketAndGetFrames(packet: AvPacket): List<AvFrame> =
    withPtr { ptr ->
      packet.use { packet ->
        sendPacketAndGetFramesNative(ptr, stream.ptr, packet.ptr).toList()
      }
    }

  /** 冲刷解码器内部缓存的尾帧。 */
  suspend fun drain(): List<AvFrame> =
    withPtr { ptr ->
      sendPacketAndGetFramesNative(ptr, stream.ptr, 0L).toList()
    }

  suspend fun flush() =
    withPtr { ptr ->
      flushNative(ptr)
    }
}
