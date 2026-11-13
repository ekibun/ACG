package soko.ekibun.acg

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import soko.ekibun.acg.data.SettingsRepository
import soko.ekibun.acg.ui.comp.ActiveInputSource
import soko.ekibun.acg.ui.comp.LocalActiveInputSource
import soko.ekibun.acg.ui.comp.trackActiveInputSource
import soko.ekibun.acg.ui.comp.webview.WebviewTaskHost
import soko.ekibun.acg.ui.nav.AppNavigator
import soko.ekibun.acg.ui.nav.AppRoute
import soko.ekibun.acg.ui.nav.BackHandler
import soko.ekibun.acg.ui.screen.home.HomeScreen
import soko.ekibun.acg.ui.screen.settings.SettingsScreen
import soko.ekibun.acg.ui.theme.AppTheme

// 应用入口，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Composable
@Preview
fun App() {
  // 设置已在两端入口预读过，这里的初值就是磁盘上那份，不会先闪一下默认主题
  val settings by SettingsRepository.settings.collectAsState()
  // 输入方式全应用一份：窗口根上看一次指针是鼠标还是手指，谁要按它区分手势都读这里
  val inputSource = remember { ActiveInputSource() }
  AppTheme(settings = settings.theme) {
    CompositionLocalProvider(LocalActiveInputSource provides inputSource) {
      // Surface 铺满整窗，让系统栏背后的底色跟着主题走；不加它那两条会是窗口默认白底。
      Surface(
        Modifier.fillMaxSize().trackActiveInputSource(inputSource),
        color = MaterialTheme.colorScheme.surface,
      ) {
        // 导航挂在窗口根上：栈空是主页，非空是替换成压在上面的独立页面。主页只管自己的 tab。
        val navigator = remember { AppNavigator() }
        // 返回先给页面（tab、弹层），再给这里，最后是平台默认；主页的 handler 后组合，排在前面。
        BackHandler(enabled = navigator.current != null) { navigator.back() }
        // 同一时刻只组合一个页面，被换下去的那个靠 saveable 状态原地复活 —— 从设置返回时 tab 不回到默认
        val pages = rememberSaveableStateHolder()
        when (val route = navigator.current) {
          null ->
            pages.SaveableStateProvider("home") {
              HomeScreen(onNavigate = navigator::open)
            }

          AppRoute.Settings ->
            pages.SaveableStateProvider(route.key) {
              SettingsScreen(onBack = { navigator.back() })
            }
        }

        // 插件发起的网页任务：全应用唯一的一份宿主，别在页面里再放一个 —— 两个实例会争同一个 controller。
        // 系统栏由各页的 Scaffold 内缩，这层覆盖物不在它们里面，得自己避。
        WebviewTaskHost(Modifier.windowInsetsPadding(WindowInsets.systemBars))
      }
    }
  }
}
