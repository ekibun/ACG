package soko.ekibun.acg.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

/**
 * 桌面端的可见 WebView —— 走自研的 WebView2 原生宿主（`cxx/webview/webview.cpp`）。
 *
 * 宿主方式是最朴素的 Win32 那套，**不依赖任何 UI 框架的原生互操作层**：
 *
 * 1. 在 WebView 线程上建一个隐藏的顶层窗口（`nativeCreateView`），拿回它的 HWND；
 * 2. 组合树里放一个 AWT 组件（[WebViewCanvas]，`SwingPanel` 承载）；
 * 3. 组件一拿到 peer（`isDisplayable`），就把 HWND `SetParent` 上去、改成 `WS_CHILD`
 *    （`nativeViewAttach`，父窗口经 JAWT 现取）；
 * 4. 之后尺寸一律以组件客户区为准（`nativeViewFitToParent`）。
 *
 * 因此**窗口后端必须是 AWT 的**（compose.desktop 的 `application { Window(...) }`
 * 就是），JAWT 才有东西可取。
 *
 * 用的是**和后台 WebView 同一个 environment**（同一份 user data folder `NativeWebView.userDataDir`），
 * 因此可见页与后台页共享 cookie / 登录态。
 *
 * ## 一个必须知道的限制
 *
 * AWT 是重型组件，**永远画在 Compose 之上** —— [content] 这个覆盖层插槽在 Windows
 * 上会被 WebView2 盖住，实际只在原生后端没起来（[AcgWebViewState.failure] 里是
 * 「起不来」而非「页面加载失败」）时才看得见。
 */
@Composable
actual fun AcgWebView(
  state: AcgWebViewState,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  var view by remember(state) { mutableStateOf<PlatformView?>(null) }

  LaunchedEffect(state) {
    val started = withContext(Dispatchers.IO) { NativeWebView.ensureStarted() }
    val error = started.exceptionOrNull()
    if (error != null) {
      state.fail(error.message ?: error.toString())
      return@LaunchedEffect
    }
    val handle =
      withContext(Dispatchers.IO) {
        NativeWebView.createView(
          parentHwnd = 0L, // 父窗口在 attach 时经 JAWT 现取，见 WebViewCanvas
          url = state.homeUrl,
          userAgent = state.config.userAgent,
          enableDevtools = state.config.enableDevtools,
          zoom = state.config.zoom,
        )
      }
    if (handle == 0L) {
      state.fail("创建 WebView2 视图失败（宿主窗口没起来，详见日志）")
      return@LaunchedEffect
    }
    view = PlatformView(handle)
  }

  val platformView = view
  // content() 在「后端没起来」和「正常」两条分支里都会用到。同一个槽位跨分支复用会丢
  // 槽内状态，所以用 movableContentOf 搬运；lambda 本身经 rememberUpdatedState 取最新，
  // 免得调用方换了 lambda 之后还在跑旧的。
  val currentContent by rememberUpdatedState(content)
  val movableContent = remember { movableContentOf { currentContent() } }
  if (platformView == null) {
    // 后端不可用（或还没起来）：退回成一个空盒子，让上层自己的提示层露出来。
    Box(modifier) { movableContent() }
    return
  }

  // 一个句柄一个宿主组件 —— AWT 组件没法"换一个原生窗口"，句柄变了就换组件。
  val canvas = remember(platformView) { WebViewCanvas(platformView.handle) }

  DisposableEffect(state, platformView, canvas) {
    state.controller = platformView
    NativeWebView.addViewListener(platformView.handle) { what, a, b ->
      state.onNativeEvent(what, a, b)
    }
    NativeWebView.setViewVisible(platformView.handle, true)
    canvas.startTracking()
    onDispose {
      if (state.controller === platformView) state.controller = null
      canvas.stopTracking()
      // 销毁只在这里做。native 侧销毁窗口时会把子窗口一起带走，不需要先 detach。
      NativeWebView.destroyView(platformView.handle)
    }
  }

  // WebView2 只给「在不在加载」，没有进度。进度条自己插值走出来，视觉上不至于
  // 一直卡在同一个位置。
  LaunchedEffect(state.isLoading) {
    while (state.isLoading) {
      delay(PROGRESS_TICK_MS)
      val current = state.loadingState
      if (current is WebViewLoadingState.Loading) {
        state.loadingState =
          WebViewLoadingState.Loading(
            (current.progress + PROGRESS_STEP).coerceAtMost(MAX_FAKE_PROGRESS),
          )
      }
    }
  }

  Box(modifier) {
    // `SwingPanel` 的 factory 只被记一次，认不出「换了一份句柄」—— 用 key 把
    // 「一份句柄 = 一个组合组」钉死。
    key(platformView) {
      SwingPanel(
        factory = { canvas },
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 见类注释：这一层在 Windows 上会被上面的 AWT 组件盖住。
    movableContent()
  }
}

/**
 * AWT 侧的宿主组件：WebView2 的原生窗口会被 `SetParent` 到它身上。
 *
 * 选 `Canvas` 而不是 `Panel`：轻量、没有子组件，而且**一定有 peer** —— JAWT 要的
 * 就是 peer 的 HWND。
 *
 * 「什么时候能挂」由 AWT 说了算：组件得先 `addNotify` 才有 peer。组合层调
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
 * 挂载与尺寸由 [WebViewCanvas] 管（attach + fitToParent），这里只做导航类的动作。
 */
private class PlatformView(
  val handle: Long,
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

  /**
   * 把焦点交给原生窗口，键盘事件才会进去（native 的 `nativeViewFocus`）。
   *
   * 通常在页面里点一下就由上面那条激活链自动完成了，这个接口留给上层在
   * 「窗口重新激活 / 主动聚焦」这类场景下手动调。
   */
  fun requestFocus() = NativeWebView.focusView(handle)
}

/** 把 native 事件折进 Compose 状态。 */
private fun AcgWebViewState.onNativeEvent(
  what: Int,
  a: String?,
  b: String?,
) {
  when (what) {
    NativeWebView.EVENT_LOADING -> {
      val loading = a == "1"
      isLoading = loading
      loadingState =
        if (loading) {
          WebViewLoadingState.Loading(0.15f)
        } else {
          if (loadingState is WebViewLoadingState.Failed) {
            loadingState
          } else {
            WebViewLoadingState.Finished
          }
        }
    }

    NativeWebView.EVENT_URL -> {
      val url = a?.takeIf { it.isNotBlank() } ?: return
      currentUrl = url
      lastLoadedUrl = url
    }

    NativeWebView.EVENT_TITLE -> {
      val title = a?.takeIf { it.isNotBlank() } ?: return
      pageTitle = title
    }

    NativeWebView.EVENT_HISTORY -> {
      canGoBack = a == "1"
      canGoForward = b == "1"
    }

    NativeWebView.EVENT_FAILED -> fail(a ?: "WebView 失败")
  }
}

private const val PROGRESS_TICK_MS = 120L
private const val PROGRESS_STEP = 0.02f
private const val MAX_FAKE_PROGRESS = 0.9f
