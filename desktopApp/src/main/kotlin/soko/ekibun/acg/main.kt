package soko.ekibun.acg

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.WindowScaffold

/**
 * 桌面入口。
 *
 * 两个关键点：
 *
 * 1. 用 Nucleus 的 Tao 后端而不是标准的 `application {}`。ComposeNativeWebview 的
 *    桌面实现通过 `LocalTaoWindow.current.nativeHandle` 取宿主 HWND 才能创建真正的
 *    WebView2；标准 AWT 后端拿不到窗口句柄，WebView 会静默降级成空白块。
 *
 * 2. Tao 的窗口是**无系统装饰**的（CSD），标题栏不会自动出现，必须自己放。
 *    这里用官方推荐的 `WindowScaffold` + `TitleBar`：它负责量标题栏高度、把
 *    caption 区推给原生 WndProc、给内容留内边距，`TitleBar` 则带上
 *    Windows 的最小化/最大化/关闭按钮与拖动区（双击标题栏 = 最大化）。
 */
fun main(args: Array<String>) = nucleusApplication(args, backend = NucleusBackend.Tao) {
    DecoratedWindow(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
        title = "ACG",
        minimumSize = DpSize(720.dp, 480.dp),
    ) {
        // titleBar 槽的 receiver 是空的，所以要用窗口作用域去调 TitleBar。
        val windowScope = this
        WindowScaffold(
            titleBar = { windowScope.TitleBar() },
        ) {
            App()
        }
    }
}
