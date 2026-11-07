package soko.ekibun.acg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AssumptionViolatedException
import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.common.Paths
import java.nio.file.Files

/** `:shared` jvmTest 的引导：`Paths` 落到临时区、宿主体检、不可用时记 skipped。 */
object TestEnv {
  @Volatile
  private var pathsInitialized = false

  /** 路径注入：落在临时区，只初始化一次（多 fork / 并行下也安全）。 */
  fun ensurePaths() {
    if (pathsInitialized) return
    synchronized(this) {
      if (pathsInitialized) return
      val root = Files.createTempDirectory("acg-test-paths")
      val data = root.resolve("data").toFile().apply { mkdirs() }
      val cache = root.resolve("cache").toFile().apply { mkdirs() }
      Paths.init(Paths(dataDir = data.path, cacheDir = cache.path))
      pathsInitialized = true
    }
  }

  /** 环境体检结果，`null` 表示健康。各用例共用一份（体检要真跑一次导航，别每条例用都跑）。 */
  val hostHealth: String? by lazy { webviewHostHealth() }

  /** 宿主不可用时把用例记成 skipped；`-Dacg.test.skipUnavailableHost=false` 可强行真跑。 */
  fun skipUnlessHostAvailable(reason: String? = hostHealth) {
    if (reason == null) return
    if (System.getProperty("acg.test.skipUnavailableHost", "true").toBoolean()) {
      throw AssumptionViolatedException(reason)
    }
    println("[host] skip disabled on request, running anyway: $reason")
  }
}

/** 宿主可用性体检：真跑一次 `about:blank` 导航并在页面里执行 `1 + 1`；null 表示健康。 */
private fun webviewHostHealth(): String? {
  // about:blank 不碰网络
  val result =
    runBlocking {
      withTimeoutOrNull(HOST_HEALTH_TIMEOUT_MS) {
        runCatching {
          JsEngine.instance.webviewAsync("about:blank", null, "1 + 1", null)
        }.getOrNull()
      }
    }
  return when {
    result == null ->
      "the host builds but the engine never responds: about:blank did not reach " +
        "NavigationCompleted within ${HOST_HEALTH_TIMEOUT_MS}ms. Most likely the WebView2 runtime on " +
        "this machine is broken (reinstalling the Evergreen Runtime usually fixes it)."
    result is String && result == "2" -> null
    else -> "host failure: the about:blank health check returned $result"
  }
}

/** 体检超时上界。 */
private const val HOST_HEALTH_TIMEOUT_MS = 40_000L
