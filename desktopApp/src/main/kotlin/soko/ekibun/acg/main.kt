package soko.ekibun.acg

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import soko.ekibun.common.Paths
import java.awt.Dimension

/**
 * 桌面入口。
 *
 * 用**标准的** compose.desktop `application { Window(...) }`，窗口装饰交回系统 —— 不是随便选的：可见
 * WebView 是个真的 Win32 子窗口，宿主方式是「`SwingPanel` 放一个 AWT `Canvas`，把原生窗口经 JAWT 取到
 * Canvas 的 HWND 后 `SetParent` 进去」（见 `soko.ekibun.web.WebView` 的 jvm actual），而 JAWT 只在 AWT
 * 后端有东西可取，所以窗口必须是 AWT 的。
 *
 * 插件 JS 的「后台 WebView」**不需要额外窗口**：`cxx/webview` 宿主自己建隐藏窗口 + 自己的消息泵，调用
 * 时才创建（宿主线程与 environment 进程内共享，每个上下文只开自己的窗口）。
 *
 * 用户数据根（设置文件与 WebView2 的 user data folder）在这里注入一次，平台层只按 [Paths] 取路径。
 * 可见页与后台页共用同一份，登录态天然共享。
 */
fun main(args: Array<String>) {
  // APPDATA / LOCALAPPDATA 是环境变量，不是系统属性；取不到就当场炸，
  // 免得拼出 "null/ekibun/ACG" 这种路径。
  val roamingAppData = System.getenv("APPDATA") ?: error("APPDATA is not set")
  val localAppData = System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is not set")
  Paths.init(
    Paths(
      dataDir = "$roamingAppData/ekibun/ACG",
      cacheDir = "$localAppData/ekibun/ACG",
    ),
  )
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
