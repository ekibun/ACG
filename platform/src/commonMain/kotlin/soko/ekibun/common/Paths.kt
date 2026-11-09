package soko.ekibun.common

/**
 * 进程级数据目录，由入口层在使用前 [init] 一次。
 * 未初始化就读取会抛 [UninitializedPropertyAccessException]。
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
    val dataDir: String get() = instance?.dataDir ?: error("Paths.init() not called")

    /** 同 [Paths.cacheDir]。 */
    val cacheDir: String get() = instance?.cacheDir ?: error("Paths.init() not called")

    /** 进程内只认第一份值：Android 的 Activity 重建会再调一次，值相同就放行，不同当场抛。 */
    fun init(paths: Paths) {
      val current = instance
      if (current == null) {
        instance = paths
        return
      }
      check(current == paths) { "Paths.init() got two different roots: $current vs $paths" }
    }
  }
}
