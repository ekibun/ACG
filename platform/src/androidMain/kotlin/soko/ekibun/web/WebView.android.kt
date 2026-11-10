package soko.ekibun.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import soko.ekibun.hostApplicationContext

// Android 端的 WebView 平台层：上下文工厂（可见与后台共用一个类）与原生视图挂载面，
// 直接包 `android.webkit.WebView`。状态折算在 commonMain，这里只发 [WebViewEvent]。

/** Android 端的 [createWebviewContext] 实现，可见与后台共用一个类。 */
actual suspend fun createWebviewContext(isHeadless: Boolean): WebviewContext {
  // WebView 在主线程创建
  return withContext(Dispatchers.Main) {
    try {
      AndroidWebviewContext(WebView(hostApplicationContext), isHeadless)
    } catch (e: Throwable) {
      throw IllegalStateException("WebView creation failed: $e", e)
    }
  }
}

/** Android 端的可见视图挂载面：`AndroidView` 包原生 WebView，覆盖层叠在上面。 */
@Composable
internal actual fun WebViewSurface(
  controller: WebviewContext,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val impl = controller as AndroidWebviewContext
  Box(modifier) {
    AndroidView(
      factory = { impl.view },
      modifier = Modifier.matchParentSize(),
    )
    // 覆盖层直接叠在原生 WebView 上
    content()
  }
}

/**
 * [WebviewContext] 的 Android 实现，可见页与后台页共用一个类。
 * 命令一律切到主线程执行。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidWebviewContext(
  internal val view: WebView,
  internal val headless: Boolean,
) : WebviewContext {
  private var disposed = false

  override var onEvent: ((WebViewEvent) -> Unit)? = null

  override var onInterceptor:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)? = null

  override var onNavigate: ((url: String) -> Boolean)? = null

  init {
    view.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      useWideViewPort = true
      loadWithOverviewMode = true
      mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      cacheMode = WebSettings.LOAD_DEFAULT
      if (headless) {
        setSupportMultipleWindows(true)
        // 后台页不加载图片
        blockNetworkImage = true
      }
    }

    view.webViewClient =
      object : WebViewClient() {
        override fun onPageStarted(
          view: WebView?,
          url: String?,
          favicon: Bitmap?,
        ) {
          if (url.isNullOrBlank()) return
          onEvent?.invoke(WebViewEvent.UrlChanged(url))
          onEvent?.invoke(WebViewEvent.PageStarted)
        }

        override fun onPageFinished(
          view: WebView?,
          url: String?,
        ) {
          if (!url.isNullOrBlank()) onEvent?.invoke(WebViewEvent.DocumentLoaded(url))
          onEvent?.invoke(WebViewEvent.PageFinished)
          view?.let(::syncHistory)
        }

        override fun doUpdateVisitedHistory(
          view: WebView?,
          url: String?,
          isReload: Boolean,
        ) {
          url?.takeIf { it.isNotBlank() }?.let { onEvent?.invoke(WebViewEvent.UrlChanged(it)) }
          view?.let(::syncHistory)
        }

        override fun onReceivedError(
          view: WebView?,
          request: WebResourceRequest?,
          error: WebResourceError?,
        ) {
          // 只有主框架失败才算加载失败
          if (request?.isForMainFrame != true) return
          onEvent?.invoke(WebViewEvent.LoadFailed(error?.description?.toString() ?: "加载失败"))
        }

        /** 后台页拦截非 http 跳转；主框架导航交给宿主决定。 */
        override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
        ): Boolean {
          val url = request.url.toString()
          if (headless && !url.startsWith("http")) return true
          if (!request.isForMainFrame) return false
          return try {
            onNavigate?.invoke(url) == true
          } catch (e: Throwable) {
            e.printStackTrace()
            false
          }
        }

        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): WebResourceResponse? {
          val callback = onInterceptor
          if (callback != null) {
            // 把请求交给拦截回调，本处照常放行
            try {
              callback(
                request.url.toString(),
                headersWithCookie(request),
                request.method,
                request.isForMainFrame,
              )
            } catch (e: Throwable) {
              e.printStackTrace()
            }
          }
          return super.shouldInterceptRequest(view, request)
        }
      }

    // 标题与进度来自 WebChromeClient
    view.webChromeClient =
      object : WebChromeClient() {
        override fun onReceivedTitle(
          view: WebView?,
          title: String?,
        ) {
          title?.takeIf { it.isNotBlank() }?.let { onEvent?.invoke(WebViewEvent.TitleChanged(it)) }
        }

        /** 进度（0..100）折算成 0..1。 */
        override fun onProgressChanged(
          view: WebView?,
          newProgress: Int,
        ) {
          onEvent?.invoke(WebViewEvent.Progress(newProgress / 100f))
        }
      }
  }

  override suspend fun setUserAgent(ua: String) = withContext(Dispatchers.Main) { view.settings.userAgentString = ua }

  override suspend fun setDevtoolsEnabled(enabled: Boolean) =
    withContext(Dispatchers.Main) { WebView.setWebContentsDebuggingEnabled(enabled) }

  // 后台态关掉图片加载：后台页多半只是跑脚本取数，图白下
  override suspend fun setVisible(visible: Boolean) =
    withContext(Dispatchers.Main) { view.settings.blockNetworkImage = !visible }

  override suspend fun navigate(
    url: String,
    headers: Map<String, String>,
  ) = withContext(Dispatchers.Main) { view.loadUrl(url, headers) }

  /** 在当前页面执行脚本，等结果（JSON 字符串）。 */
  override suspend fun evaluate(script: String): String? {
    val result = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) { view.evaluateJavascript(script) { result.complete(it) } }
    return result.await()
  }

  override suspend fun reload() = withContext(Dispatchers.Main) { view.reload() }

  override suspend fun stop() = withContext(Dispatchers.Main) { view.stopLoading() }

  override suspend fun goBack() =
    withContext(Dispatchers.Main) {
      if (view.canGoBack()) view.goBack()
    }

  override suspend fun goForward() =
    withContext(Dispatchers.Main) {
      if (view.canGoForward()) view.goForward()
    }

  /** Android 的 devtools 走 Chrome 远程调试，这里无操作。 */
  override suspend fun openDevTools() = Unit

  /** 释放 WebView；非挂起，销毁投到主线程。重复调用无副作用。 */
  override fun close() {
    if (!disposed) {
      disposed = true
      Handler(Looper.getMainLooper()).post {
        runCatching {
          view.stopLoading()
          view.clearHistory()
          // 后台页顺带清缓存
          if (headless) view.clearCache(true)
          view.destroy()
        }
      }
    }
  }

  private fun syncHistory(web: WebView) {
    onEvent?.invoke(WebViewEvent.HistoryChanged(canGoBack = web.canGoBack(), canGoForward = web.canGoForward()))
  }
}

/** 把 cookie 折进请求头。 */
internal fun headersWithCookie(request: WebResourceRequest): Map<String, String> {
  val headers = request.requestHeaders?.toMap() ?: emptyMap()
  return runCatching {
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()
    val cookie = cookieManager.getCookie(request.url.host ?: "") ?: ""
    if (cookie.isEmpty()) headers else headers + ("cookie" to cookie)
  }.getOrDefault(headers)
}
