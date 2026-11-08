package soko.ekibun.acg.ui.theme

import androidx.compose.ui.graphics.Color

/** 默认种子色：Bangumi 品牌色。 */
val AppSeedColor: Color = Color(0xFFF09199)

/** 亮暗取向。纯黑是深色的一个变体，不是独立开关 —— 否则「亮色 + 纯黑底」这种组合是能表达出来的。 */
enum class DarkMode {
  Light,
  Dark,
  Black,
  Auto,
}

/**
 * 主题的可调项。持有与持久化都在调用方，主题本身不读也不写设置。
 *
 * [seedColor] 只贡献色相与彩度，明度不参与 —— 每个槽位该用哪一档 tone 由配色算法决定。
 */
data class AppThemeSettings(
  val seedColor: Color = AppSeedColor,
  val darkMode: DarkMode = DarkMode.Auto,
)
