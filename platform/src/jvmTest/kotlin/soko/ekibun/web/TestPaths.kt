package soko.ekibun.web

import soko.ekibun.common.Paths
import java.nio.file.Files

/** `:platform` jvmTest 的路径注入：落在临时区，只初始化一次。 */
internal object TestPaths {
  // 多 fork / 并行下只初始化一次
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
