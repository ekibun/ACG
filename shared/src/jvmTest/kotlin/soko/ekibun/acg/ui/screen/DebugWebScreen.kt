package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import soko.ekibun.acg.ui.comp.webview.WebViewPane
import soko.ekibun.acg.ui.debugApplication
import soko.ekibun.web.WebView
import soko.ekibun.web.WebViewLoadingState
import soko.ekibun.web.rememberWebViewState

/** 默认首页地址。 */
const val WEBVIEW_HOME_URL: String = "https://www.google.com"

/** 「浏览器」验证窗口：带历史与刷新的完整 WebView，首屏加载 [homeUrl]。 */
@Composable
fun DebugWebScreen(
  modifier: Modifier = Modifier,
  homeUrl: String = WEBVIEW_HOME_URL,
) {
  val state =
    rememberWebViewState(homeUrl) {
      // 开启开发者工具（F12）
      enableDevtools = true
    }
  val scope = rememberCoroutineScope()
  val loading = state.loadingState

  WebViewPane(
    title = state.pageTitle ?: state.lastLoadedUrl ?: homeUrl,
    progress = if (loading is WebViewLoadingState.Loading) loading.progress else 1f,
    modifier = modifier,
    canGoBack = state.canGoBack,
    canGoForward = state.canGoForward,
    onBack = { scope.launch { state.navigateBack() } },
    onForward = { scope.launch { state.navigateForward() } },
    onReload = { scope.launch { state.reload() } },
    onStop = { scope.launch { state.stopLoading() } },
    failure = state.failure,
  ) {
    WebView(state = state, modifier = Modifier.fillMaxSize())
  }
}

// 手动验证入口：真窗口下 WebView2 才挂得上子窗口。
fun main() {
  debugApplication("浏览器") { DebugWebScreen(Modifier.fillMaxSize()) }
}
