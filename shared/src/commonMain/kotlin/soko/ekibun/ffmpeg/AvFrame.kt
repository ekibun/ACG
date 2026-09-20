package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary

class AvFrame(
  nativePtr: Long,
  val timeStamp: Long,
  val width: Int,
  val height: Int,
) : Pointer(nativePtr) {
  /** 读句柄（挂起）：帧的 native 调用点都在 ffmpeg 的 dispatcher 上。 */
  suspend fun ptr(): Long = ptrValue()

  /** 标记该帧正在被哪个 PTS 播放轮次消费，避免同一帧被重复取用。 */
  var processing: FFPlayer.PTS? = null

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }

    external fun closeNative(ptr: Long)
  }

  /**
   * 一次性出场守卫交给基类（[Pointer.markClosed]）—— 本类不再自带
   * `AtomicBoolean`。守卫必须是原子的这条要求没变：[FFPlayer] 在 `decoded` 与
   * `drained` 两处遍历关帧，同一帧可能被关两次，而 native 的 `closeNative` 不可重入。
   *
   * 本类**不设 GC 兜底**（原先用 `java.lang.ref.Cleaner`，已移除）：Android 上
   * `java.lang.ref.Cleaner` 是 **API 33** 才有的类，本工程 `minSdk = 24` 且没开
   * core library desugaring，而它原先挂在 companion 字段上 —— 类加载即
   * `NoClassDefFoundError`。代价是漏关就真漏一帧 native 内存，所以 [FFPlayer]
   * 的关闭路径必须逐个清点（它也确实是这么做的）。
   */
  override suspend fun releaseImpl() = closeNative(ptrValue())
}
