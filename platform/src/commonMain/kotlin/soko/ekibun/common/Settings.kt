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

  /** 读单个键；没有该键就是 `null`。 */
  suspend fun get(key: String): String? = read()[key]

  /** 写单个键；[value] 为 `null` 即删键。 */
  suspend fun set(
    key: String,
    value: String?,
  ) = write(mapOf(key to value))
}

/** 进程内唯一的一份设置，落盘位置由 [Paths] 决定。 */
val settingsStore: SettingsStore by lazy {
  createSettingsStore(Paths.dataDir + "/settings.preferences_pb")
}

/** 指定落盘文件的设置存储。 */
fun createSettingsStore(path: String): SettingsStore = PreferencesSettingsStore(createDataStore(path))

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
        // 用 `-=` 而不是 `remove(key)`
        if (value == null) prefs -= key else prefs[key] = value
      }
    }
  }
}
