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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 可见 WebView 的跨端抽象。
//
// 分层：commonMain 持有**全部逻辑**（状态折算、假进度插值、生命周期与失败兜底），
// 平台只提供两块收不进公共代码的东西：
//
//   - [createWebviewContext]：建一个平台 WebView 上下文（桌面 = WebView2 宿主建隐藏窗口，
//     Android = 主线程建 `android.webkit.WebView`）。可见与后台（headless）共用这一个入口。
//   - [WebViewSurface]：把上下文对应的原生视图铺进组合树（桌面 = SwingPanel + JAWT
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
 * 平台 WebView 向 commonMain 报告的事件。平台回调（native 线程 / Android 主线程）
 * 只发事件，折算到 [WebViewState] 的代码在 [WebViewState.onWebViewEvent] 一处。
 *
 * 全部是**可见页语义**：后台任务（`soko.ekibun.acg.engine.loadBackgroundWebView`）
 * 只用其中的 [PageFinished] 与 [LoadFailed]，其余由它自己消费 —— 脚本结果与拦截命中
 * 走的是驱动自己的返回值通道，不再是平台事件。
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
}

/**
 * 可见 WebView 的创建期配置。
 *
 * 只在视图创建时生效 —— 想改这些得让 [WebViewState] 重建（换 key / 换 url）。
 * **后台（headless）任务不读它**：那边由任务的字段驱动，配置由平台固化。
 */
class WebViewConfig {
  /** 自定义 User-Agent；null 表示用默认的。 */
  var userAgent: String? = null

  /** 是否允许 F12 开发者工具与右键菜单。调试页面时很有用，发布可以关掉。 */
  var enableDevtools: Boolean = false
}

/**
 * 平台 WebView 上下文 —— 可见页与后台任务共用同一个类型，差别只在调用方怎么用：
 * 可见壳配好 setter 后挂 [WebViewSurface]；后台驱动拿 `isHeadless = true` 的实例走
 * 「拦截 + evaluate」。
 *
 * 所有函数都是挂起的：每条命令落到**归属线程**上（桌面 = WebView 宿主线程，
 * Android = 主线程）执行完再返回，实现内部不再有 fire-and-forget 的投递。
 *
 * [onEvent] 与 [onInterceptor] 是从归属线程上调进来的：接收方自己保证线程安全，
 * 且**不要在回调里同步调回本接口的挂起方法**（桌面那会钉住宿主线程的消息泵，
 * 后台驱动里 `evaluate` 因此必须丢到独立 scope 上）。
 * 两个槽位都是**创建之后、首次导航之前一次性设好**的（不是运行期反复改的状态），
 * 所以实现侧就是普通字段，不需要 `@Volatile` 之类的同步。
 */
interface WebviewContext {
  /**
   * 页面生命周期事件出口。创建后、导航前设好；null = 摘掉。
   */
  var onEvent: ((WebViewEvent) -> Unit)?

  /**
   * 请求拦截：返回 `true` = 命中（桌面：native 给空 204 并 Stop；Android：照常放行、
   * 只把结果交出去）。null = 不拦。
   *
   * `method` 与 `isForMainFrame` 两端都给得到（Android 来自 `WebResourceRequest`，
   * 桌面来自 native 的 `WebResourceRequested`），脚本靠它们区分主框架与子资源；
   * Android 独有的 `isRedirect` 两边对不齐（桌面恒 false），因此不上回调。
   */
  var onInterceptor:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)?

  /** 自定义 User-Agent。 */
  suspend fun setUserAgent(ua: String)

  /** F12 / Chrome 远程调试开关。桌面无操作（`openDevTools` 不依赖它）。 */
  suspend fun setDevtoolsEnabled(enabled: Boolean)

  /** 导航到 [url]，附带 [headers]。 */
  suspend fun navigate(
    url: String,
    headers: Map<String, String> = emptyMap(),
  )

  /**
   * 在当前页执行脚本，返回平台给的 JSON 字符串；**失败**（超时 / 脚本抛错）是 `null`。
   *
   * 「没有返回值」两端给的**都不是** Kotlin 的 `null`，而是**字符串 `"null"`** —— WebView2
   * 把 `undefined` 序列化成 JSON 的 null，Android 的 `evaluateJavascript` 对 `undefined`
   * 同样给 `"null"`。桌面这一侧由 `Webview2Test` 钉住。
   */
  suspend fun evaluate(script: String): String?

  suspend fun reload()

  suspend fun stop()

  suspend fun goBack()

  suspend fun goForward()

  suspend fun openDevTools()

  /**
   * 收尾：可见页组合退出时、后台任务 `finally` 里。必须幂等。
   * 返回时销毁动作已在归属线程上发起（重复调用被静默忽略）。
   */
  suspend fun dispose()
}

/**
 * 建一个平台 WebView 上下文。
 *
 * 无模式 / 地址 / 脚本参数：[isHeadless] 选后台任务模式，其余配置走接口上的 setter，
 * 首屏地址由调用方建好之后自己 [WebviewContext.navigate]。
 *
 * 失败（环境起不来、Context 缺失）抛 [IllegalStateException]，消息给人看。
 */
expect suspend fun createWebviewContext(isHeadless: Boolean = false): WebviewContext

/**
 * 把上下文对应的原生视图铺进 [modifier] 划出的区域，[content] 是其上的 Compose
 * 覆盖层插槽。
 *
 * **注意**：桌面端原生视图是个真的 Win32 子窗口（重型组件），它会盖住同级
 * Compose 内容 —— 这个插槽在 Windows 上只在原生后端起不来时才看得见。
 */
@Composable
internal expect fun WebViewSurface(
  controller: WebviewContext,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit = {},
)

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

  /** 平台上下文；由 common 的壳在 [createWebviewContext] 成功后填。 */
  internal var controller: WebviewContext? = null

  /** 导航到 [url]，附带 [headers]。 */
  suspend fun loadUrl(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ) {
    currentUrl = url
    controller?.navigate(url, headers)
  }

  suspend fun reload() = controller?.reload()

  suspend fun stopLoading() = controller?.stop()

  suspend fun navigateBack() = controller?.goBack()

  suspend fun navigateForward() = controller?.goForward()

  /**
   * 在**当前页面**里执行一段脚本，返回它的结果（已由平台侧序列化成 JSON 字符串）。
   *
   * 与后台任务的 `script` 是同一件事，区别只在**对象**：那个是另开一个隐藏窗口抓页面，
   * 这个是用户在看的这一页 —— 不用重新请求一遍，而且带着用户当前的滚动位置、展开状态
   * 这些交互痕迹。
   *
   * 返回值语义和后台任务一致：脚本没有返回值时是 `null`，失败同样是 `null`。
   */
  suspend fun evaluate(script: String): String? = controller?.evaluate(script)

  /** 打开开发者工具（桌面端；Android 上无操作）。 */
  suspend fun openDevTools() = controller?.openDevTools()

  /** 平台实现往里记「起不来 / 加载失败」。 */
  internal fun fail(message: String) {
    failure = message
    isLoading = false
    loadingState = WebViewLoadingState.Failed(message)
  }

  /** 平台事件的唯一折算点（两端平台回调的合并）。 */
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
    }
  }
}

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
  var controller by remember(state) { mutableStateOf<WebviewContext?>(null) }

  LaunchedEffect(state) {
    val c =
      try {
        createWebviewContext().also { ctx ->
          ctx.onEvent = state::onWebViewEvent
          state.config.userAgent?.let { ctx.setUserAgent(it) }
          ctx.setDevtoolsEnabled(state.config.enableDevtools)
          state.controller = ctx
          // 初始导航由壳自己发：创建入口不带地址。
          ctx.navigate(state.initialUrl)
        }
      } catch (t: Throwable) {
        state.fail(t.message ?: t.toString())
        null
      }
    controller = c
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
      // 组合退出的那一帧没有协程上下文，而 dispose 是挂起的 —— 丢到进程级 scope 上
      // （dispose 自己幂等，重复调用被静默忽略）。
      disposeScope.launch { runCatching { c.dispose() } }
    }
  }

  Box(modifier) {
    WebViewSurface(c, Modifier.fillMaxSize()) { movableContent() }
  }
}

/** 可见 WebView 的默认首屏地址：about:blank（空页）。 */
internal const val DEFAULT_WEBVIEW_URL = "about:blank"

/**
 * 收尾动作的落地 scope。组合的 `onDispose` 没有协程上下文，而 [WebviewContext.dispose]
 * 是挂起的 —— 需要一个不随组合取消的进程级 scope。取值 [Dispatchers.Default]：
 * dispose 只是投递一次「归还」并返回，不阻塞。
 */
private val disposeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
