package soko.ekibun.acg

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import soko.ekibun.acg.ui.comp.webview.WebviewTaskHost
import soko.ekibun.acg.ui.screen.CollectionScreen
import soko.ekibun.acg.ui.theme.AppTheme

// 应用入口，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Composable
@Preview
fun App() {
  AppTheme {
    // Surface 铺满整窗，让系统栏背后的底色跟着主题走；不加它那两条会是窗口默认白底。
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      // 避开状态栏与导航栏。桌面端 systemBars 恒为 0，同一份代码两端通用。
      // 只让内容退让，不等于内缩 drawable；播放页要全屏出血时反着用 consumeWindowInsets。
      Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        CollectionScreen(Modifier.fillMaxSize())

        // 插件发起的 WebView 任务：缩成右下角胶囊，点开可切前台
        WebviewTaskHost()
      }
    }
  }
}
