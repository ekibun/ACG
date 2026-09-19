package soko.ekibun.acg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// 可见 WebView 的跨端抽象。
//
// 两端都是自己实现：
//
// | | 实现 | cookie 存储 |
// | --- | --- | --- |
// | 桌面 (Windows) | 自研 C++ shim（`cxx/webview/webview.cpp`）里的 WebView2「嵌入视图」 | 和后台 WebView **同一个 user data folder** |
// | Android | `android.webkit.WebView` + `AndroidView` | 系统单一 `CookieManager` |
//
// 两端的可见页与后台页共用同一份 cookie 存储。
//
// 桌面端只有 Windows 有实现（WebView2）；其它平台 AcgWebView 会退化成一个提示框。

/** WebView 的加载状态。 */
sealed interface WebViewLoadingState {
  /** 还没有任何文档。 */
  data object Initializing : WebViewLoadingState

  /** 正在加载。`progress` 是 0..1 的估算值（WebView2 只给布尔，这里是插值出来的）。 */
  data class Loading(
    val progress: Float,
  ) : WebViewLoadingState

  /** 已经有一个文档了。 */
  data object Finished : WebViewLoadingState

  /** 起不来或者加载失败。 */
  data class Failed(
    val message: String,
  ) : WebViewLoadingState
}

/**
 * 可见 WebView 的创建期配置。
 *
 * 只在视图创建时生效 —— 想改这些得让 [AcgWebViewState] 重建（换 key / 换 url）。
 */
class WebViewConfig {
  /** 自定义 User-Agent；null 表示用默认的。 */
  var userAgent: String? = null

  /** 是否允许 F12 开发者工具与右键菜单。调试页面时很有用，发布可以关掉。 */
  var enableDevtools: Boolean = false

  /**
   * 是否允许页面自己弹新窗口。**两端当前都取 `false`**。
   *
   * 实际生效的只有 Android（`setSupportMultipleWindows`）。桌面宿主的
   * `nativeCreateView` 根本没有这个参数，`ViewOptions::allowNewWindow`
   * （`cxx/webview/webview.cpp`）恒为默认 `false` —— 想让桌面端认它得先给
   * native 加参数（见 TODO）。
   */
  var allowNewWindow: Boolean = false

  /** 页面缩放。1.0 = 100%。 */
  var zoom: Double = 1.0
}

/**
 * 可见 WebView 的状态。
 *
 * 由 [rememberAcgWebViewState] 建；两端的 actual 实现往里写。UI 只读。
 */
class AcgWebViewState internal constructor(
  /** 首次加载的地址。 */
  val homeUrl: String,
  internal val config: WebViewConfig,
) {
  /** 当前（或即将）加载的地址。 */
  var currentUrl by mutableStateOf(homeUrl)
    internal set

  /** 文档标题。拿不到就是 null。 */
  var pageTitle by mutableStateOf<String?>(null)
    internal set

  var isLoading by mutableStateOf(false)
    internal set

  var loadingState by mutableStateOf<WebViewLoadingState>(WebViewLoadingState.Initializing)
    internal set

  /** 最近一次真的加载出文档的地址。用来区分「空壳后端」和「页面还没到」。 */
  var lastLoadedUrl by mutableStateOf<String?>(null)
    internal set

  var canGoBack by mutableStateOf(false)
    internal set

  var canGoForward by mutableStateOf(false)
    internal set

  /** 后端不可用或加载失败时的原因；null 表示没出问题。 */
  var failure by mutableStateOf<String?>(null)
    internal set

  /** 平台控制器；由 actual 实现填。 */
  internal var controller: WebViewController? = null

  /** 导航到 [url]，附带 [headers]（会走 `NavigateWithWebResourceRequest`）。 */
  fun loadUrl(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ) {
    currentUrl = url
    controller?.loadUrl(url, headers)
  }

  fun reload() = controller?.reload()

  fun stopLoading() = controller?.stop()

  fun navigateBack() = controller?.goBack()

  fun navigateForward() = controller?.goForward()

  /**
   * 在**当前页面**里执行一段脚本，返回它的结果（已由平台侧序列化成 JSON 字符串）。
   *
   * 与后台任务 [loadBackgroundWebView] 的 `script` 是同一件事，区别只在**对象**：
   * 那个是另开一个隐藏窗口抓页面，这个是用户在看的这一页 —— 不用重新请求一遍，
   * 而且带着用户当前的滚动位置、展开状态这些交互痕迹。
   *
   * 返回值语义和后台任务一致：脚本没有返回值时是 `null`（不是 `"null"`，
   * 也不是空串），失败同样是 `null`。返回 `null` 也可能是平台不支持
   * （[WebViewController.evaluate] 的默认实现）。
   */
  suspend fun evaluate(script: String): String? = controller?.evaluate(script)

  /** 打开开发者工具（桌面端；Android 上无操作）。 */
  fun openDevTools() = controller?.openDevTools()

  /** 平台实现往里记「起不来 / 加载失败」。 */
  internal fun fail(message: String) {
    failure = message
    isLoading = false
    loadingState = WebViewLoadingState.Failed(message)
  }
}

/**
 * 平台侧的控制器。桌面 = WebView2，Android = `android.webkit.WebView`。
 *
 * 这些调用都可能从任意线程来，实现自己负责切线程。
 */
interface WebViewController {
  fun loadUrl(
    url: String,
    headers: Map<String, String>,
  )

  fun reload()

  fun stop()

  fun goBack()

  fun goForward()

  /**
   * 在当前页面里执行脚本，返回结果的 JSON 字符串。
   *
   * 默认实现返回 `null` = 该平台不支持。桌面端（WebView2）与后面的 Android
   * 实现都走平台原生的「执行并回调」通道；**不要用轮询或注入 `<script>` 去模拟**，
   * 那样拿不到返回值。
   */
  suspend fun evaluate(script: String): String? = null

  /** 打开开发者工具。没有这条能力的平台给空实现即可。 */
  fun openDevTools() {}
}

/**
 * 建一个 [AcgWebViewState]。
 *
 * [config] 是「建一次」的 DSL —— 只在首次组合（或 [homeUrl] 变了）时求值。它**不是**
 * `remember` 的 key：写成一个 key 的话，每次重组都会因为 lambda 实例不同而重建整个
 * 状态（连带把 WebView 拆了重建）。想换配置就换 [homeUrl]，或者给 `AcgWebView` 换个 key。
 */
@Composable
fun rememberAcgWebViewState(
  homeUrl: String,
  config: WebViewConfig.() -> Unit = {},
): AcgWebViewState =
  remember(homeUrl) {
    AcgWebViewState(homeUrl, WebViewConfig().apply(config))
  }

/**
 * 把真正的 WebView 铺到 [modifier] 划出的区域里。
 *
 * [content] 是原生页面之上的 Compose 覆盖层插槽。**注意**：桌面端原生视图是个真的
 * Win32 子窗口（重型组件），它会盖住同级 Compose 内容 —— 这个插槽在 Windows 上只在
 * 原生后端起不来时才看得见。后端不可用时这里直接退化成一个空盒子，画 [content]。
 */
@Composable
expect fun AcgWebView(
  state: AcgWebViewState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit = {},
)
