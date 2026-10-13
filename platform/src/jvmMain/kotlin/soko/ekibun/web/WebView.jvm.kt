package soko.ekibun.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.awt.Canvas
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

// 桌面端的 WebView 平台层：上下文工厂 + 原生视图挂载面。
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

/**
 * 桌面端（Windows / WebView2）的 [createWebviewContext] 实现。
 *
 * 建的时候只挑视口尺寸（[isHeadless] 走 1×1）：User-Agent 这些配置由调用方拿到上下文
 * 之后经接口上的 setter 设，首屏地址由调用方自己发 `navigate`。
 */
actual suspend fun createWebviewContext(isHeadless: Boolean): WebviewContext = Webview2Context(isHeadless)

/** 桌面端的可见视图挂载面：`SwingPanel { Canvas() }`，原生窗口经 JAWT `SetParent` 上来。 */
@Composable
internal actual fun WebViewSurface(
  controller: WebviewContext,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val impl = controller as Webview2Context
  // 一个上下文一个宿主组件 —— AWT 组件没法「换一个原生窗口」，上下文换了就换组件。
  val canvas = remember(impl) { WebViewCanvas(impl) }

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
  private val context: Webview2Context,
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
