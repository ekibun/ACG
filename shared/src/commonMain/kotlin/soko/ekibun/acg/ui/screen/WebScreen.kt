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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import soko.ekibun.acg.web.AcgWebView
import soko.ekibun.acg.web.AcgWebViewState
import soko.ekibun.acg.web.WebViewLoadingState
import soko.ekibun.acg.web.rememberAcgWebViewState

/**
 * 固定站点页面的默认地址。换站点只改这一个常量（或从外部传 [WebScreen] 的 homeUrl）。
 */
const val WEBVIEW_HOME_URL: String = "https://www.bing.com"

/**
 * 跨端 WebView 页面：固定站点 + 顶部进度条 + 前进/后退/刷新工具条。
 *
 * - Android：[android.webkit.WebView]
 * - 桌面(Windows)：自研 C++ 宿主里的 WebView2（见 `soko.ekibun.acg.web`）
 *
 * 桌面端必须跑在**基于 AWT 的窗口**里（compose.desktop 的 `application { Window(...) }`，
 * 见 `desktopApp/main.kt`）：原生视图是个真的 Win32 窗口，靠 JAWT 从 AWT 组件（`SwingPanel`
 * 里的 Canvas）取 HWND 再 `SetParent` 挂进去 —— 没有 AWT 后端就没东西可挂。
 *
 * 顺带一提，这里的 WebView 和插件脚本的后台 WebView 共用同一套 cookie
 * （桌面共用一个 user data folder，Android 共用系统 `CookieManager`），
 * 所以在这里登录过的站点，脚本那边也是登录态。
 */
@Composable
fun WebScreen(
    modifier: Modifier = Modifier,
    homeUrl: String = WEBVIEW_HOME_URL,
) {
    val state = rememberAcgWebViewState(homeUrl) {
        // F12 开发者工具，调试页面时很有用；发布可关掉。
        enableDevtools = true
        // 页面自己弹新窗口会另开一个原生窗口，这个页面就不受控了。
        allowNewWindow = false
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WebToolbar(state = state, homeUrl = homeUrl)

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
                AcgWebView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 桌面端这个覆盖层会被原生 WebView 窗口盖住（AWT 是重型组件），
                    // 实际只在原生后端**起不来**时才看得见；Android 上一直有效。
                    state.failure?.let { BackendUnavailableHint(homeUrl, it) }
                }
            }
        }
    }
}

@Composable
private fun WebToolbar(
    state: AcgWebViewState,
    homeUrl: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(
            enabled = state.canGoBack,
            onClick = { state.navigateBack() },
        ) {
            Text("<")
        }
        TextButton(
            enabled = state.canGoForward,
            onClick = { state.navigateForward() },
        ) {
            Text(">")
        }
        TextButton(onClick = { state.loadUrl(homeUrl) }) {
            Text("home")
        }
        if (state.isLoading) {
            TextButton(onClick = { state.stopLoading() }) {
                Text("stop")
            }
        } else {
            TextButton(onClick = { state.reload() }) {
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
private fun BackendUnavailableHint(homeUrl: String, reason: String) {
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
