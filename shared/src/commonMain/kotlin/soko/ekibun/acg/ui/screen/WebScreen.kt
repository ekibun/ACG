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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.webview.web.LoadingState
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.WebViewState
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import kotlinx.coroutines.delay

/**
 * 固定站点页面的默认地址。换站点只改这一个常量（或从外部传 [WebScreen] 的 homeUrl）。
 */
const val WEBVIEW_HOME_URL: String = "https://www.bing.com"

/**
 * 跨端 WebView 页面：固定站点 + 顶部进度条 + 前进/后退/刷新工具条。
 *
 * - Android：[android.webkit.WebView]
 * - 桌面(JVM)：Nucleus Tao 窗口内的 WebView2 / WKWebView / WebKit2GTK
 * - iOS：WKWebView
 *
 * 桌面端必须跑在 Nucleus 的 Tao 窗口里（见 `desktopApp/main.kt`），
 * 否则 WebView 拿不到宿主 HWND，会静默降级成空白块。
 */
@Composable
fun WebScreen(
    modifier: Modifier = Modifier,
    homeUrl: String = WEBVIEW_HOME_URL,
) {
    val state = rememberWebViewState(homeUrl) {
        // 桌面端：白底不透明面，和普通浏览器一致。
        // 想反过来让 Compose 内容透出，就设 desktopWebSettings.transparent = true。
        backgroundColor = Color.White
        desktopWebSettings.transparent = false
        // F12 开发者工具，调试页面时很有用；发布可关掉。
        desktopWebSettings.enableDevtools = true
    }
    val navigator = rememberWebViewNavigator()

    // 桌面端没装 WebView2 运行时时，native 后端会静默降级成空壳：
    // 既不加载也不报错。用一个温和的超时探测把情况告诉用户。
    var backendUnavailable by remember { mutableStateOf(false) }
    LaunchedEffect(state, homeUrl) {
        delay(4_000)
        backendUnavailable = !state.isLoading && state.lastLoadedUrl.isNullOrBlank()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WebToolbar(
                state = state,
                navigator = navigator,
                homeUrl = homeUrl,
            )

            val loading = state.loadingState
            if (loading is LoadingState.Loading) {
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
                    navigator = navigator,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 桌面端的 native surface 会盖住同级 Compose 内容，
                    // 覆盖层必须放进 WebView 的 content 插槽才看得见。
                    if (backendUnavailable) {
                        BackendUnavailableHint(homeUrl)
                    }
                }
            }
        }
    }
}

@Composable
private fun WebToolbar(
    state: WebViewState,
    navigator: WebViewNavigator,
    homeUrl: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(
            enabled = navigator.canGoBack,
            onClick = { navigator.navigateBack() },
        ) {
            Text("<")
        }
        TextButton(onClick = { navigator.loadUrl(homeUrl) }) {
            Text("home")
        }
        if (state.isLoading) {
            TextButton(onClick = { navigator.stopLoading() }) {
                Text("stop")
            }
        } else {
            TextButton(onClick = { navigator.reload() }) {
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
private fun BackendUnavailableHint(homeUrl: String) {
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
                    text = "页面 $homeUrl 还没有加载出内容。Windows 需要系统装有 " +
                        "WebView2 运行时（Win11 自带；Win10 可能要先装 Evergreen Runtime）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
