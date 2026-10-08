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
  /**
   * 流时基（`AVStream::time_base`）的分子，分母见 [timeBaseDen]；一个 tick = num/den 秒。
   *
   * 它就是这条流时间戳的**最小刻度**。容器级 seek 折微秒时会吃掉半格以内的偏移
   * （见 [AvFormat.seekTo]），上层要算「比半格大、又不足一个帧间隔」的步长就只能靠它 ——
   * 这是把它一并递上来的唯一理由。**原样**递，不折成微秒：1/12800 的 tick 是 78.125µs，取整就没了。
   */
  val timeBaseNum: Int,
  val timeBaseDen: Int,
  /** 流时长（微秒）；0 = 流上没有 —— HLS 的总时长只在格式层，取总时长见 [AvFormat.getDurationUs]。 */
  val duration: Long,
  val metadata: Map<String, String>,
)
