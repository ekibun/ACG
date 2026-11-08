package soko.ekibun.acg.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * 应用主题：种子色 → 整套 Material 3 配色，亮暗与纯黑在此收口。
 *
 * 配色交给 MaterialKolor（官方算法的 KMP 实现）：HCT 造色板、按 tone 取色，同一个种子出同一套颜色。
 * 错误色固定官方 25° 色相，不跟种子走 —— 否则粉色种子会长出粉色错误。纯黑交给 `isAmoled`，只压表面档。
 *
 * 排版沿用 M3 默认 15 级 —— 少填一级就会出现「页面自己一套、组件内部一套」。
 */
@Composable
fun AppTheme(
  settings: AppThemeSettings = AppThemeSettings(),
  content: @Composable () -> Unit,
) {
  val isDark =
    when (settings.darkMode) {
      DarkMode.Light -> false
      DarkMode.Dark, DarkMode.Black -> true
      DarkMode.Auto -> isSystemInDarkTheme()
    }
  val isBlack = settings.darkMode == DarkMode.Black
  val colorScheme =
    remember(settings.seedColor, isDark, isBlack) {
      dynamicColorScheme(
        seedColor = settings.seedColor,
        isDark = isDark,
        isAmoled = isBlack,
        style = PaletteStyle.Content,
      )
    }

  MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, content = content)
}

@Preview
@Composable
private fun ColorPreviewLight() {
  AppTheme(AppThemeSettings(darkMode = DarkMode.Light)) { ColorPreview() }
}

@Preview
@Composable
private fun ColorPreviewDark() {
  AppTheme(AppThemeSettings(darkMode = DarkMode.Dark)) { ColorPreview() }
}

@Preview
@Composable
private fun ColorPreviewBlack() {
  AppTheme(AppThemeSettings(darkMode = DarkMode.Black)) { ColorPreview() }
}

/**
 * 配色预览：卡上用容器色铺底、名字取它对应的 on 色 —— 一眼看出这对配色读不读得清。
 *
 * 不摆组件：组件观感要看真实页面，色卡只回答「这两个颜色压在一起能不能读」。
 */
@Composable
private fun ColorPreview() {
  val scheme = MaterialTheme.colorScheme
  // 名字 → (底色, 压在上面的 on 色)。on 为 null 表示没有配对内容（描边、表面档位），只画色块。
  // mapOf 是 LinkedHashMap，插入顺序即排版顺序；铺满 ColorScheme 全部 48 个角色，别让它悄悄漏掉。
  val pairs: Map<String, Pair<Color, Color?>> =
    mapOf(
      "Primary" to (scheme.primary to scheme.onPrimary),
      "PrimaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
      "Secondary" to (scheme.secondary to scheme.onSecondary),
      "SecondaryContainer" to (scheme.secondaryContainer to scheme.onSecondaryContainer),
      "Tertiary" to (scheme.tertiary to scheme.onTertiary),
      "TertiaryContainer" to (scheme.tertiaryContainer to scheme.onTertiaryContainer),
      "Error" to (scheme.error to scheme.onError),
      "ErrorContainer" to (scheme.errorContainer to scheme.onErrorContainer),
      "Background" to (scheme.background to scheme.onBackground),
      "Surface" to (scheme.surface to scheme.onSurface),
      "SurfaceVariant" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
      "InverseSurface" to (scheme.inverseSurface to scheme.inverseOnSurface),
      // inversePrimary 只当「压在 inverseSurface 上的强调色」用（Snackbar 行动按钮），底色因此是 inverseSurface
      "InversePrimary" to (scheme.inverseSurface to scheme.inversePrimary),
      // 固定色明暗同值；Variant 卡是同一块 Fixed 底色换成次级文字色
      "PrimaryFixed" to (scheme.primaryFixed to scheme.onPrimaryFixed),
      "PrimaryFixedDim" to (scheme.primaryFixedDim to scheme.onPrimaryFixed),
      "PrimaryFixedVariant" to (scheme.primaryFixed to scheme.onPrimaryFixedVariant),
      "SecondaryFixed" to (scheme.secondaryFixed to scheme.onSecondaryFixed),
      "SecondaryFixedDim" to (scheme.secondaryFixedDim to scheme.onSecondaryFixed),
      "SecondaryFixedVariant" to (scheme.secondaryFixed to scheme.onSecondaryFixedVariant),
      "TertiaryFixed" to (scheme.tertiaryFixed to scheme.onTertiaryFixed),
      "TertiaryFixedDim" to (scheme.tertiaryFixedDim to scheme.onTertiaryFixed),
      "TertiaryFixedVariant" to (scheme.tertiaryFixed to scheme.onTertiaryFixedVariant),
      // 描边、阴影与纯表面档位没有配对内容
      "outline" to (scheme.outline to null),
      "outlineVariant" to (scheme.outlineVariant to null),
      "surfaceTint" to (scheme.surfaceTint to null),
      "scrim" to (scheme.scrim to null),
      "contLowest" to (scheme.surfaceContainerLowest to null),
      "contLow" to (scheme.surfaceContainerLow to null),
      "cont" to (scheme.surfaceContainer to null),
      "contHigh" to (scheme.surfaceContainerHigh to null),
      "contHighest" to (scheme.surfaceContainerHighest to null),
      "dim" to (scheme.surfaceDim to null),
      "bright" to (scheme.surfaceBright to null),
    )

  Surface(color = scheme.surfaceContainerLowest) {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      pairs.toList().chunked(3).forEach { row ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          row.forEach { (name, colors) ->
            val (container, content) = colors
            if (content == null) {
              // 没有 on 色的角色：一个色块加名字，不占一整张卡
              Row(
                Modifier.weight(1f).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(
                  Modifier
                    .width(26.dp)
                    .height(26.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(container),
                )
                Text(
                  name,
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            } else {
              Box(
                Modifier
                  .weight(1f)
                  .clip(MaterialTheme.shapes.small)
                  .background(container)
                  .padding(8.dp),
              ) {
                Text(
                  name,
                  color = content,
                  style = MaterialTheme.typography.labelMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
          repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}
