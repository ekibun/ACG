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
  /** 平均帧率（帧/秒）；拿不到时为 0。 */
  val frameRate: Float,
  /** 流时长（微秒）；0 = 流上没有。取总时长见 [AvFormat.getDurationUs]。 */
  val duration: Long,
  val metadata: Map<String, String>,
) {
  /**
   * 帧间隔（微秒）：默认按帧率换算，运行时用实测的两帧之差校正。
   * 0 = 既拿不到帧率、也还没量到。
   */
  internal var frameIntervalUs: Long =
    if (frameRate > 0f) (1_000_000f / frameRate).toLong() else 0
}
