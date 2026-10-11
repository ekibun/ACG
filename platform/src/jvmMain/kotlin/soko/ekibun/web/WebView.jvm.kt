package soko.ekibun.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Canvas
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

// 桌面端的 WebView 平台层：控制器工厂（可见）+ 原生视图挂载面。
//
// 宿主方式是最朴素的 Win32 那套，**不依赖任何 UI 框架的原生互操作层**：
//
// 1. [Webview2Context] 建一个隐藏的顶层窗口，并起一条自己的消息泵线程；
// 2. 组合树里放一个 AWT 组件（[WebViewCanvas]，`SwingPanel` 承载）；
// 3. 组件一拿到 peer（`isDisplayable`），就经 JAWT 取它的 HWND，把宿主窗口
//    `SetParent` 上去、改成 `WS_CHILD`（[Webview2Context.attach]）；
// 4. 之后尺寸一律以组件客户区为准（[Webview2Context.fitToParent]）。
//
// 因此**窗口后端必须是 AWT 的**（compose.desktop 的 `application { Window(...) }`
// 就是），JAWT 才有东西可取。
//
// cookie 靠 **user data folder** 统一，而 environment 干脆是进程内共享的（同一
// user data folder 上不允许并存多个 environment，native 侧因此只建一个、引用计数）：
// 可见页与后台页是同一个 environment 下的两个窗口，登录态天然就是同一套。
//
// 状态折算、假进度插值、生命周期兜底都在 commonMain；这里只发 [WebViewEvent]。

/** 桌面端（Windows / WebView2）的 [createWebViewController] 实现。 */
internal actual suspend fun createWebViewController(
  headless: Boolean,
  config: WebViewConfig,
  initialUrl: String,
  initialHeaders: Map<String, String>,
  script: String?,
  onRequest: ((WebViewRequest) -> WebViewInterception?)?,
  onEvent: (WebViewEvent) -> Unit,
): WebViewController {
  if (headless) {
    return createDesktopHeadlessController(initialUrl, initialHeaders, script, onRequest, onEvent)
  }
  val ctx = WebviewContext()
  // 监听要挂在 `start()` 之前：那之后宿主线程随时可能把事件打过来。
  ctx.listener = DesktopEventListener(onEvent)
  try {
//    ctx.start()
  } catch (t: Throwable) {
    closeContext(ctx)
    throw t
  }
  // 用户代理是「建一次」的配置，控制器就绪之后才设得进去。可见页不拦请求，
  // 所以 [onRequest] 在这里用不上。
  config.userAgent?.let { ctx.setUserAgent(it) }
  val controller = DesktopWebViewController(ctx)
  // 可见视图的初始导航不带自定义头：带头那条路是控制器就绪之后的 `loadUrl`。
  controller.loadUrl(initialUrl, emptyMap())
  return controller
}

/** 桌面端的可见视图挂载面：`SwingPanel { Canvas() }`，原生窗口经 JAWT `SetParent` 上来。 */
@Composable
internal actual fun WebViewSurface(
  controller: WebViewController,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val impl = controller as DesktopWebViewController
  // 一个上下文一个宿主组件 —— AWT 组件没法「换一个原生窗口」，上下文换了就换组件。
  val canvas = remember(impl) { WebViewCanvas(impl.context) }

  DisposableEffect(impl, canvas) {
    canvas.startTracking()
    onDispose { canvas.stopTracking() }
  }

  Box(modifier) {
    // `SwingPanel` 的 factory 只被记一次，认不出「换了一份句柄」—— 用 key 把
    // 「一份上下文 = 一个组合组」钉死。
    key(impl) {
      SwingPanel(
        factory = { canvas },
        modifier = Modifier.fillMaxSize(),
      )
    }
    // AWT 是重型组件，永远画在 Compose 之上：这层覆盖只在后端起不来时看得见。
    content()
  }
}

/**
 * AWT 侧的宿主组件：WebView2 的原生窗口会被 `SetParent` 到它身上。
 *
 * 选 `Canvas` 而不是 `Panel`：轻量、没有子组件，而且**一定有 peer** —— JAWT 要的
 * 就是 peer 的 HWND。
 *
 * 「什么时候挂」由 AWT 说了算：组件得先 `addNotify` 才有 peer。组合层调
 * [startTracking] 时多半还没到那一刻，所以顺手挂上监听，等 AWT 发
 * `componentShown` / `componentResized` 再试。attach 是幂等的，多来几次无妨。
 */
private class WebViewCanvas(
  private val context: WebviewContext,
) : Canvas() {
  init {
    // 不透明底色：attach 之前（以及原生窗口还没跟上尺寸的那一两帧）不会露出
    // 后面的 Compose 内容 —— 重型子窗口的合成顺序不保证。
    background = Color(0x1E, 0x1E, 0x1E)
    isFocusable = true
  }

  private val componentListener =
    object : ComponentAdapter() {
      override fun componentShown(e: ComponentEvent) = attachIfPossible()

      override fun componentResized(e: ComponentEvent) {
        attachIfPossible()
        // 尺寸变了就重新贴合；还没 attach 的话 native 侧直接忽略。
        context.fitToParent()
      }
    }

  fun startTracking() {
    addComponentListener(componentListener)
  }

  fun stopTracking() {
    removeComponentListener(componentListener)
  }

  private fun attachIfPossible() {
    if (!isDisplayable) return
    context.attach(this)
  }
}

/**
 * [WebViewController] 的桌面实现 —— [WebviewContext] 的一层薄包装。
 *
 * 命令直通上下文；宿主事件由 [DesktopEventListener] 折成 [WebViewEvent]。挂载与
 * 尺寸由 [WebViewCanvas] 管（attach + fitToParent）。后台任务的控制器
 * （`BackgroundWebView.jvm.kt`）按对象委托复用它，所以是 `internal` 而不是 `private`。
 */
internal class DesktopWebViewController(
  internal val context: WebviewContext,
) : WebViewController {
  /** 那些**非挂起**签名（`loadUrl`）发起的挂起动作挂这里；[dispose] 一并取消。 */
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  override fun loadUrl(
    url: String,
    headers: Map<String, String>,
  ) {
    scope.launch { runCatching { context.navigate(url, "GET", headers) } }
  }

  override fun reload() = context.reload()

  override fun stop() = context.stop()

  override fun goBack() = context.goBack()

  override fun goForward() = context.goForward()

  override fun openDevTools() = context.openDevTools()

  /**
   * 在**用户正在看的这一页**上执行脚本并拿回结果。
   *
   * 后台任务（`webview(url, {}, script)`）能取数靠的是同一个 `ExecuteScript`，
   * 但它是另开一个隐藏窗口把页面重抓一遍 —— 这里不用：直接在当前这页上跑，
   * 既省一次网络请求，也能拿到只有交互之后才存在的东西（滚动位置、展开的节点、
   * 临时登录态）。
   *
   * 结果语义与后台任务一致：没有返回值或失败都是 `null`。
   */
  override suspend fun evaluate(script: String): String? = context.executeScript(script)

  /** 幂等：`Pointer.markClosed` 抢占，重复的 `close()` 只有第一次真的归还。 */
  override fun dispose() {
    scope.cancel()
    closeContext(context)
  }
}

/**
 * 把 native 的事件字符串折成 [WebViewEvent]（折算规则只有 commonMain 一份，这里
 * 只做**名字 → 事件对象**的翻译）。
 */
private class DesktopEventListener(
  private val onEvent: (WebViewEvent) -> Unit,
) : WebviewContext.Listener {
  override fun onEvent(
    event: String,
    data: String,
  ) {
    when (event) {
      "navigationStarting" -> onEvent(WebViewEvent.PageStarted)
      "urlChanged" -> data.takeIf { it.isNotBlank() }?.let { onEvent(WebViewEvent.UrlChanged(it)) }
      "documentLoaded" -> data.takeIf { it.isNotBlank() }?.let { onEvent(WebViewEvent.DocumentLoaded(it)) }
      "historyChanged" -> {
        // data 是 "canGoBack canGoForward"，两个都是 "1" / "0"。
        val parts = data.split(' ')
        onEvent(
          WebViewEvent.HistoryChanged(
            canGoBack = parts.getOrNull(0) == "1",
            canGoForward = parts.getOrNull(1) == "1",
          ),
        )
      }

      "navigationCompleted" -> onEvent(WebViewEvent.PageFinished)
      "navigationFailed" -> {
        val detail = data.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
        onEvent(WebViewEvent.LoadFailed("页面加载失败$detail"))
      }
      "documentTitleChanged" -> data.takeIf { it.isNotBlank() }?.let { onEvent(WebViewEvent.TitleChanged(it)) }
      "processFailed" -> onEvent(WebViewEvent.LoadFailed("WebView 进程崩溃（$data）"))
    }
  }
}

/**
 * 归还上下文 —— 一律丢到 IO 上做。
 *
 * `releaseContext` 里有几件会阻塞的事（等 controller 拆掉、清 native 侧的待办队列），
 * 而调用点多半在 AWT 的 EDT / 组合线程上（`onDispose`、后台任务的 `finally`），
 * 不能钉住。丢到 IO 上之后它与「下一个上下文建起来」是并发的，所以 native 侧的
 * 析构得自己把顺序理对（见 `cxx/webview/webview.cpp` 的 `~WebviewContext`）。
 */
internal fun closeContext(ctx: WebviewContext) {
  disposeScope.launch { runCatching { ctx.close() } }
}

private val disposeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
