package soko.ekibun.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 桌面端的后台 WebView：headless 控制器的创建 + 预热宿主。
//
// 任务骨架（超时、终态收敛、销毁时机）在 commonMain 的 [loadBackgroundWebView]；
// 这里只负责把 native 的 token 管道（拦截回调 / nativeFinished / nativeFailed）
// 折成 [WebViewEvent]。走的是自研 C++ 宿主的**后台模式**：native 自己建一个隐藏
// 顶层窗口 + 自己的消息泵，不依赖任何 Compose 窗口，与 Android 端的契约完全对齐。
//
// 请求钩子接在引擎层的 `add_WebResourceRequested` +
// `AddWebResourceRequestedFilter(L"*", ALL)` 上，**含全部子资源**。
// 可见页（WebView）和这里共用**同一个 environment**（同一份 user data folder
// `NativeWebView.userDataDir`），所以 cookie / 登录态是同一套。

/**
 * 桌面端 headless（后台任务）控制器的创建。
 *
 * token 必须在 `run` 之前拿到并注册好 —— 页面可能在 `run` 返回之前就已经把请求打了
 * 过来（native 是按 token 派发结果的，不是按句柄）。`run` 失败（环境没就绪时 native
 * 直接返回 -1 且不回任何事件）在这里抛 [IllegalStateException]，由 common 骨架
 * 折成 [WebViewTaskResult.Failed]。
 */
internal suspend fun createDesktopHeadlessController(
  initialUrl: String,
  initialHeaders: Map<String, String>,
  script: String?,
  onRequest: ((WebViewRequest) -> WebViewInterception?)?,
  onEvent: (WebViewEvent) -> Unit,
): WebViewController {
  val token = NativeWebView.newToken()
  NativeWebView.registerBackground(
    token,
    object : NativeWebView.BackgroundSink {
      override fun onIntercept(
        url: String,
        method: String,
        isForMainFrame: Boolean,
        headers: Map<String, String>,
      ): Boolean {
        val callback = onRequest ?: return false
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
        onEvent(WebViewEvent.RequestIntercepted(hit))
        return true
      }

      override fun onFinished(json: String?) = onEvent(WebViewEvent.ScriptFinished(json))

      override fun onFailed(message: String) = onEvent(WebViewEvent.TaskFailed(message))
    },
  )

  val handle =
    withContext(Dispatchers.IO) {
      NativeWebView.run(initialUrl, initialHeaders, script, token)
    }
  if (handle <= 0L) {
    NativeWebView.unregisterBackground(token)
    throw IllegalStateException("后台 WebView 立不起来：$initialUrl —— WebView 环境不可用")
  }

  val delegate = DesktopWebViewController(handle, onEvent)
  return object : WebViewController by delegate {
    override fun dispose() {
      NativeWebView.unregisterBackground(token)
      // destroyView 与原来的 cancel 等价：从 liveViews 摘句柄 + postTask 销毁，幂等。
      delegate.dispose()
    }
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
