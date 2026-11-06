package soko.ekibun.acg.ui.comp.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 一块完整的 WebView 区域：工具栏 + 进度 + 承载面，后端起不来时盖一层提示。
 *
 * 承载面由调用方以 [content] 传入：「浏览器」验证窗口自己建控制器，插件的任务由引擎给。
 */
@Composable
fun WebViewPane(
  title: String,
  progress: Float,
  modifier: Modifier = Modifier,
  history: Boolean = true,
  canGoBack: Boolean = false,
  canGoForward: Boolean = false,
  onBack: () -> Unit = {},
  onForward: () -> Unit = {},
  onReload: () -> Unit = {},
  onStop: (() -> Unit)? = null,
  /** 非空表示后端起不来，[content] 之上盖一层提示，内容就是原因。 */
  failure: String? = null,
  trailing: @Composable RowScope.() -> Unit = {},
  content: @Composable () -> Unit,
) {
  Surface(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      WebToolbar(
        title = title,
        progress = progress,
        history = history,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        onBack = onBack,
        onForward = onForward,
        onReload = onReload,
        onStop = onStop,
        trailing = trailing,
      )

      Box(Modifier.fillMaxWidth().weight(1f)) {
        content()
        if (failure != null) BackendUnavailableHint(page = title, reason = failure)
      }
    }
  }
}

@Composable
private fun BackendUnavailableHint(
  page: String,
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
          text = "页面 $page 没能加载：$reason",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}
