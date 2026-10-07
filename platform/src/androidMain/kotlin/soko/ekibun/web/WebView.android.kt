package soko.ekibun.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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

// Android 端的 WebView 平台层：控制器工厂（可见 + 后台共用一个类，按模式分配置）+
// 原生视图挂载面。直接包 `android.webkit.WebView`，不做无头化 —— 它就是真身。
//
// cookie 统一是「白送」的：Android 全进程只有一个系统级 `CookieManager`，
// 可见页和后台 WebView 走的是同一份存储，不像桌面端那样必须刻意共用一个
// user data folder。
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
//
// 状态折算在 commonMain；这里只发 [WebViewEvent]。

/** Android 端的 [createWebViewController] 实现：可见与后台（headless）共用一个入口。 */
internal actual suspend fun createWebViewController(
  headless: Boolean,
  config: WebViewConfig,
  initialUrl: String,
  initialHeaders: Map<String, String>,
  script: String?,
  onRequest: ((WebViewRequest) -> WebViewInterception?)?,
  onEvent: (WebViewEvent) -> Unit,
): WebViewController {
  val context =
    applicationContext
      ?: throw IllegalStateException(
        "WebView 缺少 Context：Android 端要先在 setContent 里调一次 BackgroundWebViewHost()",
      )
  // WebView 只能在主线程建/用；这里全程停在主线程上，而不是像 http.js 那样用
  // Thread.sleep 轮询（那会锁死主循环）。
  return withContext(Dispatchers.Main) {
    val controller =
      try {
        AndroidWebViewController(WebView(context), headless, onEvent)
      } catch (e: Throwable) {
        throw IllegalStateException("WebView 创建失败：$e", e)
      }
    if (headless) {
      controller.configureHeadless(script, onRequest)
    } else {
      controller.configureVisible(config)
    }
    controller.view.loadUrl(initialUrl, initialHeaders)
    controller
  }
}

/** Android 端的可见视图挂载面：`AndroidView` 包真身，覆盖层直接叠在上面。 */
@Composable
internal actual fun WebViewSurface(
  controller: WebViewController,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val impl = controller as AndroidWebViewController
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
 * [WebViewController] 的 Android 实现 —— 可见页与后台 WebView 共用一个类，
 * 行为差异（settings、client、销毁时的缓存清理）在两个 `configureXxx` 里分开。
 *
 * 事件经 [onEvent] 发回 common；`WebView` 的方法必须在主线程调，命令入口都做了
 * 主线程归一（可见命令的调用方本来就在主线程，[dispose] 则可能从任意线程来）。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidWebViewController(
  internal val view: WebView,
  internal val headless: Boolean,
  private val onEvent: (WebViewEvent) -> Unit,
) : WebViewController {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var disposed = false

  override fun loadUrl(
    url: String,
    headers: Map<String, String>,
  ) = onMainThread { view.loadUrl(url, headers) }

  override fun reload() = onMainThread { view.reload() }

  override fun stop() = onMainThread { view.stopLoading() }

  override fun goBack() =
    onMainThread {
      if (view.canGoBack()) view.goBack()
    }

  override fun goForward() =
    onMainThread {
      if (view.canGoForward()) view.goForward()
    }

  /** Android 的 devtools 是 Chrome 远程调试，没有「打开面板」这个动作。 */
  override fun openDevTools() = Unit

  /**
   * 在当前页面里执行脚本并等回结果（JSON 字符串）。桌面端走 WebView2 的
   * `ExecuteScript`，这里走 `evaluateJavascript`，返回值形状一致。
   */
  override suspend fun evaluate(script: String): String? {
    val result = CompletableDeferred<String?>()
    onMainThread { view.evaluateJavascript(script) { result.complete(it) } }
    return result.await()
  }

  /**
   * 用完即弃。可能从任意线程调（后台骨架的 `finally` 在调用方协程上），
   * 销毁动作归一回主线程；重复调用无副作用。
   */
  override fun dispose() {
    val doDestroy = {
      if (!disposed) {
        disposed = true
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
    if (Looper.myLooper() == Looper.getMainLooper()) doDestroy() else mainHandler.post(doDestroy)
  }

  // ---- 可见页配置（原 `configure`）----

  internal fun configureVisible(config: WebViewConfig) {
    view.settings.apply {
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
      view.setInitialScale((config.zoom * 100).toInt())
    }
    config.userAgent?.let { view.settings.userAgentString = it }

    WebView.setWebContentsDebuggingEnabled(config.enableDevtools)

    view.webViewClient =
      object : WebViewClient() {
        override fun onPageStarted(
          view: WebView?,
          url: String?,
          favicon: Bitmap?,
        ) {
          if (url.isNullOrBlank()) return
          onEvent(WebViewEvent.UrlChanged(url))
          onEvent(WebViewEvent.PageStarted)
        }

        override fun onPageFinished(
          view: WebView?,
          url: String?,
        ) {
          if (!url.isNullOrBlank()) onEvent(WebViewEvent.DocumentLoaded(url))
          onEvent(WebViewEvent.PageFinished)
          view?.let(::syncHistory)
        }

        override fun doUpdateVisitedHistory(
          view: WebView?,
          url: String?,
          isReload: Boolean,
        ) {
          url?.takeIf { it.isNotBlank() }?.let { onEvent(WebViewEvent.UrlChanged(it)) }
          view?.let(::syncHistory)
        }

        override fun onReceivedError(
          view: WebView?,
          request: WebResourceRequest?,
          error: WebResourceError?,
        ) {
          // 只有主框架失败才算「这个页面没了」；子资源 404 不该把整页判死。
          if (request?.isForMainFrame != true) return
          onEvent(WebViewEvent.LoadFailed(error?.description?.toString() ?: "加载失败"))
        }
      }

    // 标题与进度只有 WebChromeClient 有（`WebViewClient` 上没有这两个回调）。
    view.webChromeClient =
      object : WebChromeClient() {
        override fun onReceivedTitle(
          view: WebView?,
          title: String?,
        ) {
          title?.takeIf { it.isNotBlank() }?.let { onEvent(WebViewEvent.TitleChanged(it)) }
        }

        /** Android 给的是真进度（0..100），桌面 WebView2 只给布尔，那边靠插值。 */
        override fun onProgressChanged(
          view: WebView?,
          newProgress: Int,
        ) {
          onEvent(WebViewEvent.Progress(newProgress / 100f))
        }
      }
  }

  // ---- 后台页配置（原 `BackgroundWebViewImpl.init`）----

  internal fun configureHeadless(
    script: String?,
    onRequest: ((WebViewRequest) -> WebViewInterception?)?,
  ) {
    view.settings.apply {
      javaScriptEnabled = true
      useWideViewPort = true
      loadWithOverviewMode = true
      setSupportMultipleWindows(true)
      domStorageEnabled = true
      mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      // 后台页只要 DOM/脚本，不要图片：省流量也省时间。
      blockNetworkImage = true
    }

    // 拦截命中后置位：这次请求照常放行，但结果已经定下来了，不让之后的
    // onPageFinished 覆盖它（与 http.js 一致）。终态幂等另有骨架的
    // CompletableDeferred 兜底，这里摘掉的是「错误页上再跑一遍脚本」。
    var settled = false

    view.webViewClient =
      object : WebViewClient() {
        override fun onPageFinished(
          view: WebView?,
          url: String?,
        ) {
          if (settled) return
          // 对齐 http.js：只有 script 的返回值才算结果，没 script 就是没有结果。
          if (script == null) {
            onEvent(WebViewEvent.ScriptFinished(null))
          } else {
            view?.evaluateJavascript(script) { value -> onEvent(WebViewEvent.ScriptFinished(value)) }
          }
        }

        override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
        ): Boolean = !request.url.toString().startsWith("http")

        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): WebResourceResponse? {
          val callback = onRequest ?: return super.shouldInterceptRequest(view, request)
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
          if (hit != null && !settled) {
            settled = true
            onEvent(WebViewEvent.RequestIntercepted(hit))
          }
          return super.shouldInterceptRequest(view, request)
        }
      }
  }

  private fun syncHistory(web: WebView) {
    onEvent(WebViewEvent.HistoryChanged(canGoBack = web.canGoBack(), canGoForward = web.canGoForward()))
  }

  private inline fun onMainThread(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post { block() }
    }
  }
}
