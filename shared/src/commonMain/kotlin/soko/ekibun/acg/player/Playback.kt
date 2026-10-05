package soko.ekibun.acg.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import soko.ekibun.ffmpeg.AvPlayback

/** 只放两端共用、且需要被 Compose 观察的状态。 */
abstract class Playback(
  onFrame: (Long?) -> Unit,
  onEvent: (Event) -> Unit,
) : AvPlayback(onFrame, onEvent) {
  private val aspectRatioState = mutableFloatStateOf(1f)

  /** 视频宽高比，首帧到达后会自动触发重组 */
  val aspectRatio: Float get() = aspectRatioState.floatValue

  protected fun updateAspectRatio(
    width: Int,
    height: Int,
  ) {
    if (width <= 0 || height <= 0) return
    val ratio = width.toFloat() / height
    // 避免每帧都写入快照状态
    if (ratio != aspectRatioState.floatValue) aspectRatioState.floatValue = ratio
  }

  /** 人声消除（左右声道相减）开关 */
  var isMuteVoice by mutableStateOf(false)
}
