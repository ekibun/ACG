package soko.ekibun.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AssumptionViolatedException
import soko.ekibun.web.WebViewEvent
import soko.ekibun.web.Webview2Context
import java.nio.file.Files

/** `:platform` jvmTest 的引导：`Paths` 落到临时区、宿主体检、不可用时记 skipped。 */
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

  /** 环境体检结果，`null` 表示健康。各用例共用一份（体检要真起一次宿主，别每条例用都跑）。 */
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

/**
 * 宿主可用性体检：直构 [Webview2Context]、导航 `about:blank`、执行 `1 + 1`；null 表示健康。
 */
private fun webviewHostHealth(): String? =
  runBlocking {
    // 伴生初始化要从 Paths 取 user data folder，先补注入
    TestEnv.ensurePaths()
    val ctx =
      runCatching { Webview2Context() }.getOrElse { return@runBlocking "host failed to start: ${it.message ?: it}" }
    try {
      val loaded = CompletableDeferred<Unit>()
      val json =
        withTimeoutOrNull(HEALTH_TIMEOUT_MS) {
          ctx.onEvent = { event -> if (event is WebViewEvent.PageFinished) loaded.complete(Unit) }
          // 没 attach 的上下文没有父窗口客户区，尺寸得自己给
          ctx.setBounds(800, 600)
          ctx.navigate("about:blank")
          loaded.await()
          ctx.evaluate("1 + 1")
        }
      when {
        json == null ->
          "the host builds but the engine never responds: about:blank did not reach " +
            "PageFinished within ${HEALTH_TIMEOUT_MS}ms. Most likely the WebView2 runtime on this " +
            "machine is broken (reinstalling the Evergreen Runtime usually fixes it)."
        json == "2" -> null
        else -> "host failure: the about:blank health check returned $json"
      }
    } finally {
      // dispose() 非挂起：底层 Pointer 自己把销毁派发到归属线程
      runCatching { ctx.close() }
    }
  }

/** 体检超时上界。 */
private const val HEALTH_TIMEOUT_MS = 40_000L
