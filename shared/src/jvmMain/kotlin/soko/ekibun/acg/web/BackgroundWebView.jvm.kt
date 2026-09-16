package soko.ekibun.acg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桌面端的后台 WebView。
 *
 * 走自研 C++ 宿主（`cxx/webview/webview.cpp`）的**后台模式**：native 自己建一个
 * 隐藏顶层窗口 + 自己的消息泵，不依赖任何 Compose 窗口，所以这里再没有「宿主没挂上」
 * 这种失败态，和 Android 端的契约完全对齐。
 *
 * 请求钩子接在引擎层的 `add_WebResourceRequested` +
 * `AddWebResourceRequestedFilter(L"*", ALL)` 上，**含全部子资源**。这正是换掉预编译
 * 那个库的直接原因（详见 `BackgroundWebView.kt` 的文件头）。
 *
 * 可见页（[AcgWebView]）和这里共用**同一个 environment**（同一份 user data folder
 * `NativeWebView.userDataDir`），所以 cookie / 登录态是同一套。
 */

actual suspend fun loadBackgroundWebView(task: WebViewTask): WebViewTaskResult {
  val started = withContext(Dispatchers.IO) { NativeWebView.ensureStarted() }
  started.exceptionOrNull()?.let { cause ->
    return WebViewTaskResult.Failed(
      "后台 WebView 起不来：${cause.message ?: cause}",
    )
  }

  // token 必须在 `run` 之前拿到并注册好 —— 页面可能在 `run` 返回之前就已经
  // 把请求打过来了（native 是按 token 派发结果的，不是按句柄）。
  val token = NativeWebView.newToken()
  val outcome = CompletableDeferred<WebViewTaskResult>()

  val sink =
    object : NativeWebView.BackgroundSink {
      override fun onIntercept(
        url: String,
        method: String,
        isForMainFrame: Boolean,
        headers: Map<String, String>,
      ): Boolean {
        val callback = task.onInterceptRequest ?: return false
        // 见 WebViewRequest 的说明：桌面端的 isRedirect 拿不到，恒为 false。
        val hit =
          callback(
            WebViewRequest(
              url = url,
              headers = headers,
              method = method,
              isForMainFrame = isForMainFrame,
              isRedirect = false,
            ),
          ) ?: return false
        // 返回 true = 命中：native 会给一个空 204 并把加载停掉；结果只落这一次。
        outcome.complete(WebViewTaskResult.Intercepted(hit))
        return true
      }

      override fun onFinished(json: String?) {
        outcome.complete(WebViewTaskResult.Scripted(json))
      }

      override fun onFailed(message: String) {
        outcome.complete(WebViewTaskResult.Failed("后台 WebView 失败：$message"))
      }
    }
  NativeWebView.registerBackground(token, sink)

  var handle = 0L
  try {
    handle =
      withContext(Dispatchers.IO) {
        NativeWebView.run(task.url, task.effectiveHeaders(), task.script, token)
      }
    if (handle <= 0L) {
      // native 在环境还没就绪时会直接返回 -1 且不回任何事件（没视图可挂），
      // 所以这里不能干等 outcome。
      return WebViewTaskResult.Failed(
        "后台 WebView 立不起来：${task.url} —— WebView 环境不可用",
      )
    }
    return withTimeoutOrNull(BACKGROUND_WEBVIEW_TIMEOUT_MS) { outcome.await() }
      ?: WebViewTaskResult.Failed(
        "后台 WebView 超时（${BACKGROUND_WEBVIEW_TIMEOUT_MS}ms）：${task.url}",
      )
  } finally {
    NativeWebView.unregisterBackground(token)
    // 命中拦截时 JVM 这边已经拿到结果、native 那边只是把加载停了，
    // 这个隐藏窗口得由我们收掉。
    NativeWebView.cancel(handle)
  }
}

/**
 * 桌面端不再需要宿主窗口，这个组合点留着只做**预热**：提前把 WebView 线程和环境
 * 起起来，第一次 `webview(...)` 调用就不用等环境初始化。
 *
 * 失败不在这里报 —— 真用到时 [loadBackgroundWebView] 会把原因带出去。
 */
@Composable
actual fun BackgroundWebViewHost() {
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) { NativeWebView.ensureStarted() }
  }
}
