package soko.ekibun.acg.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import soko.ekibun.common.Paths
import java.io.File

/**
 * 手动验证窗口：`DebugPlayScreen` / `DebugWebScreen` / `DebugCodeScreen` 的 `main` 共用。
 *
 * 必须是真 AWT 窗口 —— `SwingPanel` 与 JAWT `SetParent` 要挂 Win32 子窗口，离屏场景
 * （`ImageComposeScene`）没有父窗口可挂。数据目录落 `build/debug`（`-Dacg.debug.dir` 可改），
 * 让设置与 WebView2 的 user data folder 在多次启动间保留。
 */
internal fun debugApplication(
  title: String,
  size: DpSize = DpSize(1100.dp, 760.dp),
  content: @Composable () -> Unit,
) {
  initDebugPaths()
  application {
    val windowState = rememberWindowState(size = size, position = WindowPosition(Alignment.Center))
    Window(onCloseRequest = ::exitApplication, state = windowState, title = title) {
      MaterialTheme { content() }
    }
  }
}

/** 一个进程只起一个窗口，所以 `Paths.init` 只调一次。 */
private fun initDebugPaths() {
  val root = File(System.getProperty("acg.debug.dir") ?: "shared/build/debug").apply { mkdirs() }
  val data = root.resolve("data").apply { mkdirs() }
  val cache = root.resolve("cache").apply { mkdirs() }
  Paths.init(Paths(dataDir = data.path, cacheDir = cache.path))
}
