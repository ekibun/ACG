package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.loadLibrary

/** 一个解码器实例，绑定在 [stream] 上；每实例独占一条归属线程。 */
class AvCodec(
  private val stream: AvStream,
) : Pointer(dispatcher = ThreadDispatcher("avcodec"), closeDispatcherOnClose = true) {
  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun initNative(stream: Long): Long

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

  override fun initPtr(): Long = initNative(stream.ptr)

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
