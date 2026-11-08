package soko.ekibun.acg.data

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
 * 主题的可调项。
 *
 * [seedColor] 只贡献色相与彩度，明度不参与 —— 每个槽位该用哪一档 tone 由配色算法决定。
 */
data class ThemeSettings(
  val seedColor: Color = AppSeedColor,
  val darkMode: DarkMode = DarkMode.Auto,
)

/**
 * 应用自己的设置。插件读写的键值走 `pluginSettingsStore`，单独一份文件。
 *
 * 站点特化（图片质量、bbcode 之类）不进这里 —— 那属于数据源自己的配置。
 */
data class AppSettings(
  val theme: ThemeSettings = ThemeSettings(),
)
