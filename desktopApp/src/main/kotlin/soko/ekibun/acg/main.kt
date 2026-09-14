package soko.ekibun.acg

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.WindowScaffold
import soko.ekibun.acg.web.BackgroundWebViewHost

/**
 * 桌面入口。
 *
 * 三个关键点：
 *
 * 1. 用 Nucleus 的 Tao 后端而不是标准的 `application {}`。ComposeNativeWebview 的
 *    桌面实现通过 `LocalTaoWindow.current.nativeHandle` 取宿主 HWND 才能创建真正的
 *    WebView2；标准 AWT 后端拿不到窗口句柄，WebView 会静默降级成空白块。
 *
 * 2. Tao 的窗口是**无系统装饰**的（CSD），标题栏不会自动出现，必须自己放。
 *    这里用官方推荐的 `WindowScaffold` + `TitleBar`：它负责量标题栏高度、把
 *    caption 区推给原生 WndProc、给内容留内边距，`TitleBar` 则带上
 *    Windows 的最小化/最大化/关闭按钮与拖动区（双击标题栏 = 最大化）。
 *
 * 3. 插件 JS 的「后台 WebView」需要一个**不显示**的窗口当宿主 —— 见下面第二个
 *    `DecoratedWindow`。
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

    // 后台 WebView 的宿主窗口：**故意不显示**。
    //
    // 桌面端没有真正的无头 WebView —— WebView2 要一个真实的父 HWND，
    // 而库把 `put_IsVisible(TRUE)` 写死在创建流程里，也没有「设成 0 尺寸」这条退路
    // （`nativeSetBounds` 会把宽高 clamp 到至少 1px）。所以「后台」唯一的做法就是
    // 给一个不显示的宿主窗口：不参与合成、不出现在任务栏，但 JS、cookie、
    // evaluateJavaScript 全都照跑。
    //
    // 拿不到这个窗口的后果不是报错，而是静默降级成空壳 —— 所以
    // `loadBackgroundWebView` 会明确失败并告诉调用方。
    DecoratedWindow(
        onCloseRequest = {},
        state = rememberWindowState(size = DpSize(1024.dp, 768.dp)),
        visible = false,
        title = "ACG background webview",
    ) {
        BackgroundWebViewHost()
    }
}
