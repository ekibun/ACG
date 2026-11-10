package soko.ekibun.acg.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import soko.ekibun.quickjs.JSError
import soko.ekibun.web.WebViewEvent
import soko.ekibun.web.WebviewContext

/**
 * 任务的页面状态：地址、标题、进度（0..1，1 表示没有加载在进行）。
 *
 * [title] 在页面报出 `<title>` 之前为 `null`，界面回退到 [url]。
 */
data class WebviewPage(
  val url: String,
  val title: String? = null,
  val progress: Float = 1f,
  val canGoBack: Boolean = false,
  val canGoForward: Boolean = false,
)

/** 事件 → 页面状态；其余事件原样返回。 */
internal fun WebviewPage.reduce(event: WebViewEvent): WebviewPage =
  when (event) {
    WebViewEvent.PageStarted -> copy(progress = 0f)
    is WebViewEvent.Progress -> copy(progress = event.fraction.coerceIn(0f, 1f))
    is WebViewEvent.UrlChanged -> copy(url = event.url)
    WebViewEvent.PageFinished -> copy(progress = 1f)
    is WebViewEvent.TitleChanged -> copy(title = event.title.takeIf(String::isNotBlank))
    else -> this
  }

/**
 * 一次进行中的 WebView 任务：后台跑，用户随时可以把它提到前台看一眼。
 *
 * 提到前台只是把同一个实例铺到界面上，脚本判定不变：每次 `PageFinished` 跑一遍，返回非 null 收工。
 */
class WebviewTask internal constructor(
  val url: String,
  /** 用户页面：创建即前台，无后台态，关闭兑现 null。 */
  val foregroundOnly: Boolean = false,
) {
  /** 上下文创建后填入；还没建好的任务不能切前台。 */
  internal var controller: WebviewContext? = null

  internal val outcome = CompletableDeferred<Any?>()

  private val _page = MutableStateFlow(WebviewPage(url = url))

  /** 页面状态，界面订阅它画标题栏。 */
  val page: StateFlow<WebviewPage> = _page.asStateFlow()

  internal fun onEvent(event: WebViewEvent) {
    _page.value = _page.value.reduce(event)
  }

  /** 后台倒计时；挂到前台时取消。 */
  internal var expiry: Job? = null

  val done: Boolean get() = outcome.isCompleted

  /** 主动结束；[reason] 会作为 JS 侧的失败消息。 */
  internal fun fail(reason: String) {
    outcome.completeExceptionally(JSError("$reason: $url"))
  }
}

/**
 * 所有进行中的 WebView 任务。引擎往里登记，界面取出来渲染。
 *
 * 后台可以有多个并行任务，**前台同时只有一个**：同时挂两个实例除了让人分不清该点哪个，没有好处。
 */
object WebviewTasks {
  /** 放到后台多久后强制关闭。挂前台会取消这个倒计时。 */
  private const val BACKGROUND_TIMEOUT_MS = 30_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _all = MutableStateFlow<List<WebviewTask>>(emptyList())

  /** 所有进行中的任务，按登记顺序。 */
  val all: StateFlow<List<WebviewTask>> = _all.asStateFlow()

  private val _foreground = MutableStateFlow<WebviewTask?>(null)

  /** 当前铺在界面上的那一个；null 表示都在后台。 */
  val foreground: StateFlow<WebviewTask?> = _foreground.asStateFlow()

  internal fun register(task: WebviewTask) {
    _all.value = _all.value + task
  }

  internal fun unregister(task: WebviewTask) {
    task.expiry?.cancel()
    task.expiry = null
    if (_foreground.value === task) _foreground.value = null
    _all.value = _all.value - task
  }

  /** 提到前台；被已有用户页面挡住时返回 false，调用方按失败处理。 */
  fun toForeground(task: WebviewTask): Boolean {
    if (task.done) return false
    val current = _foreground.value
    // 前台任务在显示期间独占：它正等着用户当场操作，不能被后来者顶掉
    if (current != null && current !== task && current.foregroundOnly) return false
    task.expiry?.cancel()
    task.expiry = null
    // 先把上一个按回后台，前台只留一个
    current?.takeIf { it !== task }?.let(::toBackground)
    _foreground.value = task
    scope.launch { runCatching { task.controller?.setVisible(true) } }
    return true
  }

  /** 按回后台：开始 30 秒倒计时，到点强制关闭。前台任务（含用户页面）没这个动作。 */
  fun toBackground(task: WebviewTask) {
    if (task.foregroundOnly) return
    if (_foreground.value === task) _foreground.value = null
    scope.launch { runCatching { task.controller?.setVisible(false) } }
    task.expiry?.cancel()
    task.expiry =
      scope.launch {
        delay(BACKGROUND_TIMEOUT_MS)
        task.fail("webview task closed after ${BACKGROUND_TIMEOUT_MS / 1000}s in background")
      }
  }

  /** 刷新当前页。 */
  fun reload(task: WebviewTask) {
    scope.launch { runCatching { task.controller?.reload() } }
  }

  /** 用户点关闭：Promise 正常兑现成 null，不是失败。 */
  fun close(task: WebviewTask) {
    unregister(task)
    task.outcome.complete(null)
    scope.launch { runCatching { task.controller?.close() } }
  }

  /** 页面内后退；没有历史返回 false，由调用方决定关掉还是交给平台。 */
  fun back(task: WebviewTask): Boolean {
    if (!task.page.value.canGoBack) return false
    scope.launch { runCatching { task.controller?.goBack() } }
    return true
  }

  /** 页面内前进。 */
  fun forward(task: WebviewTask) {
    if (!task.page.value.canGoForward) return
    scope.launch { runCatching { task.controller?.goForward() } }
  }
}
