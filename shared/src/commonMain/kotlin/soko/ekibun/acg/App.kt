package soko.ekibun.acg

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import soko.ekibun.acg.ui.comp.webview.WebviewTaskHost
import soko.ekibun.acg.ui.screen.CollectionScreen

// 应用入口，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Composable
@Preview
fun App() {
  MaterialTheme {
    Box(Modifier.fillMaxSize()) {
      CollectionScreen(Modifier.fillMaxSize())

      // 插件发起的 WebView 任务：缩成右下角胶囊，点开可切前台
      WebviewTaskHost()
    }
  }
}
