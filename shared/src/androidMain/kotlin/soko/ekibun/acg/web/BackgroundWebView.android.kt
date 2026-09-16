package soko.ekibun.acg.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Android 端的后台 WebView —— 直接照 `BangumiPlugin` 的 `BackgroundWebView` 来。
//
// Android 上不需要「宿主窗口」：`android.webkit.WebView` 本来就能脱离视图树独立工作，
// 所以这里是**命令式**建的那一个，而不是组合出来的。界面上不必画任何东西。
//
// `shouldInterceptRequest` 看得见**全部**请求（含图片/分片等子资源），桌面端的自研
// WebView2 宿主现在也是全量拦截 —— 两端契约一致，靠拦 `Range` 头拿真实媒体地址的
// 脚本在两边都原样可用。

/** 建 WebView 用的 Context，由 [BackgroundWebViewHost] 在组合里顺手记下来。 */
@Volatile
private var applicationContext: Context? = null

@Composable
actual fun BackgroundWebViewHost() {
  val context = LocalContext.current.applicationContext
  // 只留 applicationContext：整个进程活着它就在，不存在泄漏 Activity 的问题。
  LaunchedEffect(context) { applicationContext = context }
}

actual suspend fun loadBackgroundWebView(task: WebViewTask): WebViewTaskResult {
  val context =
    applicationContext
      ?: return WebViewTaskResult.Failed(
        "后台 WebView 缺少 Context：Android 端要先在 setContent 里调一次 " +
          "BackgroundWebViewHost()",
      )

  // WebView 只能在主线程建/用；这里全程停在主线程上 await 回调，
  // 而不是像 http.js 那样用 Thread.sleep 轮询（那会锁死主循环）。
  return withContext(Dispatchers.Main) {
    val result = CompletableDeferred<WebViewTaskResult>()
    val webView =
      try {
        BackgroundWebViewImpl(context)
      } catch (e: Throwable) {
        return@withContext WebViewTaskResult.Failed("后台 WebView 创建失败：$e")
      }

    webView.onIntercept = { request ->
      val callback = task.onInterceptRequest
      if (callback == null) {
        false
      } else {
        val hit =
          callback(
            WebViewRequest(
              url = request.url.toString(),
              headers = headersWithCookie(request),
              method = request.method,
              isForMainFrame = request.isForMainFrame,
              isRedirect = request.isRedirect,
            ),
          )
        if (hit == null) {
          false
        } else {
          result.complete(WebViewTaskResult.Intercepted(hit))
          true
        }
      }
    }

    webView.onPageFinished = {
      val script = task.script
      if (script == null) {
        // 对齐 http.js：只有 script 的返回值才算结果，没 script 就是没有结果。
        result.complete(WebViewTaskResult.Scripted(null))
      } else {
        webView.evaluateJavascript(script) { value ->
          result.complete(WebViewTaskResult.Scripted(value))
        }
      }
    }

    try {
      webView.loadUrl(task.url, task.effectiveHeaders())
      withTimeoutOrNull(BACKGROUND_WEBVIEW_TIMEOUT_MS) { result.await() }
        ?: WebViewTaskResult.Failed(
          "后台 WebView 超时（${BACKGROUND_WEBVIEW_TIMEOUT_MS}ms）：${task.url}",
        )
    } finally {
      // 用完即弃：不按 key 缓存实例，避免上一个页面的 cookie/JS 状态串到下一个人手里。
      runCatching {
        webView.onIntercept = null
        webView.onPageFinished = null
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        webView.destroy()
      }
    }
  }
}

/**
 * 把 cookie 折进请求头 —— 对齐 `http.js` 里 `makeRequest` 干的事。
 *
 * 后台页面的请求头本身不带 cookie，但脚本拿到 url 后要自己 `fetch` 一次，
 * 那次请求必须带上同一份会话，否则登录态是断的。
 */
private fun headersWithCookie(request: WebResourceRequest): Map<String, String> {
  val headers = request.requestHeaders?.toMap() ?: emptyMap()
  return runCatching {
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()
    val cookie = cookieManager.getCookie(request.url.host ?: "") ?: ""
    if (cookie.isEmpty()) headers else headers + ("cookie" to cookie)
  }.getOrDefault(headers)
}

@SuppressLint("SetJavaScriptEnabled")
private class BackgroundWebViewImpl(
  context: Context,
) : WebView(context) {
  var onPageFinished: ((String?) -> Unit)? = null

  /** 返回 true 表示已命中并收工，之后不再派发。 */
  var onIntercept: ((WebResourceRequest) -> Boolean)? = null

  init {
    settings.javaScriptEnabled = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.setSupportMultipleWindows(true)
    settings.domStorageEnabled = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    // 后台页只要 DOM/脚本，不要图片：省流量也省时间。
    settings.blockNetworkImage = true

    webViewClient =
      object : WebViewClient() {
        override fun onPageFinished(
          view: WebView?,
          url: String?,
        ) {
          onPageFinished?.invoke(url)
        }

        override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
        ): Boolean = !request.url.toString().startsWith("http")

        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): WebResourceResponse? {
          // 命中后立刻摘掉两个回调：这次请求照常放行，但结果已经定下来了，
          // 不让之后的 onPageFinished 覆盖它（与 http.js 一致）。
          if (onIntercept?.invoke(request) == true) {
            onIntercept = null
            onPageFinished = null
          }
          return super.shouldInterceptRequest(view, request)
        }
      }
  }
}
