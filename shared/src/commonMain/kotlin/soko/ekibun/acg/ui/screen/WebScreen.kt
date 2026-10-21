package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soko.ekibun.web.WebView
import soko.ekibun.web.WebViewLoadingState
import soko.ekibun.web.WebViewState
import soko.ekibun.web.rememberWebViewState

/** 默认首页地址。 */
const val WEBVIEW_HOME_URL: String = "https://www.google.com"

/** 跨端 WebView 页面：进度条、前进 / 后退 / 刷新工具条，首屏加载 [homeUrl]。 */
@Composable
fun WebScreen(
  modifier: Modifier = Modifier,
  homeUrl: String = WEBVIEW_HOME_URL,
) {
  val state =
    rememberWebViewState(WEBVIEW_HOME_URL) {
      // 开启开发者工具（F12）
      enableDevtools = true
    }

  Surface(modifier = modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      WebToolbar(state = state)

      val loading = state.loadingState
      if (loading is WebViewLoadingState.Loading) {
        LinearProgressIndicator(
          progress = { loading.progress },
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Spacer(Modifier.height(4.dp))
      }

      Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
      ) {
        WebView(
          state = state,
          modifier = Modifier.fillMaxSize(),
        ) {
          // 后端不可用时的提示
          state.failure?.let { BackendUnavailableHint(homeUrl, it) }
        }
      }
    }
  }
}

@Composable
private fun WebToolbar(state: WebViewState) {
  // 工具栏命令都是挂起函数，点击回调自带协程上下文
  val scope = rememberCoroutineScope()

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    TextButton(
      enabled = state.canGoBack,
      onClick = { scope.launch { state.navigateBack() } },
    ) {
      Text("<")
    }
    TextButton(
      enabled = state.canGoForward,
      onClick = { scope.launch { state.navigateForward() } },
    ) {
      Text(">")
    }
    if (state.isLoading) {
      TextButton(onClick = { scope.launch { state.stopLoading() } }) {
        Text("stop")
      }
    } else {
      TextButton(onClick = { scope.launch { state.reload() } }) {
        Text("reload")
      }
    }

    Spacer(Modifier.width(6.dp))

    Text(
      text = state.pageTitle ?: state.lastLoadedUrl ?: "loading...",
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun BackendUnavailableHint(
  homeUrl: String,
  reason: String,
) {
  Box(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Card {
      Column(Modifier.padding(16.dp)) {
        Text(
          text = "没有可用的 WebView 后端",
          style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
          text = "页面 $homeUrl 没能加载：$reason",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}
