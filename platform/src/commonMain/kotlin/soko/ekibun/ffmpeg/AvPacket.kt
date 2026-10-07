package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary

class AvPacket : Pointer() {
  /**
   * packet 句柄 —— 覆写基类的句柄来源。
   *
   * 句柄要调实例方法 `initNative()` 才拿得到，而 Kotlin 不允许在 `super(...)` 的实参里
   * 出现 `this`，所以本类**留空基类的第一个实参**、在这里现算。首次读句柄（`packet.ptr`）
   * 时才建 —— 一次没读就 `close()` 的话，[Pointer.closeDeferred] 不会为了「还」去建一个。
   */
  override fun initPtr(): Long = initNative()

  var streamIndex: Int = -1

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun initNative(): Long

    @JvmStatic
    external fun closeNative(ptr: Long)
  }

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
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
