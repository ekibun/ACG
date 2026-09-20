package soko.ekibun.ffmpeg

import soko.ekibun.Pointer

/**
 * 一条流的元信息快照。
 *
 * ⚠️ [ptr] 是**借来的**：它指向 [AvFormat] 内部 `AVFormatContext` 的 `AVStream*`，
 * 归那个上下文所有，随 [AvFormat.closeAsync] 一起消失。所以本类**不拥有**它 ——
 * 继承 [Pointer] 只是为了把它标记成「这里有个 native 指针」，而 [close] 保持
 * 基类那个抛异常的默认实现：谁都不该去释放它（[Pointer.releaseHint] 已写明）。
 */
data class AvStream(
  val nativePtr: Long,
  val index: Int,
  val codecType: Int,
  val sampleRate: Int,
  val channels: Int,
  val width: Int,
  val height: Int,
  val duration: Long,
  val metadata: Map<String, String>,
) : Pointer(nativePtr) {
  /** 读句柄（挂起）：借来的裸指针，读取方自己保证还在 [AvFormat] 的生命周期内。 */
  suspend fun ptr(): Long = ptrValue()

  override val releaseHint: String
    get() = "它只是借用 AvFormat 内部 AVStream* 的一个快照，不拥有它，也不该被释放"
}
