package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 宿主可用性体检：直构 [Webview2Context]、导航 `about:blank`、执行 `1 + 1`；null 表示健康。 */
internal fun webviewHostHealth(): String? =
  runBlocking {
    // 伴生初始化要从 Paths 取 user data folder，先补注入
    TestPaths.ensureInitialized()
    val ctx =
      runCatching { Webview2Context() }.getOrElse { return@runBlocking "host failed to start: ${it.message ?: it}" }
    try {
      val loaded = CompletableDeferred<Unit>()
      val json =
        withTimeoutOrNull(HOST_HEALTH_TIMEOUT_MS) {
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
            "PageFinished within ${HOST_HEALTH_TIMEOUT_MS}ms. Most likely the WebView2 runtime on this " +
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
private const val HOST_HEALTH_TIMEOUT_MS = 40_000L
