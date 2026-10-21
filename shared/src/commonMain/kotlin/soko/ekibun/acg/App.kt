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
import soko.ekibun.acg.ui.screen.CollectionScreen
import soko.ekibun.acg.ui.screen.PlayScreen
import soko.ekibun.acg.ui.screen.WebScreen

private enum class AppTab(
  val label: String,
) {
  Play("播放"),
  Web("浏览器"),
  Collect("收藏"),

  // 脚本页签：直接执行插件 JS 的控制台
  Code("脚本"),
}

// 应用入口，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Composable
@Preview
fun App() {
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
        AppTab.Collect -> CollectionScreen(Modifier.fillMaxSize())
        AppTab.Code -> CodeScreen()
      }
    }
  }
}
