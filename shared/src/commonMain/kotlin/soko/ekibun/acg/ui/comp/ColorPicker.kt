package soko.ekibun.acg.ui.comp

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.hue_chroma
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.materialkolor.hct.Hct
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.min

/**
 * 取色器：横轴色相、纵轴彩度的一块二维图，下面一排快捷预设。
 *
 * 没有明度轴 —— 种子色只贡献色相与彩度，明度由 MaterialKolor 重算，
 * 摆一个明度滑条只会让人以为主题会跟着变亮变暗。
 */
@Composable
fun ColorPickerDialog(
  initial: Color,
  onDismiss: () -> Unit,
  onConfirm: (Color) -> Unit,
) {
  val start = remember(initial) { Hct.fromInt(initial.toArgb()) }
  val startHue = start.hue.toFloat()
  var hue by remember { mutableFloatStateOf(startHue) }
  var ratio by remember {
    mutableFloatStateOf((start.chroma / maxChromaAt(startHue, vividTone(startHue))).toFloat().coerceIn(0f, 1f))
  }
  // 打开时先显示存下来的那个色；拖图后换成坐标算出来的色，点预设换成它的色卡
  var swatch by remember { mutableStateOf<Color?>(initial) }
  val picked = swatch ?: seedColor(hue, ratio)

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier.widthIn(max = 400.dp),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Column(
        Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text("主题色", style = MaterialTheme.typography.titleMedium)
        HueChromaPanel(
          hue = hue,
          ratio = ratio,
          onChange = { h, r ->
            hue = h
            ratio = r
            swatch = null
          },
        )
        PresetSeeds(
          hue = hue,
          ratio = ratio,
          onPick = { preset ->
            hue = preset.hue
            ratio = preset.ratio
            swatch = preset.seed
          },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            Modifier
              .size(32.dp)
              .clip(CircleShape)
              .background(picked)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
          )
          Spacer(Modifier.width(12.dp))
          Text(hexOf(picked), style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.weight(1f))
          TextButton(onClick = onDismiss) { Text("取消") }
          TextButton(onClick = { onConfirm(picked) }) { Text("确定") }
        }
      }
    }
  }
}

/** 预设：[hue] 与 [ratio] 是它落在图上的坐标，[seed] 既是点下去要存的种子色，也是色点本身的颜色。 */
private data class Preset(
  val hue: Float,
  val ratio: Float,
  val seed: Color,
)

/** Bangumi 的品牌粉，第一个预设的来源。 */
private val BangumiSeed = Color(0xFFF09199)

private val PresetPoints: List<Preset> =
  buildList {
    val seed = Hct.fromInt(BangumiSeed.toArgb())
    val hue = seed.hue.toFloat()
    val ratio = (seed.chroma / maxChromaAt(hue, vividTone(hue))).toFloat().coerceIn(0f, 1f)
    add(Preset(hue, ratio, BangumiSeed))
    // 其余色相等分一圈、彩度取八成，末尾补一个中性灰
    for (step in 1 until 8) {
      val presetHue = step * 45f
      add(Preset(presetHue, 0.8f, seedColor(presetHue, 0.8f)))
    }
    add(Preset(0f, 0f, seedColor(0f, 0f)))
  }

/** 两个色相之间的最短角距，跨 0° 也成立。 */
private fun hueDistance(
  a: Float,
  b: Float,
): Float {
  val raw = abs(a - b) % 360f
  return min(raw, 360f - raw)
}

/** 一排预设：点一下把指示圈挪过去，再在图上微调。 */
@Composable
private fun PresetSeeds(
  hue: Float,
  ratio: Float,
  onPick: (Preset) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
  ) {
    PresetPoints.forEach { preset ->
      val nearRatio = abs(ratio - preset.ratio) < 0.02f
      val nearHue = preset.ratio == 0f || hueDistance(hue, preset.hue) < 3f
      val ring = if (nearRatio && nearHue) MaterialTheme.colorScheme.onSurface else Color.Transparent
      Box(
        Modifier
          .size(24.dp)
          .clip(CircleShape)
          .background(preset.seed)
          .border(2.dp, ring, CircleShape)
          .clickable { onPick(preset) },
      )
    }
  }
}

/**
 * 色相（横）× 彩度（纵）：每列从该色相最饱和处渐变到中性灰。
 *
 * 彩度上限按列取，因为同一个 tone 下蓝色最高只到 50 上下、黄色能到 100 ——
 * 用一个统一上限，右半张图会有一大片被截成死色。
 *
 * 图本身是常量（坐标轴与明度规则都不随设置变），所以烘成 `hue_chroma.png` 当资源用：
 * 运行时不用算，也不用为了写像素引平台原语。
 */
@Composable
private fun HueChromaPanel(
  hue: Float,
  ratio: Float,
  onChange: (hue: Float, ratio: Float) -> Unit,
) {
  val shape = MaterialTheme.shapes.medium
  Box(
    Modifier
      .fillMaxWidth()
      .height(180.dp)
      .colorPickerArea { area, position ->
        onChange(
          (position.x / area.width).coerceIn(0f, 0.999f) * 360f,
          (1f - position.y / area.height).coerceIn(0f, 1f),
        )
      },
  ) {
    Image(
      painter = painterResource(Res.drawable.hue_chroma),
      contentDescription = null,
      modifier = Modifier.fillMaxSize().clip(shape),
      contentScale = ContentScale.FillBounds,
    )

    // 指示圈画在不裁切的这一层：贴边时也能看全
    Canvas(Modifier.fillMaxSize()) {
      val center = Offset(hue / 360f * size.width, (1f - ratio) * size.height)
      val radius = 9.dp.toPx()
      drawCircle(
        color = Color.Black.copy(alpha = 0.45f),
        radius = radius + 1.dp.toPx(),
        center = center,
        style = Stroke(width = 3.dp.toPx()),
      )
      drawCircle(Color.White, radius, center, style = Stroke(width = 2.dp.toPx()))
    }
  }
}

/** 按下即取色、拖动持续取色。拆成 tap / drag 两个 detector 会互相抢事件，这里自己读指针流。 */
private fun Modifier.colorPickerArea(onPick: (Size, Offset) -> Unit): Modifier =
  pointerInput(Unit) {
    awaitEachGesture {
      val down = awaitFirstDown()
      val area = Size(size.width.toFloat(), size.height.toFloat())
      onPick(area, down.position)
      down.consume()
      while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: break
        if (!change.pressed) break
        onPick(area, change.position)
        change.consume()
      }
    }
  }

private fun hexOf(color: Color): String {
  val argb = color.toArgb()
  val text = (argb and 0xFFFFFF).toString(16)
  val padded = text.padStart(6, '0')
  return "#" + padded.uppercase()
}

// 种子色与「色相 + 彩度」坐标之间的换算：配色方案在 ui/theme 里，这里只做色域几何。

/** 图底部那档中性灰的明度；各列的顶端再按自己的色域往上推。 */
internal const val BASE_TONE = 50.0

/** 请求彩度时给的“够大”值 —— HctSolver 会把它截到 sRGB 里真正能达到的上限。 */
private const val ENOUGH_CHROMA = 200.0

/** 某色相在给定明度下能达到的最大彩度。 */
internal fun maxChromaAt(
  hue: Float,
  tone: Double,
): Double = Hct.from(hue.toDouble(), ENOUGH_CHROMA, tone).chroma

/**
 * 该色相最鲜艳的明度。
 *
 * sRGB 色域在不同色相上厚薄差很多：红色峰值在 tone 54 附近，绿色要到 94 才展得开，品红反倒 40 最宽。
 * 固定 tone 50 的话，青绿蓝的上限被压掉三到五成 —— 那才是「选不到鲜艳颜色」的原因。
 */
internal fun vividTone(hue: Float): Double {
  var bestTone = BASE_TONE
  var bestChroma = 0.0
  var tone = 24
  while (tone <= 96) {
    val chroma = maxChromaAt(hue, tone.toDouble())
    if (chroma > bestChroma) {
      bestChroma = chroma
      bestTone = tone.toDouble()
    }
    tone += 4
  }
  return bestTone
}

/** 二维图上的一点还原成种子色：彩度按该色相最鲜艳那一档的上限换算。 */
internal fun seedColor(
  hue: Float,
  ratio: Float,
): Color {
  val tone = vividTone(hue)
  return Color(Hct.from(hue.toDouble(), maxChromaAt(hue, tone) * ratio, tone).toInt())
}
