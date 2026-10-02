package soko.ekibun.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

// 桌面端的 WebView 平台层：控制器工厂（可见）+ 原生视图挂载面。
//
// 宿主方式是最朴素的 Win32 那套，**不依赖任何 UI 框架的原生互操作层**：
//
// 1. 在 WebView 线程上建一个隐藏的顶层窗口（`nativeCreateView`），拿回它的 HWND；
// 2. 组合树里放一个 AWT 组件（[WebViewCanvas]，`SwingPanel` 承载）；
// 3. 组件一拿到 peer（`isDisplayable`），就把 HWND `SetParent` 上去、改成 `WS_CHILD`
//    （`nativeViewAttach`，父窗口经 JAWT 现取）；
// 4. 之后尺寸一律以组件客户区为准（`nativeViewFitToParent`）。
//
// 因此**窗口后端必须是 AWT 的**（compose.desktop 的 `application { Window(...) }`
// 就是），JAWT 才有东西可取。
//
// 用的是**和后台 WebView 同一个 environment**（同一份 user data folder `NativeWebView.userDataDir`），
// 因此可见页与后台页共享 cookie / 登录态。
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
  // 可见视图的初始导航不带自定义头：带头那条路在控制器就绪后的 `loadUrl`
  // （`nativeViewLoadUrl`），建视图时没有传 header 的入口，`initialHeaders` 用不上。
  val started = withContext(Dispatchers.IO) { NativeWebView.ensureStarted() }
  started.exceptionOrNull()?.let {
    throw IllegalStateException(it.message ?: it.toString(), it)
  }
  val handle =
    withContext(Dispatchers.IO) {
      NativeWebView.createView(
        url = initialUrl,
        userAgent = config.userAgent,
        enableDevtools = config.enableDevtools,
        zoom = config.zoom,
      )
    }
  if (handle == 0L) {
    throw IllegalStateException("创建 WebView2 视图失败（宿主窗口没起来，详见日志）")
  }
  val controller = DesktopWebViewController(handle, onEvent)
  // 注册要趁早：注册前的早期事件由 callbacks.pending 攒着补发（建视图和注册之间
  // 有个很窄的窗口）。
  NativeWebView.addViewListener(handle) { what, a, b -> controller.onNative(what, a, b) }
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
  // 一个句柄一个宿主组件 —— AWT 组件没法"换一个原生窗口"，句柄变了就换组件。
  val canvas = remember(impl) { WebViewCanvas(impl.handle) }

  DisposableEffect(impl, canvas) {
    NativeWebView.setViewVisible(impl.handle, true)
    canvas.startTracking()
    onDispose { canvas.stopTracking() }
  }

  Box(modifier) {
    // `SwingPanel` 的 factory 只被记一次，认不出「换了一份句柄」—— 用 key 把
    // 「一份句柄 = 一个组合组」钉死。
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
  private val handle: Long,
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
        NativeWebView.fitViewToParent(handle)
      }
    }

  /**
   * 键盘焦点走进 AWT 组件时，转交给原生窗口。
   *
   * 这只是**兜底**：落在页面里的鼠标点击到不了 AWT（WebView2 的子窗口是跨进程的），
   * 「点一下页面就能打字」靠的是原生侧的激活链 —— 输入队列在 `nativeViewAttach` 里
   * 接到 AWT 线程 → 点击激活 `SunAwtFrame` → AWT 把焦点派回 Canvas（宿主）→ 宿主窗口
   * 收到 `WM_SETFOCUS` → `requestWebViewFocus`（见 `cxx/webview/webview.cpp` 的
   * `wndProc`；原来的 DOM 桥 2026-09-15 已整套删除）。
   * 这条监听管的是另一半：AWT 自己把焦点给了这个组件（比如窗口激活、Tab 走过来）时
   * 也得把焦点送进 WebView，别停在 Canvas 上。
   */
  private val focusListener =
    object : FocusAdapter() {
      override fun focusGained(e: FocusEvent) = NativeWebView.focusView(handle)
    }

  fun startTracking() {
    addComponentListener(componentListener)
    addFocusListener(focusListener)
    attachIfPossible()
  }

  fun stopTracking() {
    removeComponentListener(componentListener)
    removeFocusListener(focusListener)
  }

  private fun attachIfPossible() {
    if (!isDisplayable) return
    NativeWebView.attachView(handle, this)
  }
}

/**
 * [WebViewController] 的桌面实现 —— 只是 native 句柄的一层薄包装。
 *
 * 命令直通 [NativeWebView]；平台事件经 [onNative] 折算成 [WebViewEvent] 交给
 * 创建时指定的接收方。挂载与尺寸由 [WebViewCanvas] 管（attach + fitToParent）。
 * background 任务的控制器（`BackgroundWebView.jvm.kt`）按对象委托复用它，所以是
 * `internal` 而不是 `private`。
 */
internal class DesktopWebViewController(
  val handle: Long,
  private val onEvent: (WebViewEvent) -> Unit,
) : WebViewController {
  override fun loadUrl(
    url: String,
    headers: Map<String, String>,
  ) = NativeWebView.loadUrl(handle, url, headers)

  override fun reload() = NativeWebView.viewAction(handle, NativeWebView.ACTION_RELOAD)

  override fun stop() = NativeWebView.viewAction(handle, NativeWebView.ACTION_STOP)

  override fun goBack() = NativeWebView.viewAction(handle, NativeWebView.ACTION_BACK)

  override fun goForward() = NativeWebView.viewAction(handle, NativeWebView.ACTION_FORWARD)

  override fun openDevTools() = NativeWebView.viewAction(handle, NativeWebView.ACTION_DEVTOOLS)

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
  override suspend fun evaluate(script: String): String? = NativeWebView.evaluateScript(handle, script)

  /** 幂等：`destroyView` 只认还登记在 `liveViews` 里的句柄，重复调用无副作用。 */
  override fun dispose() = NativeWebView.destroyView(handle)

  /** 把 native 事件（`NativeWebView.EVENT_*`）折算成 [WebViewEvent]。 */
  internal fun onNative(
    what: Int,
    a: String?,
    b: String?,
  ) {
    when (what) {
      NativeWebView.EVENT_LOADING ->
        if (a == "1") {
          onEvent(WebViewEvent.PageStarted)
        } else {
          onEvent(WebViewEvent.PageFinished)
        }

      NativeWebView.EVENT_URL ->
        a?.takeIf { it.isNotBlank() }?.let { onEvent(WebViewEvent.DocumentLoaded(it)) }

      NativeWebView.EVENT_TITLE ->
        a?.takeIf { it.isNotBlank() }?.let { onEvent(WebViewEvent.TitleChanged(it)) }

      NativeWebView.EVENT_HISTORY ->
        onEvent(WebViewEvent.HistoryChanged(canGoBack = a == "1", canGoForward = b == "1"))

      NativeWebView.EVENT_FAILED -> onEvent(WebViewEvent.LoadFailed(a ?: "WebView 失败"))
    }
  }
}
