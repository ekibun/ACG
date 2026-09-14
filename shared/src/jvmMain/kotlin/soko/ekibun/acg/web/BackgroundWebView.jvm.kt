package soko.ekibun.acg.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.webview.request.RequestInterceptor
import dev.nucleusframework.webview.request.WebRequest
import dev.nucleusframework.webview.request.WebRequestInterceptResult
import dev.nucleusframework.webview.web.LoadingState
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 桌面端的后台 WebView 宿主。
 *
 * 这里没有「无头 WebView」可用：库的 Windows 后端拿 `LocalTaoWindow.current.nativeHandle`
 * 当 WebView2 的父 HWND，没有真实窗口就干脆不建（`WebView2WindowsBridge.isLoaded &&
 * parentHwnd != 0L` 不成立 → 掉进 `NativeWebView()` 空实现，**不报错**）。
 * 所以调用方必须把 [BackgroundWebViewHost] 挂在一个**不显示**的 Nucleus 窗口里，
 * 见 `desktopApp/main.kt`。
 *
 * 任务通过 [Channel] 投递，而不是直接把待办塞进 Compose 状态：调用方在
 * `Dispatchers.IO` 上，往状态列表里写会和组合线程抢 snapshot。
 */

/** 一次待执行/执行中的后台任务。用身份相等（不实现 equals）当 Compose key。 */
private class BackgroundJob(val task: WebViewTask) {
    val result = CompletableDeferred<WebViewTaskResult>()

    /** 结果只能落一次：拦截命中与「超时/脚本返回」可能同时到达。 */
    fun complete(value: WebViewTaskResult) {
        result.complete(value)
    }
}

private val jobs = Channel<BackgroundJob>(Channel.UNLIMITED)

/**
 * 宿主是否已挂上。没挂上时 [loadBackgroundWebView] 直接抛错，
 * 而不是让调用方在一个永远不会有人消费的 Channel 上等满超时。
 */
@Volatile
private var hostAttached = false

actual suspend fun loadBackgroundWebView(task: WebViewTask): WebViewTaskResult {
    check(hostAttached) {
        "后台 WebView 宿主没有挂上：桌面端要在 nucleusApplication 里开一个 " +
            "visible = false 的 DecoratedWindow 包住 BackgroundWebViewHost()"
    }
    val job = BackgroundJob(task)
    jobs.send(job)
    return job.result.await()
}

@Composable
actual fun BackgroundWebViewHost() {
    val sessions = remember { mutableStateListOf<BackgroundJob>() }

    DisposableEffect(Unit) {
        hostAttached = true
        onDispose { hostAttached = false }
    }

    // 在组合线程上收 Channel → 写状态列表，调用方线程不碰 Compose 状态。
    LaunchedEffect(Unit) {
        for (job in jobs) sessions.add(job)
    }

    Column(Modifier.fillMaxSize()) {
        sessions.forEach { job ->
            // key 用对象身份：任务结束就整体移除，WebView 随组合一起销毁。
            key(job) {
                BackgroundSession(job) { sessions.remove(job) }
            }
        }
    }
}

@Composable
private fun BackgroundSession(job: BackgroundJob, onDone: () -> Unit) {
    val task = job.task
    val headers = remember(task) { task.effectiveHeaders() }

    val state = rememberWebViewState(task.url, headers) {
        // 后台页不透明：透明会让 DComp 层走另一条混合路径，没必要。
        backgroundColor = Color.White
        desktopWebSettings.transparent = false
        task.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.let { customUserAgentString = it.value }
    }

    val interceptor = remember(task) {
        object : RequestInterceptor {
            override fun onInterceptUrlRequest(
                request: WebRequest,
                navigator: WebViewNavigator,
            ): WebRequestInterceptResult {
                val callback = task.onInterceptRequest
                    ?: return WebRequestInterceptResult.Allow
                val hit = callback(
                    WebViewRequest(
                        url = request.url,
                        headers = request.headers,
                        method = request.method,
                        isForMainFrame = request.isForMainFrame,
                        isRedirect = request.isRedirect,
                    )
                ) ?: return WebRequestInterceptResult.Allow
                // 命中：记下结果并拒掉这次导航 —— Reject 即中止加载，等价于
                // http.js 里把 webview 的两个回调摘掉。
                job.complete(WebViewTaskResult.Intercepted(hit))
                return WebRequestInterceptResult.Reject
            }
        }
    }
    // 位置参数调用：库把 requestInterceptor 放在 CoroutineScope 之后。
    val navigator = rememberWebViewNavigator(rememberCoroutineScope(), interceptor)

    WebView(
        state = state,
        modifier = Modifier.fillMaxSize(),
        navigator = navigator,
    )

    LaunchedEffect(job) {
        try {
            val loaded = withTimeoutOrNull(BACKGROUND_WEBVIEW_TIMEOUT_MS) {
                // 1) 等 native WebView 真的挂到 state 上（库在 LaunchedEffect 里做）。
                snapshotFlow { state.webView }.filterNotNull().first()
                // 2) 等真实的文档。桌面端的 loadingState 是**轮询**出来的：
                //    新建时 isLoading 就是 false，而且没有可用后端时会被立刻置成
                //    Finished —— 只等 Finished 会在「空壳」上立刻通过，所以必须
                //    用 lastLoadedUrl 非空来确认页面真的来过。
                snapshotFlow { state.loadingState to state.lastLoadedUrl }
                    .first { (loading, url) ->
                        loading is LoadingState.Finished && !url.isNullOrBlank()
                    }
                true
            }

            // 拦截可能已经先落子了，别再覆盖。
            if (job.result.isCompleted) return@LaunchedEffect

            if (loaded == null) {
                val why = if (state.lastLoadedUrl.isNullOrBlank()) {
                    "始终没有加载出内容（Windows 需要 WebView2 运行时；" +
                        "也可能是宿主窗口没挂上 WebView）"
                } else {
                    "等待页面加载完成超时"
                }
                job.complete(WebViewTaskResult.Failed("后台 WebView 失败：${task.url} —— $why"))
                return@LaunchedEffect
            }

            val script = task.script
            if (script == null) {
                // 只做了加载、没有取值的脚本：和 http.js 一样，结果就是「没有结果」。
                job.complete(WebViewTaskResult.Scripted(null))
                return@LaunchedEffect
            }
            val ret = suspendCancellableCoroutine { cont ->
                val webView = state.webView
                if (webView == null) {
                    cont.resume(null)
                } else {
                    webView.evaluateJavaScript(script) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                }
            }
            job.complete(WebViewTaskResult.Scripted(ret))
        } catch (e: Throwable) {
            job.complete(WebViewTaskResult.Failed("后台 WebView 异常：${e}"))
        } finally {
            // 从列表里摘掉自己 → 组合销毁 → WebView 释放。
            onDone()
        }
    }
}
