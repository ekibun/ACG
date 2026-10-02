package soko.ekibun.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

// 可见 WebView 的跨端抽象。
//
// 分层：commonMain 持有**全部逻辑**（状态折算、假进度插值、生命周期与失败兜底），
// 平台只提供两块收不进公共代码的东西：
//
//   - [createWebViewController]：建控制器（桌面 = WebView2 宿主建隐藏窗口，Android =
//     主线程建 `android.webkit.WebView`）。可见与后台（headless）共用这一个入口。
//   - [WebViewSurface]：把控制器对应的原生视图铺进组合树（桌面 = SwingPanel + JAWT
//     挂原生窗口，Android = AndroidView）。
//
// 两端都走同一个事件出口（[WebViewEvent]）：平台回调只发事件，往 [WebViewState]
// 折算的代码只有 commonMain 这一份。
//
// | | 实现 | cookie 存储 |
// | --- | --- | --- |
// | 桌面 (Windows) | 自研 C++ shim（`cxx/webview/webview.cpp`）里的 WebView2「嵌入视图」 | 和后台 WebView **同一个 user data folder** |
// | Android | `android.webkit.WebView` + `AndroidView` | 系统单一 `CookieManager` |
//
// 两端的可见页与后台页共用同一份 cookie 存储。桌面端只有 Windows 有实现。

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
 * 平台控制器向 commonMain 报告的事件。平台回调（native 线程 / Android 主线程）
 * 只发事件，折算到 [WebViewState] 的代码在 [WebViewState.onWebViewEvent] 一处。
 *
 * `PageStarted` / `Progress` / `UrlChanged` / `DocumentLoaded` / `PageFinished` /
 * `TitleChanged` / `HistoryChanged` / `LoadFailed` 是**可见页**关心的；
 * `RequestIntercepted` / `ScriptFinished` / `TaskFailed` 是**后台任务**
 * （[loadBackgroundWebView]）的终态事件，可见页收到后忽略。
 */
sealed interface WebViewEvent {
  /** 页面开始加载。Android = `onPageStarted`；桌面 = loading=1。 */
  data object PageStarted : WebViewEvent

  /** 真实加载进度（0..1）。只有 Android 给；桌面没有，由壳的插值循环兜住。 */
  data class Progress(
    val fraction: Float,
  ) : WebViewEvent

  /** 地址变了（不区分是否已提交）。Android = `onPageStarted` / `doUpdateVisitedHistory`。 */
  data class UrlChanged(
    val url: String,
  ) : WebViewEvent

  /** 有文档真的加载出来了（提交且可交互）。桌面 = SourceChanged；Android = `onPageFinished`。 */
  data class DocumentLoaded(
    val url: String,
  ) : WebViewEvent

  /** 这一轮加载结束（无论成败，失败走 [LoadFailed]）。Android = `onPageFinished`；桌面 = loading=0。 */
  data object PageFinished : WebViewEvent

  /** 标题变化。 */
  data class TitleChanged(
    val title: String,
  ) : WebViewEvent

  /** 历史栈变化。 */
  data class HistoryChanged(
    val canGoBack: Boolean,
    val canGoForward: Boolean,
  ) : WebViewEvent

  /** 主框架加载失败（子资源 404 不算）。 */
  data class LoadFailed(
    val message: String,
  ) : WebViewEvent

  /** 后台任务：请求拦截命中，加载将被平台中止。 */
  data class RequestIntercepted(
    val interception: WebViewInterception,
  ) : WebViewEvent

  /**
   * 后台任务：页面加载完成且注入脚本已执行完（`json = null` 表示没有返回值，包括
   * 「没给脚本」的情况）。桌面无脚本时由 native 的 NavigationCompleted 直接发。
   */
  data class ScriptFinished(
    val json: String?,
  ) : WebViewEvent

  /** 后台任务：起不来或导航失败。 */
  data class TaskFailed(
    val message: String,
  ) : WebViewEvent
}

/**
 * 可见 WebView 的创建期配置。
 *
 * 只在视图创建时生效 —— 想改这些得让 [WebViewState] 重建（换 key / 换 url）。
 * **后台（headless）控制器不读它**：后台页只要 DOM/脚本，配置由平台固化。
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
   * `nativeCreateView` 没有这个参数，`ViewOptions::allowNewWindow`
   * （`cxx/webview/webview.cpp`）恒为默认 `false` —— 也就是说桌面端恒按「把
   * 新窗口请求压回当前视图」处理（`NewWindowHandler` 就地导航）。要让桌面端
   * 认这个旋钮，得给 native 加参数。
   */
  var allowNewWindow: Boolean = false

  /** 页面缩放。1.0 = 100%。 */
  var zoom: Double = 1.0
}

/**
 * 可见 WebView 的状态。
 *
 * 由 [rememberWebViewState] 建；common 的壳把平台事件折算进来。UI 只读。
 */
class WebViewState internal constructor(
  /** 首屏加载的地址。 */
  internal val initialUrl: String,
  internal val config: WebViewConfig,
) {
  /** 当前（或即将）加载的地址；还没收到地址事件就是 null。 */
  var currentUrl by mutableStateOf<String?>(null)
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

  /** 平台报过真实进度（Android）就不再跑假进度插值。普通字段，不驱动重组。 */
  internal var sawRealProgress = false

  /** 平台控制器；由 common 的壳在 [createWebViewController] 成功后填。 */
  internal var controller: WebViewController? = null

  /** 导航到 [url]，附带 [headers]（桌面走 `NavigateWithWebResourceRequest`）。 */
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

  /** 平台事件的唯一折算点（原桌面 `onNativeEvent` 与 Android 各回调直写 state 的合并）。 */
  internal fun onWebViewEvent(event: WebViewEvent) {
    when (event) {
      is WebViewEvent.PageStarted -> {
        isLoading = true
        sawRealProgress = false
        loadingState = WebViewLoadingState.Loading(0.15f)
      }

      is WebViewEvent.Progress -> {
        sawRealProgress = true
        if (loadingState !is WebViewLoadingState.Failed) {
          loadingState = WebViewLoadingState.Loading(event.fraction.coerceIn(0f, 1f))
        }
      }

      is WebViewEvent.UrlChanged -> currentUrl = event.url

      is WebViewEvent.DocumentLoaded -> {
        currentUrl = event.url
        lastLoadedUrl = event.url
      }

      is WebViewEvent.PageFinished -> {
        isLoading = false
        if (loadingState !is WebViewLoadingState.Failed) {
          loadingState = WebViewLoadingState.Finished
        }
      }

      is WebViewEvent.TitleChanged -> {
        pageTitle = event.title.takeIf { it.isNotBlank() } ?: pageTitle
      }

      is WebViewEvent.HistoryChanged -> {
        canGoBack = event.canGoBack
        canGoForward = event.canGoForward
      }

      is WebViewEvent.LoadFailed -> fail(event.message)

      // 后台任务的终态事件，可见页不消费。
      is WebViewEvent.RequestIntercepted, is WebViewEvent.ScriptFinished, is WebViewEvent.TaskFailed -> Unit
    }
  }
}

/**
 * 平台侧的控制器。桌面 = WebView2，Android = `android.webkit.WebView`。
 *
 * 由 [createWebViewController] 创建；这些调用都可能从任意线程来，实现自己负责切线程。
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
   * 默认实现返回 `null` = 该平台不支持。桌面端（WebView2）与 Android 实现都走
   * 平台原生的「执行并回调」通道；**不要用轮询或注入 `<script>` 去模拟**，
   * 那样拿不到返回值。
   */
  suspend fun evaluate(script: String): String? = null

  /** 打开开发者工具。没有这条能力的平台给空实现即可。 */
  fun openDevTools() {}

  /**
   * 收尾：可见页在组合退出时由壳调用，后台任务在 `finally` 里调用。
   * 实现必须幂等 —— 重复销毁、销毁已死的句柄都只能安静地忽略。
   */
  fun dispose() {}
}

/**
 * 建一个平台 WebView 控制器 —— 平台唯一需要实现的创建入口，可见与后台共用。
 *
 * - `headless = false`：可见视图，按 [config] 配置并导航到 [initialUrl]。
 *   创建成功不等于已经显示：挂载由 [WebViewSurface] 负责（桌面要等 AWT 组件
 *   拿到 peer 之后才能 `SetParent`）。
 * - `headless = true`：后台任务的用完即弃视图。不读 [config]（平台自带后台默认
 *   配置），导航到 [initialUrl]（带 [initialHeaders]），页面加载完执行 [script]
 *   并经 [WebViewEvent.ScriptFinished] 回结果；[onRequest] 是请求拦截回调，
 *   命中即由平台中止加载并发 [WebViewEvent.RequestIntercepted]。
 *
 * [onEvent] 会从任意线程被调（native 回调线程 / Android 主线程），接收方自己保证
 * 线程安全。失败（环境起不来、窗口建不出）抛 [IllegalStateException]，消息给人看。
 */
internal expect suspend fun createWebViewController(
  headless: Boolean,
  config: WebViewConfig,
  initialUrl: String,
  initialHeaders: Map<String, String>,
  script: String?,
  onRequest: ((WebViewRequest) -> WebViewInterception?)?,
  onEvent: (WebViewEvent) -> Unit,
): WebViewController

/**
 * 把控制器对应的原生视图铺进 [modifier] 划出的区域，[content] 是其上的 Compose
 * 覆盖层插槽。
 *
 * **注意**：桌面端原生视图是个真的 Win32 子窗口（重型组件），它会盖住同级
 * Compose 内容 —— 这个插槽在 Windows 上只在原生后端起不来时才看得见。
 */
@Composable
internal expect fun WebViewSurface(
  controller: WebViewController,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit = {},
)

/**
 * 建一个 [WebViewState]。
 *
 * [initialUrl] 是首屏加载的地址（默认 [DEFAULT_WEBVIEW_URL]，about:blank 空页）。
 * [config] 是「建一次」的 DSL —— 只在首次组合时求值。它**不是**
 * `remember` 的 key：写成一个 key 的话，每次重组都会因为 lambda 实例不同而重建整个
 * 状态（连带把 WebView 拆了重建）。想换初始地址或配置就给 `WebView` 换个 key
 * （重建整个状态）。
 */
@Composable
fun rememberWebViewState(
  initialUrl: String = DEFAULT_WEBVIEW_URL,
  config: WebViewConfig.() -> Unit = {},
): WebViewState =
  remember {
    WebViewState(initialUrl, WebViewConfig().apply(config))
  }

/**
 * 把真正的 WebView 铺到 [modifier] 划出的区域里（common 实现，平台只出
 * [WebViewSurface]）。首屏默认加载 [DEFAULT_WEBVIEW_URL]（about:blank，空页），
 * 不自动导航到任何站点 —— 之后用 [WebViewState.loadUrl] 去。
 *
 * [content] 是原生页面之上的 Compose 覆盖层插槽。「后端没起来」与「正常」两条
 * 分支共用同一个槽位（[movableContentOf] 搬运，槽内状态不丢）；lambda 经
 * [rememberUpdatedState] 取最新，免得调用方换了 lambda 之后还在跑旧的。
 */
@Composable
fun WebView(
  state: WebViewState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit = {},
) {
  var controller by remember(state) { mutableStateOf<WebViewController?>(null) }

  LaunchedEffect(state) {
    val c =
      try {
        createWebViewController(
          headless = false,
          config = state.config,
          initialUrl = state.initialUrl,
          initialHeaders = emptyMap(),
          script = null,
          onRequest = null,
        ) { state.onWebViewEvent(it) }
      } catch (t: Throwable) {
        state.fail(t.message ?: t.toString())
        null
      }
    if (c != null) {
      state.controller = c
      controller = c
    }
  }

  // WebView2 只给「在不在加载」，没有进度。平台没报真进度时（桌面）由下面的插值
  // 兜底；Screen 只读 loadingState，不关心进度从哪来。
  LaunchedEffect(state.isLoading) { state.interpolateFakeProgress() }

  val currentContent by rememberUpdatedState(content)
  val movableContent = remember { movableContentOf { currentContent() } }

  val c = controller
  if (c == null) {
    // 后端不可用（或还没起来）：退回成一个空盒子，让上层自己的提示层露出来。
    Box(modifier) { movableContent() }
    return
  }

  DisposableEffect(c) {
    onDispose {
      if (state.controller === c) state.controller = null
      c.dispose()
    }
  }

  Box(modifier) {
    WebViewSurface(c, Modifier.fillMaxSize()) { movableContent() }
  }
}

/** 可见 WebView 的默认首屏地址：about:blank（空页）。 */
internal const val DEFAULT_WEBVIEW_URL = "about:blank"

/**
 * 假进度插值：WebView2 只给「在不在加载」，没有进度。平台没报过真进度
 * （[WebViewState.sawRealProgress]）时按固定步长推进、0.9 封顶 —— 视觉上
 * 不至于一直卡在同一个位置。
 */
private suspend fun WebViewState.interpolateFakeProgress() {
  while (isLoading && !sawRealProgress) {
    delay(PROGRESS_TICK_MS)
    val current = loadingState
    if (current is WebViewLoadingState.Loading) {
      loadingState =
        WebViewLoadingState.Loading(
          (current.progress + PROGRESS_STEP).coerceAtMost(MAX_FAKE_PROGRESS),
        )
    }
  }
}

private const val PROGRESS_TICK_MS = 120L
private const val PROGRESS_STEP = 0.02f
private const val MAX_FAKE_PROGRESS = 0.9f
