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

// 可见 WebView 的跨端抽象：commonMain 负责状态折算、进度插值、生命周期与失败兜底，
// 平台只提供 [createWebviewContext] 与 [WebViewSurface]。两端共用 [WebViewEvent] 出口。

/** WebView 的加载状态。 */
sealed interface WebViewLoadingState {
  /** 还没有任何文档。 */
  data object Initializing : WebViewLoadingState

  /** 正在加载。[progress] 是 0..1 的估算值。 */
  data class Loading(
    val progress: Float,
  ) : WebViewLoadingState

  /** 已有一个文档。 */
  data object Finished : WebViewLoadingState

  /** 起不来或加载失败。 */
  data class Failed(
    val message: String,
  ) : WebViewLoadingState
}

/** 平台 WebView 上报的事件，统一由 [WebViewState.onWebViewEvent] 折算。 */
sealed interface WebViewEvent {
  /** 页面开始加载。 */
  data object PageStarted : WebViewEvent

  /** 真实加载进度（0..1）。 */
  data class Progress(
    val fraction: Float,
  ) : WebViewEvent

  /** 地址变化。 */
  data class UrlChanged(
    val url: String,
  ) : WebViewEvent

  /** 文档已提交并可交互。 */
  data class DocumentLoaded(
    val url: String,
  ) : WebViewEvent

  /** 本轮加载结束。 */
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

  /** 主框架加载失败。 */
  data class LoadFailed(
    val message: String,
  ) : WebViewEvent
}

/** 可见 WebView 的创建期配置，只在视图创建时生效。 */
class WebViewConfig {
  /** 是否允许开发者工具与右键菜单。 */
  var enableDevtools: Boolean = false
}

/**
 * 平台 WebView 上下文：可见页与后台任务共用，后台实例 `isHeadless = true`。
 * 函数均为挂起，命令投到归属线程执行完再返回。
 */
interface WebviewContext : AutoCloseable {
  /** 页面生命周期事件出口；null 表示不接收。 */
  var onEvent: ((WebViewEvent) -> Unit)?

  /**
   * 请求拦截：返回 `true` 表示命中，null 表示不拦截。
   * 参数为 url、headers、method、是否主框架。
   */
  var onInterceptor:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)?

  /** 主框架导航前同步判定：返回 true 即取消，地址交调用方处置。 */
  var onNavigate: ((url: String) -> Boolean)?

  /** 设置自定义 User-Agent。 */
  suspend fun setUserAgent(ua: String)

  /** 开关开发者工具；桌面端无操作。 */
  suspend fun setDevtoolsEnabled(enabled: Boolean)

  /**
   * 切前后台：同一个实例换宿主，不重建页面。
   *
   * 后台态省资源（Android 不加载图片、桌面按 1×1 布局），挂到界面上之前要先置前台，
   * 否则拿到的是一张缺图的页面。
   */
  suspend fun setVisible(visible: Boolean)

  /** 导航到 [url]，附带 [headers]。 */
  suspend fun navigate(
    url: String,
    headers: Map<String, String> = emptyMap(),
  )

  /**
   * 在当前页执行脚本，返回平台序列化的 JSON 字符串，失败为 `null`。
   * 无返回值时两端给的是字符串 `"null"`。
   */
  suspend fun evaluate(script: String): String?

  suspend fun reload()

  suspend fun stop()

  suspend fun goBack()

  suspend fun goForward()

  suspend fun openDevTools()

  /** 释放上下文，幂等；非挂起，销毁投到归属线程。 */
  override fun close()
}

/**
 * 建一个平台 WebView 上下文，[isHeadless] 表示后台模式。
 * 失败抛 [IllegalStateException]；首屏地址由调用方 [WebviewContext.navigate]。
 */
expect suspend fun createWebviewContext(isHeadless: Boolean = false): WebviewContext

/** 把原生视图铺进 [modifier] 区域，[content] 是其上的 Compose 覆盖层。 */
@Composable
internal expect fun WebViewSurface(
  controller: WebviewContext,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit = {},
)

/** 可见 WebView 的状态，由 [rememberWebViewState] 建；UI 只读。 */
class WebViewState internal constructor(
  /** 首屏地址。 */
  internal val initialUrl: String,
  internal val config: WebViewConfig,
) {
  /** 当前或即将加载的地址；未收到地址事件时为 null。 */
  var currentUrl by mutableStateOf<String?>(null)
    internal set

  /** 文档标题；拿不到为 null。 */
  var pageTitle by mutableStateOf<String?>(null)
    internal set

  /** 是否正在加载。 */
  var isLoading by mutableStateOf(false)
    internal set

  /** 加载状态。 */
  var loadingState by mutableStateOf<WebViewLoadingState>(WebViewLoadingState.Initializing)
    internal set

  /** 最近一次真正加载出文档的地址。 */
  var lastLoadedUrl by mutableStateOf<String?>(null)
    internal set

  /** 能否后退。 */
  var canGoBack by mutableStateOf(false)
    internal set

  /** 能否前进。 */
  var canGoForward by mutableStateOf(false)
    internal set

  /** 失败原因；null 表示正常。 */
  var failure by mutableStateOf<String?>(null)
    internal set

  /** 平台是否报过真实进度。 */
  internal var sawRealProgress = false

  /** 平台上下文，创建成功后填入。 */
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

  /** 在当前页面执行脚本，返回 JSON 字符串；失败或无返回值为 `null`。 */
  suspend fun evaluate(script: String): String? = controller?.evaluate(script)

  /** 打开开发者工具；Android 上无操作。 */
  suspend fun openDevTools() = controller?.openDevTools()

  /** 记录失败状态。 */
  internal fun fail(message: String) {
    failure = message
    isLoading = false
    loadingState = WebViewLoadingState.Failed(message)
  }

  /** 把平台事件折算进状态。 */
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

/** 建一个 [WebViewState]；[config] 只在首次组合时求值。 */
@Composable
fun rememberWebViewState(
  initialUrl: String = DEFAULT_WEBVIEW_URL,
  config: WebViewConfig.() -> Unit = {},
): WebViewState =
  remember {
    WebViewState(initialUrl, WebViewConfig().apply(config))
  }

/** 把 WebView 铺进 [modifier] 区域，首屏加载 [DEFAULT_WEBVIEW_URL]；[content] 是覆盖层插槽。 */
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
          ctx.setDevtoolsEnabled(state.config.enableDevtools)
          state.controller = ctx
          // 创建完成后发起首屏导航
          ctx.navigate(state.initialUrl)
        }
      } catch (t: Throwable) {
        state.fail(t.message ?: t.toString())
        null
      }
    controller = c
  }

  // 加载期间由插值循环推进假进度
  LaunchedEffect(state.isLoading) { state.interpolateFakeProgress() }

  val currentContent by rememberUpdatedState(content)
  val movableContent = remember { movableContentOf { currentContent() } }

  val c = controller
  if (c == null) {
    // 后端不可用：只渲染覆盖层
    Box(modifier) { movableContent() }
    return
  }

  DisposableEffect(c) {
    onDispose {
      if (state.controller === c) state.controller = null
      // 在进程级 scope 上执行挂起的释放
      disposeScope.launch { runCatching { c.close() } }
    }
  }

  Box(modifier) {
    WebViewSurface(c, Modifier.fillMaxSize()) { movableContent() }
  }
}

/** 默认首屏地址。 */
internal const val DEFAULT_WEBVIEW_URL = "about:blank"

/**
 * 把一个**已经在跑**的 [WebviewContext] 铺到界面上。
 *
 * 与 [WebView] 的区别：这里不新建实例，前后台切换就是把同一个实例挂上/摘下——
 * 页面状态（表单、滚动位置、登录进度）都留在原处。
 */
@Composable
fun WebViewHost(
  controller: WebviewContext,
  modifier: Modifier = Modifier,
) {
  WebViewSurface(controller, modifier)
}

/** 释放动作用的进程级 scope，不随组合取消。 */
private val disposeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

/** 按固定步长推进假进度，上限 0.9。 */
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
