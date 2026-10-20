package soko.ekibun.common

/**
 * 进程级数据目录。入口层（`desktopApp/main.kt`、`MainActivity.onCreate`）在首次使用前
 * [init] 一次；未初始化就读取会抛 [UninitializedPropertyAccessException]。
 *
 * 应用名与系统目录规范由入口层决定（Windows 的 `%APPDATA%`、Android 的 `filesDir`），
 * 平台层只消费，不自己拼路径。
 */
data class Paths(
  /** 用户数据根：设置文件与 WebView2 的 user data folder 都在它下面。 */
  val dataDir: String,
  /** 可清缓存的根。 */
  val cacheDir: String,
) {
  companion object {
    private var instance: Paths? = null

    /** 同 [Paths.dataDir]。 */
    val dataDir: String get() = instance?.dataDir ?: error("Paths.init() 还没调用")

    /** 同 [Paths.cacheDir]。 */
    val cacheDir: String get() = instance?.cacheDir ?: error("Paths.init() 还没调用")

    /** 只能成功一次：重复调用时 DataStore 与 WebView2 已按第一份打开。 */
    fun init(paths: Paths) {
      check(instance == null) { "Paths.init() must be called exactly once" }
      instance = paths
    }
  }
}
