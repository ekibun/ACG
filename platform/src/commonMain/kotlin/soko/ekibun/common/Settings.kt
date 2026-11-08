package soko.ekibun.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** 扁平的字符串键值设置；传 `null` 即删除该键。 */
interface SettingsStore {
  suspend fun read(): Map<String, String>

  suspend fun write(values: Map<String, String?>)

  suspend fun get(key: String): String? = read()[key]

  suspend fun set(
    key: String,
    value: String?,
  ) = write(mapOf(key to value))
}

/** App 自己的设置。 */
val settingsStore: SettingsStore by lazy {
  createSettingsStore(Paths.dataDir + "/settings.preferences_pb")
}

fun createSettingsStore(path: String): SettingsStore = PreferencesSettingsStore(createDataStore(path))

/** 插件写的设置：所有插件共用这一份，与 [settingsStore] 分开。 */
val pluginSettingsStore: SettingsStore by lazy {
  createSettingsStore(Paths.dataDir + "/plugins.preferences_pb")
}

/** 平台侧建 DataStore 的原语，[path] 需带 `.preferences_pb` 后缀。 */
expect fun createDataStore(path: String): DataStore<Preferences>

private class PreferencesSettingsStore(
  private val preferences: DataStore<Preferences>,
) : SettingsStore {
  override suspend fun read(): Map<String, String> =
    preferences.data
      .first()
      .asMap()
      .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }
      .toMap()

  override suspend fun write(values: Map<String, String?>) {
    preferences.edit { prefs ->
      for ((name, value) in values) {
        val key = stringPreferencesKey(name)
        if (value == null) prefs -= key else prefs[key] = value
      }
    }
  }
}
