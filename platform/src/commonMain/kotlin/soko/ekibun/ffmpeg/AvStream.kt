package soko.ekibun.ffmpeg

/** 一条流的元信息快照；[ptr] 是借来的，所以本类不继承 `Pointer`。 */
data class AvStream(
  val ptr: Long,
  val index: Int,
  val codecType: Int,
  val sampleRate: Int,
  val channels: Int,
  val width: Int,
  val height: Int,
  /** 流时基的分子（分母见 [timeBaseDen]）：一个 tick = num/den 秒。 */
  val timeBaseNum: Int,
  val timeBaseDen: Int,
  /** 流时长（微秒）；0 = 流上没有。取总时长见 [AvFormat.getDurationUs]。 */
  val duration: Long,
  val metadata: Map<String, String>,
)
