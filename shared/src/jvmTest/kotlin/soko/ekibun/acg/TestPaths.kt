package soko.ekibun.acg

import soko.ekibun.common.Paths
import java.nio.file.Files

/**
 * `:shared` jvmTest 的路径注入：`settingsStore` 与后台 WebView 都从 [Paths] 取路径。
 * 落在临时区，只注入一次（`Paths.init` 只能成功一次）。
 */
object TestPaths {
  @Volatile
  private var initialized = false

  fun ensureInitialized() {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      val root = Files.createTempDirectory("acg-test-paths")
      val data = root.resolve("data").toFile().apply { mkdirs() }
      val cache = root.resolve("cache").toFile().apply { mkdirs() }
      Paths.init(Paths(dataDir = data.path, cacheDir = cache.path))
      initialized = true
    }
  }
}
