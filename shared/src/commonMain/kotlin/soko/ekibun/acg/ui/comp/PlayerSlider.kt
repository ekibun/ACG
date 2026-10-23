package soko.ekibun.acg.ui.comp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * 播放进度条：底轨、已缓冲段、已播放段都画在滑轨**内部**（缓冲压在已播放之下），滑块是圆点。
 *
 * 轨道宽度由 `Slider` 让给「滑块行程」（它把轨道按 `-thumbWidth` 量宽），所以
 * `分数 × 宽度` 正好落在滑块中心，三段不会与滑块错位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSlider(
  value: Float,
  buffered: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  enabled: Boolean,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val span = valueRange.endInclusive - valueRange.start
  val played = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
  val loaded = if (span > 0f) ((buffered - valueRange.start) / span).coerceIn(0f, 1f) else 0f
  val fade = if (enabled) 1f else 0.38f
  val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = fade)
  val playedColor = MaterialTheme.colorScheme.primary.copy(alpha = fade)
  val loadedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * fade)

  Slider(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    enabled = enabled,
    onValueChangeFinished = onValueChangeFinished,
    valueRange = valueRange,
    thumb = {
      Box(Modifier.size(16.dp).background(playedColor, CircleShape))
    },
    track = {
      Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val corner = CornerRadius(size.height / 2)
        drawRoundRect(color = trackColor, cornerRadius = corner)
        // 缓冲段只在领先已播放段时画：seek 之后缓冲头会短暂落后于播放位置
        if (loaded > played) {
          drawRoundRect(
            color = loadedColor,
            size = Size(size.width * loaded, size.height),
            cornerRadius = corner,
          )
        }
        if (played > 0f) {
          drawRoundRect(
            color = playedColor,
            size = Size(size.width * played, size.height),
            cornerRadius = corner,
          )
        }
      }
    },
  )
}
