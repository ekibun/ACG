package soko.ekibun.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 扁平的字符串键值设置。
 *
 * 只收 `String`：键和值都要过 JS 边界（见 [soko.ekibun.acg.engine.JsEngine] 的 `settings.*`），
 * 多类型等于在两端各写一遍转换，而要存的东西（凭据、登录态）本来就是文本。
 *
 * 传 `null` 即**删掉**该键 —— 不然「清除登录态」只能写成空串，读回来还得再判一次空。
 */
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

/**
 * 进程级唯一的一份设置。
 *
 * 不能按需新建：DataStore **禁止同一个文件上并存两个实例**（各自缓存一份内存快照，
 * 互相覆盖），而它自己又拖着一个后台 scope，所以整进程共用一个。
 *
 * 落盘位置由 [Paths] 决定 —— 入口层必须在这份单例首次被读之前完成 `Paths.init`。
 */
val settingsStore: SettingsStore by lazy {
  createSettingsStore(Paths.dataDir + "/settings.preferences_pb")
}

/** 指定落盘文件的设置存储。生产路径见 [settingsStore]。 */
fun createSettingsStore(path: String): SettingsStore = PreferencesSettingsStore(createDataStore(path))

/** 平台侧建 DataStore 的原语。`.preferences_pb` 后缀是 DataStore 的硬要求，由调用方拼好路径。 */
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
        // 用 `-=` 而不是 `remove(key)`：后者把被删的值 `as T` 回给调用方，泛型擦除后
        // 那次转换的空安全没有保证，而这里根本不看旧值。
        if (value == null) prefs -= key else prefs[key] = value
      }
    }
  }
}
