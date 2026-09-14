package soko.ekibun.ffmpeg

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import soko.ekibun.jniLoadLibrary
import java.util.concurrent.Executors

class AvCodec(
  private val stream: AvStream
) {
  private val dispatcher by lazy {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  }
  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }
  }

  private var pctx: Long? = null
  private external fun initNative(stream: Long): Long
  private suspend fun ensureContext(create: Boolean): Long {
    return withContext(dispatcher) {
      if (create && pctx == null) pctx = initNative(stream.ptr)
      if ((pctx ?: 0L) == 0L) throw Exception("AvCodec closed")
      pctx!!
    }
  }

  private external fun sendPacketAndGetFramesNative(
    ctx: Long,
    stream: Long,
    packet: Long,
  ): Array<AvFrame>

  /**
   * 喂入一个 packet 并取出它触发产出的所有帧。
   *
   * 必须返回列表：`avcodec_receive_frame` 需要循环调用到 `EAGAIN`，
   * 一个 packet 可能产出 0 帧（帧被解码器内部缓存，如 B 帧重排）或多帧。
   * 只取一帧会丢帧。
   */
  suspend fun sendPacketAndGetFrames(packet: AvPacket): List<AvFrame> =
    withContext(dispatcher) {
      val ctx = ensureContext(true)
      sendPacketAndGetFramesNative(ctx, stream.ptr, packet.ptr).toList()
    }

  /**
   * 冲刷解码器：`avcodec_send_packet(NULL)` 会让解码器吐出内部缓存的尾帧。
   * 不 drain 的话，文件末尾若干帧永远播不出来。
   */
  suspend fun drain(): List<AvFrame> = withContext(dispatcher) {
    val ctx = pctx ?: return@withContext emptyList()
    sendPacketAndGetFramesNative(ctx, stream.ptr, 0L).toList()
  }

  private external fun flushNative(ctx: Long)
  suspend fun flush() = withContext(dispatcher) {
    pctx?.let { flushNative(it) }
  }

  private external fun closeNative(ctx: Long)
  suspend fun close() = withContext(dispatcher) {
    pctx?.let {
      closeNative(it)
      // Clear the handle so a later sendPacketAndGetFrames cannot reach the
      // freed decoder context: ensureContext() only rebuilds when pctx==null.
      pctx = null
    }
  }
}
