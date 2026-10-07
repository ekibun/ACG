package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.jniLoadLibrary

/**
 * 一个解码器实例 —— 绑定在 [stream] 上。
 *
 * 归属线程是**每实例一条**（构造期起，名字就是 `avcodec`），回收也得按实例来：构造参数
 * `closeDispatcherOnClose = true` 声明这条 dispatcher 由本对象独占，基类会在归还落地之后把它
 * 关掉（[Pointer] 的 `closeDeferred()` 完成时）。共享那条（[AvFormat] 的伴生对象）不能这么传
 * —— 那是全进程一条。
 */
class AvCodec(
  private val stream: AvStream,
) : Pointer(dispatcher = ThreadDispatcher("avcodec"), closeDispatcherOnClose = true) {
  companion object {
    init {
      jniLoadLibrary("ffmpeg")
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

  /**
   * 喂入一个 packet 并取出它触发产出的所有帧。
   *
   * 必须返回列表：`avcodec_receive_frame` 需要循环调用到 `EAGAIN`，
   * 一个 packet 可能产出 0 帧（帧被解码器内部缓存，如 B 帧重排）或多帧。
   * 只取一帧会丢帧。
   */
  suspend fun sendPacketAndGetFrames(packet: AvPacket): List<AvFrame> =
    withPtr { ptr ->
      packet.use { packet ->
        sendPacketAndGetFramesNative(ptr, stream.ptr, packet.ptr).toList()
      }
    }

  /**
   * 冲刷解码器：`avcodec_send_packet(NULL)` 会让解码器吐出内部缓存的尾帧。
   * 不 drain 的话，文件末尾若干帧永远播不出来。
   */
  suspend fun drain(): List<AvFrame> =
    withPtr { ptr ->
      sendPacketAndGetFramesNative(ptr, stream.ptr, 0L).toList()
    }

  suspend fun flush() =
    withPtr { ptr ->
      flushNative(ptr)
    }
}
