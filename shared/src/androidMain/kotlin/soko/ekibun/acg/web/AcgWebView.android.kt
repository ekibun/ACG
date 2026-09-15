package soko.ekibun.acg.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android 端的可见 WebView。
 *
 * 直接包 `android.webkit.WebView`，不做无头化 —— 它就是真身。
 *
 * cookie 统一是「白送」的：Android 全进程只有一个系统级 `CookieManager`，
 * 可见页和 [BackgroundWebViewHost] 那边命令式建的后台 WebView 走的是同一份存储，
 * 不像桌面端那样必须刻意共用一个 user data folder。
 *
 * 与桌面端的行为差别（都是平台本性，不是实现取舍）：
 *
 * - **没有 [WebViewConfig.initScript] 的可靠注入点**：`onPageStarted` 里补一发
 *   `evaluateJavascript` 在「新文档已建立」和「页面脚本已跑」之间没有保证。
 *   真要文档级前置脚本，得引 `androidx.webkit` 的 `addDocumentStartJavaScript`。
 * - 开发者工具在 Android 上是 Chrome 远程调试（`chrome://inspect`），
 *   对应 `WebView.setWebContentsDebuggingEnabled`，没有独立的 devtools 窗口。
 */
@Composable
actual fun AcgWebView(
    state: AcgWebViewState,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    // 普通字段，不是 Compose 状态：controller 是给 UI 之外的调用方用的，
    // 写它不该触发重组（也免得在 factory 里写 snapshot state）。
    val holder = remember(state) { WebViewHolder() }

    DisposableEffect(state) {
        onDispose {
            if (state.controller is AndroidController) state.controller = null
            holder.dispose()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply { configure(state, ctx) }.also { view ->
                    holder.web = view
                    state.controller = AndroidController(view)
                }
            },
            modifier = Modifier.matchParentSize(),
        )
        // 原生 WebView 之上就是普通 Compose 层，直接叠着画（桌面端麻烦得多：那边原生
        // 窗口会盖住同级 Compose 内容，覆盖层必须塞进 content 插槽）。
        content()
    }
}

/** 拿得住那个 WebView，好在组合退出时销毁它。 */
private class WebViewHolder {
    var web: WebView? = null

    fun dispose() {
        val view = web ?: return
        web = null
        runCatching {
            view.stopLoading()
            view.clearHistory()
            view.destroy()
        }
    }
}

/** `android.webkit.WebView` 上的 [WebViewController]。 */
private class AndroidController(private val web: WebView) : WebViewController {

    override fun loadUrl(url: String, headers: Map<String, String>) {
        // WebView 的方法要在主线程调；调用方（工具条 / 脚本桥）本来就在主线程。
        web.loadUrl(url, headers)
    }

    override fun reload() = web.reload()

    override fun stop() = web.stopLoading()

    override fun goBack() {
        if (web.canGoBack()) web.goBack()
    }

    override fun goForward() {
        if (web.canGoForward()) web.goForward()
    }

    /** Android 的 devtools 是 Chrome 远程调试，没有「打开面板」这个动作。 */
    override fun openDevTools() = Unit
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(state: AcgWebViewState, context: Context) {
    val config = state.config

    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        // 页面自己弹新窗口会把这个视图顶掉；桌面端关掉时也是把 target=_blank 压回本视图。
        setSupportMultipleWindows(config.allowNewWindow)
        javaScriptCanOpenWindowsAutomatically = config.allowNewWindow
    }

    if (config.zoom != 1.0) {
        // 只是个初始缩放，页面自己的 viewport meta 仍然优先。
        setInitialScale((config.zoom * 100).toInt())
    }
    config.userAgent?.let { settings.userAgentString = it }

    WebView.setWebContentsDebuggingEnabled(config.enableDevtools)

    webViewClient = object : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url.isNullOrBlank()) return
            state.currentUrl = url
            state.isLoading = true
            state.loadingState = WebViewLoadingState.Loading(0.15f)
            // 见类注释：这里只是「尽量早」，不是文档级保证。
            config.initScript?.let { view?.evaluateJavascript(it, null) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (url.isNullOrBlank()) return
            state.currentUrl = url
            state.lastLoadedUrl = url
            state.isLoading = false
            state.loadingState = if (state.loadingState is WebViewLoadingState.Failed) {
                state.loadingState
            } else {
                WebViewLoadingState.Finished
            }
            state.syncHistory(view)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            url?.takeIf { it.isNotBlank() }?.let { state.currentUrl = it }
            state.syncHistory(view)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            // 只有主框架失败才算「这个页面没了」；子资源 404 不该把整页判死。
            if (request?.isForMainFrame != true) return
            state.fail(error?.description?.toString() ?: "加载失败")
        }
    }

    // 标题与进度只有 WebChromeClient 有（`WebViewClient` 上没有这两个回调）。
    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            title?.takeIf { it.isNotBlank() }?.let { state.pageTitle = it }
        }

        /** Android 给的是真进度（0..100），桌面 WebView2 只给布尔，那边靠插值。 */
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (state.loadingState is WebViewLoadingState.Failed) return
            state.loadingState = WebViewLoadingState.Loading((newProgress / 100f).coerceIn(0f, 1f))
        }
    }
}

private fun AcgWebViewState.syncHistory(web: WebView?) {
    web ?: return
    canGoBack = web.canGoBack()
    canGoForward = web.canGoForward()
}
