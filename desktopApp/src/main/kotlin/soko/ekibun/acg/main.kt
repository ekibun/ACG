package soko.ekibun.acg

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import soko.ekibun.web.NativeWebView
import java.awt.Dimension
import java.io.File

/**
 * 桌面入口。
 *
 * 用**标准的** compose.desktop `application { Window(...) }`，窗口装饰交回系统 ——
 * 这一点不是随便选的：可见 WebView 是个真的 Win32 子窗口，宿主方式是
 * 「`SwingPanel` 放一个 AWT `Canvas`，再把原生窗口经 JAWT 取到 Canvas 的 HWND 后
 * `SetParent` 进去」（见 `soko.ekibun.web.WebView` 的 jvm actual）。
 * JAWT 只在 AWT 后端有东西可取，所以窗口必须是 AWT 的。
 *
 * 插件 JS 的「后台 WebView」**不需要额外窗口**：自研的 `cxx/webview` 宿主自己建
 * 隐藏窗口 + 自己的消息泵。`App()` 里的 `BackgroundWebViewHost()` 只是预热。
 */
fun main(args: Array<String>) {
  // WebView2 的 user data folder（cookie / 登录态）：App 启动时显式传入，对齐
  // Android 由 App 提供 `Context` 的模式 —— 目录是 App 层的决定，web 组件不自己猜。
  // LOCALAPPDATA 取不到时不设置，web 组件走它自己的回退链。
  System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let {
    NativeWebView.setUserDataDir(File(it, "ACG/webview2"))
  }
  application {
    Window(
      onCloseRequest = ::exitApplication,
      state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
      title = "ACG",
    ) {
      // compose.desktop 的 `Window` 没有 minimumSize 参数，直接在 ComposeWindow 上设。
      LaunchedEffect(Unit) {
        window.minimumSize = Dimension(720, 480)
      }
      App()
    }
  }
}
