package soko.ekibun.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import okio.Path
import okio.Path.Companion.toPath

/**
 * 扁平的字符串键值设置。
 *
 * 只收 `String`：键和值都要过 JS 边界（见 [soko.ekibun.acg.engine.JsEngine] 的 `settings.*`），
 * 多类型等于在两端各写一遍转换，而要存的东西（凭据、登录态）本来就是文本。
 *
 * [write] 的值收 `null` 即**删掉**该键 —— 不然「清除登录态」只能写成空串，
 * 读回来还得再判一次空。
 */
interface SettingsStore {
  suspend fun read(): Map<String, String>

  suspend fun write(values: Map<String, String?>)
}

/**
 * 进程级唯一的一份设置。
 *
 * 不能按需新建：DataStore **禁止同一个文件上并存两个实例**（各自缓存一份内存快照，
 * 互相覆盖），而它自己又拖着一个后台 scope，所以整进程共用一个。
 *
 * 测试别碰它 —— 它会写到开发机的家目录；测试走 [createSettingsStore] 指到临时路径。
 */
val settingsStore: SettingsStore by lazy { createSettingsStore(settingsFilePath().toPath()) }

/** 指定落盘文件的设置存储。生产路径见 [settingsStore]。 */
internal fun createSettingsStore(path: Path): SettingsStore =
  PreferencesSettingsStore(PreferenceDataStoreFactory.createWithPath(produceFile = { path }))

/** 设置文件的绝对路径。`.preferences_pb` 后缀是 DataStore 的硬要求。 */
internal expect fun settingsFilePath(): String

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
        // 用 `-=` 而不是 `remove(key)`：后者把被删的值 `as T` 回给调用方，泛型擦除后
        // 那次转换的空安全没有保证，而这里根本不看旧值。
        if (value == null) prefs -= key else prefs[key] = value
      }
    }
  }
}
