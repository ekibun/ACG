package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary

class AvPacket : AutoCloseable {
  /**
   * packet 句柄的 [Pointer] 视图。
   *
   * 本类**不继承** [Pointer]：句柄要调实例方法 `initNative()` 才拿得到，而 Kotlin 不允许
   * 在 `super(...)` 的实参里出现 `this`；[Pointer] 的指针又是**构造参数**（创建即确定、
   * 之后不可更改），后填不进去。于是句柄单独归一个内部子类持有，本类只负责转发。
   */
  private val handle: Handle = Handle(initNative())

  private inner class Handle(
    ptr: Long,
  ) : Pointer(ptr) {
    /**
     * 本子类不带 dispatcher，所以基类 [Pointer.close] 走的就是同步路径，无需另立入口。
     * 句柄用 [Pointer.ptrValue] 读 —— 没有 dispatcher 时它就地在调用线程上返回。
     */
    override suspend fun releaseImpl() = closeNative(ptrValue())
  }

  /** 读句柄（挂起）—— `av_read_frame` / `avcodec_send_packet` 都要它。 */
  suspend fun ptr(): Long = handle.ptrValue()

  var streamIndex: Int = -1

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }
  }

  private external fun initNative(): Long

  external fun closeNative(ptr: Long)

  /**
   * 归还底层的 `AVPacket`。**必须显式调用**。
   *
   * 这里原先靠 `protected fun finalize()` 兜底（它意外覆写了 `Object.finalize`，javap 里
   * 就是 `protected final void finalize()`），已移除：
   * - `finalize` 跑在 JVM 的 finalizer 线程上、时机不可控，且自 JDK 18 起被标记为待移除；
   * - native 侧只有 `av_packet_free` 一个释放点（`av_packet_alloc` 出来的包，
   *   `av_read_frame` / `avcodec_send_packet` 都不负责归还）。一集动画几万个 packet，
   *   全指望 GC 兜底就是持续增长的 native 内存。
   *
   * 于是所有权是**显式的**：谁 `getPacket` 拿到，谁负责走完消费链路；
   * 中途丢弃的分支必须就地 [close]。
   *
   * 幂等：由 [Pointer.markClosed] 抢名额，`av_packet_free` 不可重入 ——
   * 归还点在消费侧（[AvCodec.sendPacketAndGetFrames] 的 `finally`），而丢弃点在
   * [AvFormat.getPacket] 与 [FFPlayer] 的几条提前退出分支上，两边可能都跑到。
   */
  override fun close() {
    handle.close()
  }
}
