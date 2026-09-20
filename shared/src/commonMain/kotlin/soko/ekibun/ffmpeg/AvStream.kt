package soko.ekibun.ffmpeg

/**
 * 一条流的元信息快照。
 *
 * ⚠️ [ptr] 是**借来的**：它指向 [AvFormat] 内部 `AVFormatContext` 的 `AVStream*`，归那个
 * 上下文所有，随 [AvFormat] 关闭一起消失。
 *
 * 所以本类**不继承** `Pointer`：「不拥有」这条于是写在**类型**上 —— 借来的东西没有
 * `close()`，`use {}` / `invokeOnCompletion` 这类自动调用点也就无从把它关掉（曾靠
 * 「继承 + 覆写 close() 抛异常」，那要跑到运行期才拦得住）。
 */
data class AvStream(
  val ptr: Long,
  val index: Int,
  val codecType: Int,
  val sampleRate: Int,
  val channels: Int,
  val width: Int,
  val height: Int,
  val duration: Long,
  val metadata: Map<String, String>,
)
