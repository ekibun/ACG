package soko.ekibun.acg.ui.comp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 滑轨本身的粗细。 */
private val BAR_HEIGHT = 4.dp

/** 卡顿扫光与滑轨之间留的空隙。 */
private val STALL_GAP = 1.dp

/** 卡顿扫光条的高度。 */
private val STALL_HEIGHT = 3.dp

/**
 * 轨道槽的高度。滑轨在其中**居中**，与滑块圆心保持同一水平线（`Slider` 把轨道按中心对齐到
 * 滑块），多出来的空间全部留给扫光条 —— 槽高恒为「滑轨 + 两侧各一份间隙与扫光」，上下对称。
 */
private val TRACK_AREA_HEIGHT = BAR_HEIGHT + (STALL_GAP + STALL_HEIGHT) * 2

/** 扫光扫过一遍的时长。 */
private const val STALL_SWEEP_MS = 1_400

/** 扫光带的宽度占轨道宽度的比例。 */
private const val STALL_SWEEP_FRACTION = 0.28f

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
  /** 读包卡住（`FFPlayer.Event.ReadTimeout`）：滑轨下缘扫一道浅色光。 */
  stalled: Boolean,
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
  val stallBaseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f * fade)
  val stallSweepColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f * fade)

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
      // 只有卡住时才建无穷动画：正常播放不跑空转的过渡
      val sweep = if (stalled) stallSweepPhase() else 0f
      Canvas(Modifier.fillMaxWidth().height(TRACK_AREA_HEIGHT)) {
        val barHeight = BAR_HEIGHT.toPx()
        val barTop = (size.height - barHeight) / 2
        val corner = CornerRadius(barHeight / 2)
        drawRoundRect(
          color = trackColor,
          topLeft = Offset(0f, barTop),
          size = Size(size.width, barHeight),
          cornerRadius = corner,
        )
        // 缓冲段只在领先已播放段时画：seek 之后缓冲头会短暂落后于播放位置
        if (loaded > played) {
          drawRoundRect(
            color = loadedColor,
            topLeft = Offset(0f, barTop),
            size = Size(size.width * loaded, barHeight),
            cornerRadius = corner,
          )
        }
        if (played > 0f) {
          drawRoundRect(
            color = playedColor,
            topLeft = Offset(0f, barTop),
            size = Size(size.width * played, barHeight),
            cornerRadius = corner,
          )
        }
        if (stalled) {
          val bandHeight = STALL_HEIGHT.toPx()
          val bandTop = barTop + barHeight + STALL_GAP.toPx()
          val bandCorner = CornerRadius(bandHeight / 2)
          drawRoundRect(
            color = stallBaseColor,
            topLeft = Offset(0f, bandTop),
            size = Size(size.width, bandHeight),
            cornerRadius = bandCorner,
          )
          // 一道浅色光从左扫到右，两端淡出；起点算在轨道左端之外，扫到右端时整条带又全暗
          val bandWidth = size.width * STALL_SWEEP_FRACTION
          val bandStart = -bandWidth + (size.width + bandWidth) * sweep
          drawRoundRect(
            brush =
              Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to stallSweepColor,
                1f to Color.Transparent,
                startX = bandStart,
                endX = bandStart + bandWidth,
              ),
            topLeft = Offset(0f, bandTop),
            size = Size(size.width, bandHeight),
            cornerRadius = bandCorner,
          )
        }
      }
    },
  )
}

/** 扫光相位 0..1，循环播放；调用方只在需要时组合它，动画随之起停。 */
@Composable
private fun stallSweepPhase(): Float {
  val transition = rememberInfiniteTransition(label = "stall")
  val sweep by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(STALL_SWEEP_MS, easing = LinearEasing)),
      label = "stallSweep",
    )
  return sweep
}
