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

// Android 端的 WebView 平台层：上下文工厂（可见 + 后台共用一个类，按模式分配置）+
// 原生视图挂载面。直接包 `android.webkit.WebView`，不做无头化 —— 它就是真身。
//
// cookie 统一是「白送」的：Android 全进程只有一个系统级 `CookieManager`，
// 可见页和后台 WebView 走的是同一份存储，不像桌面端那样必须刻意共用一个
// user data folder。
//
// 建 `android.webkit.WebView` 要的 Context 由宿主注入一次，见 [hostApplicationContext]。
//
// 与桌面端的行为差别（都是平台本性，不是实现取舍）：
//
// - **两端都不提供文档级前置脚本注入**（2026-09-15 统一）：桌面端本可以靠
//   `AddScriptToExecuteOnDocumentCreated` 做到，但配置项 `initScript` 已删掉；
//   Android 上 `onPageStarted` 里补一发 `evaluateJavascript` 在「新文档已建立」
//   和「页面脚本已跑」之间**没有保证**，真要做文档级前置脚本得引 `androidx.webkit`
//   的 `addDocumentStartJavaScript`。为了两端行为一致，索性都不做。
// - 开发者工具在 Android 上是 Chrome 远程调试（`chrome://inspect`），
//   对应 `WebView.setWebContentsDebuggingEnabled`，没有独立的 devtools 窗口。
// - 请求拦截命中**不中止加载**（照常放行，只把结果交出去）；桌面端 native 会给
//   空 204 并 `Stop()`。驱动只取第一个终态，所以两边对脚本的可见行为一致。
//
// 状态折算在 commonMain；这里只发 [WebViewEvent]。

/** Android 端的 [createWebviewContext] 实现：可见与后台（headless）共用一个类。 */
actual suspend fun createWebviewContext(isHeadless: Boolean): WebviewContext {
  // WebView 只能在主线程建/用；这里全程停在主线程上，而不是像 http.js 那样用
  // Thread.sleep 轮询（那会锁死主循环）。
  return withContext(Dispatchers.Main) {
    try {
      AndroidWebviewContext(WebView(hostApplicationContext), isHeadless)
    } catch (e: Throwable) {
      throw IllegalStateException("WebView 创建失败：$e", e)
    }
  }
}

/** Android 端的可见视图挂载面：`AndroidView` 包真身，覆盖层直接叠在上面。 */
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
    // 原生 WebView 之上就是普通 Compose 层，直接叠着画（桌面端麻烦得多：那边原生
    // 窗口会盖住同级 Compose 内容，覆盖层必须塞进 content 插槽）。
    content()
  }
}

/**
 * [WebviewContext] 的 Android 实现 —— 可见页与后台 WebView 共用一个类，
 * 行为差异（settings、销毁时的缓存清理）在 [init] 里按 [headless] 分支。
 *
 * `WebView` 的方法只能在主线程调，所以接口上的命令一律 `withContext(Dispatchers.Main)`；
 * 两个回调槽位是普通字段 —— 创建之后、首次导航之前一次性注入，不做同步。
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
        // 后台页只要 DOM/脚本，不要图片：省流量也省时间。
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
          // 只有主框架失败才算「这个页面没了」；子资源 404 不该把整页判死。
          if (request?.isForMainFrame != true) return
          onEvent?.invoke(WebViewEvent.LoadFailed(error?.description?.toString() ?: "加载失败"))
        }

        /**
         * 非 http 的跳转只在后台页里拦掉 —— 无人页面上不该发生唤起外部 App
         * 这类副作用；可见页放行，与系统默认一致。
         */
        override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
        ): Boolean = headless && !request.url.toString().startsWith("http")

        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): WebResourceResponse? {
          val callback = onInterceptor
          if (callback != null) {
            // 命中由驱动自己记账（它那边定结果）；这里照常放行。
            callback(
              request.url.toString(),
              headersWithCookie(request),
              request.method,
              request.isForMainFrame,
            )
          }
          return super.shouldInterceptRequest(view, request)
        }
      }

    // 标题与进度只有 WebChromeClient 有（`WebViewClient` 上没有这两个回调）。
    view.webChromeClient =
      object : WebChromeClient() {
        override fun onReceivedTitle(
          view: WebView?,
          title: String?,
        ) {
          title?.takeIf { it.isNotBlank() }?.let { onEvent?.invoke(WebViewEvent.TitleChanged(it)) }
        }

        /** Android 给的是真进度（0..100），桌面 WebView2 只给布尔，那边靠插值。 */
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

  override suspend fun navigate(
    url: String,
    headers: Map<String, String>,
  ) = withContext(Dispatchers.Main) { view.loadUrl(url, headers) }

  /**
   * 在当前页面里执行脚本并等回结果（JSON 字符串）。桌面端走 WebView2 的
   * `ExecuteScript`，这里走 `evaluateJavascript`，返回值形状一致。
   */
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

  /** Android 的 devtools 是 Chrome 远程调试，没有「打开面板」这个动作。 */
  override suspend fun openDevTools() = Unit

  /**
   * 用完即弃。非挂起：销毁动作投递回主线程（[Handler.post]），[view.destroy] 必须在主线程调，
   * 投递本身立刻返回、不阻塞调用方 —— 与桌面端 [Pointer.close] 把释放派发到归属线程同构。
   * 重复调用被 [disposed] 挡住，无副作用。
   */
  override fun close() {
    if (!disposed) {
      disposed = true
      // 主线程投递：destroy() 必须跑在主线程；非挂起版本不 await，立刻返回。
      Handler(Looper.getMainLooper()).post {
        runCatching {
          view.stopLoading()
          view.clearHistory()
          // 后台页按 key 用完即弃，顺手清缓存，避免上一个页面的 cookie/JS 状态
          // 串到下一个人手里；可见页不清（它是长驻的）。
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

/**
 * 把 cookie 折进请求头 —— 对齐 `http.js` 里 `makeRequest` 干的事。
 *
 * 后台页面的请求头本身不带 cookie，但脚本拿到 url 后要自己 `fetch` 一次，
 * 那次请求必须带上同一份会话，否则登录态是断的。
 */
internal fun headersWithCookie(request: WebResourceRequest): Map<String, String> {
  val headers = request.requestHeaders?.toMap() ?: emptyMap()
  return runCatching {
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()
    val cookie = cookieManager.getCookie(request.url.host ?: "") ?: ""
    if (cookie.isEmpty()) headers else headers + ("cookie" to cookie)
  }.getOrDefault(headers)
}
