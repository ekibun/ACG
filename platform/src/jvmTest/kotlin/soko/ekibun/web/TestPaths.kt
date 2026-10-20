package soko.ekibun.web

import soko.ekibun.common.Paths
import java.nio.file.Files

/**
 * `:platform` jvmTest 的路径注入：`settingsStore` 与 [Webview2Context] 都从 [Paths] 取路径。
 * 落在临时区，只注入一次（`Paths.init` 只能成功一次）。
 */
internal object TestPaths {
  // 与 `:shared` 那份同形：多 fork / 并行时必须有可见性 + 互斥，否则 `Paths.init` 会被调第二次
  // （它只允许成功一次，第二次直接抛）。
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
