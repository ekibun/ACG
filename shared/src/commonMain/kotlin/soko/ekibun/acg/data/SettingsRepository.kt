package soko.ekibun.acg.data

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import soko.ekibun.common.settingsStore

/**
 * 应用设置的唯一入口：内存里一份 [AppSettings]，落盘到 `settingsStore`。
 *
 * 键是扁平字符串，缺键 / 值坏掉都回落默认值 —— 设置读坏不该让应用起不来。
 * 只写变化的键：`SettingsStore.write` 每次会重写整个 preferences 文件，少写一个是一个。
 */
object SettingsRepository {
  private const val KEY_SEED = "theme.seed"
  private const val KEY_DARK = "theme.darkMode"

  private val _settings = MutableStateFlow(AppSettings())
  val settings = _settings.asStateFlow()

  /** 从磁盘读一次，覆盖内存值。启动时由两端入口在 `setContent` 之前调用，避免首帧闪一下。 */
  suspend fun load() {
    _settings.value = decode(settingsStore.read())
  }

  /** 改一处设置：只落盘变化的键，内存值随后更新。 */
  suspend fun update(transform: (AppSettings) -> AppSettings) {
    val before = _settings.value
    val after = transform(before)
    if (after == before) return
    val old = encode(before)
    val changed = encode(after).filter { (key, value) -> old[key] != value }
    settingsStore.write(changed)
    _settings.value = after
  }

  private fun decode(values: Map<String, String>): AppSettings {
    val defaults = AppSettings()
    return defaults.copy(
      theme =
        defaults.theme.copy(
          seedColor = values[KEY_SEED]?.toColorOrNull() ?: defaults.theme.seedColor,
          darkMode =
            values[KEY_DARK]
              ?.let { name -> DarkMode.entries.find { it.name == name } }
              ?: defaults.theme.darkMode,
        ),
    )
  }

  private fun encode(settings: AppSettings): Map<String, String> =
    mapOf(
      KEY_SEED to colorKey(settings.theme.seedColor),
      KEY_DARK to settings.theme.darkMode.name,
    )

  private fun colorKey(color: Color): String {
    val value = color.value
    return value.toString()
  }

  private fun String.toColorOrNull(): Color? {
    val value = toULongOrNull() ?: return null
    return Color(value)
  }
}
