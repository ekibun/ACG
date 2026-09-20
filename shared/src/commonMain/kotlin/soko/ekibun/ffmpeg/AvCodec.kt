package soko.ekibun.ffmpeg

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary
import java.util.concurrent.Executors

class AvCodec(
  private val stream: AvStream,
) : AutoCloseable {
  private val codecDispatcher by lazy {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  }

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }
  }

  /**
   * 解码器上下文句柄的 [Pointer] 视图 —— 同 [AvFormat] 的 `ctx`：句柄要等首次解码
   * 才建，而 [Pointer] 的指针是构造参数，所以单独归一个内部子类持有。
   */
  private var ctx: Context? = null

  private inner class Context(
    ptr: Long,
  ) : Pointer(ptr, codecDispatcher) {
    override val releaseHint: String
      get() = "它由 AvCodec.closeAsync() 统一销毁：销毁必须回到 codecDispatcher 线程"

    /**
     * 销毁，只跑一次。只能由外层在 [codecDispatcher] 线程上调用 ——
     * 句柄用基类的 [Pointer.ptrValue] 读，此时已在归属 dispatcher 上，就地返回。
     */
    suspend fun destroyNow() {
      if (markClosed()) closeNative(ptrValue())
    }

    override suspend fun releaseImpl() = destroyNow()
  }

  private external fun initNative(stream: Long): Long

  private suspend fun ensureContext(create: Boolean): Long =
    withContext(codecDispatcher) {
      if (create && ctx == null) ctx = Context(initNative(stream.ptr()))
      (ctx ?: throw Exception("AvCodec closed")).ptrValue()
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
    withContext(codecDispatcher) {
      val ctx = ensureContext(true)
      try {
        sendPacketAndGetFramesNative(ctx, stream.ptr(), packet.ptr()).toList()
      } finally {
        // packet 的所有权在这里被消费：`avcodec_send_packet` 之后它就不再被需要，
        // 而 native 侧只有 `av_packet_free` 一个释放点（没有 GC 兜底，见 [AvPacket]）。
        // 放在 finally 里是为了中途抛异常也归还。
        packet.close()
      }
    }

  /**
   * 冲刷解码器：`avcodec_send_packet(NULL)` 会让解码器吐出内部缓存的尾帧。
   * 不 drain 的话，文件末尾若干帧永远播不出来。
   */
  suspend fun drain(): List<AvFrame> =
    withContext(codecDispatcher) {
      val c = ctx ?: return@withContext emptyList()
      sendPacketAndGetFramesNative(c.ptrValue(), stream.ptr(), 0L).toList()
    }

  private external fun flushNative(ctx: Long)

  suspend fun flush() =
    withContext(codecDispatcher) {
      ctx?.let { flushNative(it.ptrValue()) }
    }

  private external fun closeNative(ctx: Long)

  // 同 [AvFormat]：销毁要回 dispatcher 线程，所以挂起那版只能叫 `closeAsync`；
  // 同步的 `close()` 保留成抛异常 —— 走 `use {}` 会立刻吵出来，而不是假装关掉了。
  private val releaseHint: String =
    "销毁必须回到 codecDispatcher 线程上执行，请在用完后显式 await suspend 的 closeAsync()"

  override fun close(): Unit = throw UnsupportedOperationException(releaseHint)

  suspend fun closeAsync() =
    withContext(codecDispatcher) {
      // 句柄清掉，免得之后的 sendPacketAndGetFrames 摸到已经释放的解码器上下文：
      // ensureContext() 只在 ctx==null 时才重建。
      ctx?.destroyNow()
      ctx = null
    }
}
