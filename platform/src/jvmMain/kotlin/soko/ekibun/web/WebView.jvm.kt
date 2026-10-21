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

// 桌面端 WebView 平台层：上下文工厂与原生视图挂载面。
// [Webview2Context] 建隐藏顶层窗口并起消息泵线程；组合树里放 AWT 组件，组件拿到 peer 后
// 经 JAWT 取 HWND，`SetParent` 上来并改 `WS_CHILD`；尺寸以组件客户区为准。

/** 桌面端的 [createWebviewContext] 实现；[isHeadless] 时用 1×1 视口。 */
actual suspend fun createWebviewContext(isHeadless: Boolean): WebviewContext = Webview2Context(isHeadless)

/** 桌面端的可见视图挂载面：`SwingPanel { Canvas() }`，原生窗口经 JAWT `SetParent` 上来。 */
@Composable
internal actual fun WebViewSurface(
  controller: WebviewContext,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val impl = controller as Webview2Context
  // 一份上下文对应一个 AWT 组件
  val canvas = remember(impl) { WebViewCanvas(impl) }

  DisposableEffect(impl, canvas) {
    canvas.startTracking()
    onDispose { canvas.stopTracking() }
  }

  Box(modifier) {
    // 按上下文分组，保证换了句柄就换组件
    key(impl) {
      SwingPanel(
        factory = { canvas },
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 覆盖层画在原生组件之上
    content()
  }
}

/**
 * AWT 侧的宿主组件：WebView2 的原生窗口 `SetParent` 到它身上。
 * 等 AWT 发出 `componentShown` / `componentResized` 后反复尝试 attach，attach 幂等。
 */
private class WebViewCanvas(
  private val context: Webview2Context,
) : Canvas() {
  init {
    // 不透明底色，attach 前不露出后面的 Compose 内容
    background = Color(0x1E, 0x1E, 0x1E)
    isFocusable = true
  }

  private val componentListener =
    object : ComponentAdapter() {
      override fun componentShown(e: ComponentEvent) = attachIfPossible()

      override fun componentResized(e: ComponentEvent) {
        attachIfPossible()
        // 未 attach 时 native 侧忽略
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
