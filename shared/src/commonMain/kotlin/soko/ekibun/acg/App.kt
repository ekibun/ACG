package soko.ekibun.acg

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import soko.ekibun.acg.ui.screen.CodeScreen
import soko.ekibun.acg.ui.screen.PlayScreen
import soko.ekibun.acg.ui.screen.WebScreen
import soko.ekibun.acg.web.BackgroundWebViewHost

private enum class AppTab(val label: String) {
    Play("播放"),
    Web("浏览器"),
    // 一个能直接执行插件 JS 的控制台 —— 后台 WebView 这些能力只有从 JS 里
    // 才够得着，没有这个页签就没法在应用内验证。
    Code("脚本"),
}

@Composable
@Preview
fun App() {
    // 插件 JS 的「后台 WebView」的宿主钩子：Android 上顺手记下 Context，
    // 桌面端预热 WebView 环境。挂在 App 上，两端入口都不用各自记着调。
    BackgroundWebViewHost()

    MaterialTheme {
        var selected by remember { mutableIntStateOf(0) }
        val tabs = AppTab.entries

        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selected,
                        onClick = { selected = index },
                        text = { Text(tab.label) },
                    )
                }
            }

            when (tabs[selected]) {
                AppTab.Play -> PlayScreen()
                AppTab.Web -> WebScreen(Modifier.fillMaxSize())
                AppTab.Code -> CodeScreen()
            }
        }
    }
}
