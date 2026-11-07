package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import soko.ekibun.DevStrip
import soko.ekibun.acg.ui.comp.player.PlayerControls
import soko.ekibun.acg.ui.comp.player.PlayerSurface
import soko.ekibun.acg.ui.comp.player.rememberPlayerController
import soko.ekibun.acg.ui.debugApplication
import soko.ekibun.startDevServer

/** dev 服务器上的夹广告 HLS 样本（端口由 [startDevServer] 现给，这里只留路径）。 */
private const val SAMPLE_PATH = "media/hls-ad/playlist.m3u8"

// 播放验证窗口，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Preview(showBackground = true)
@Composable
fun DebugPlayScreen(initialUrl: String = "") {
  var url by remember { mutableStateOf(initialUrl) }
  val controller = rememberPlayerController()
  val scope = rememberCoroutineScope()

  Surface {
    Column {
      TextField(
        modifier = Modifier.fillMaxWidth(),
        maxLines = 1,
        trailingIcon = {
          TextButton(
            enabled = url.isNotBlank(),
            // 同一地址再点一次也是重连
            onClick = { scope.launch { controller.open(url) } },
          ) {
            Text("play>")
          }
        },
        value = url,
        onValueChange = { url = it },
      )
      PlayerSurface(
        controller = controller,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
      PlayerControls(
        controller = controller,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

// 手动验证入口：进程内起 dev 服务器（端口由系统给），用它拼默认地址、并把控制条贴到窗口上。
fun main() {
  val port = startDevServer()?.port
  if (port == null) System.err.println("[dev] no dev media server; the default url is empty")
  debugApplication("播放", onWindow = { window -> port?.let { DevStrip.attach(window, it.toString()) } }) {
    DebugPlayScreen(initialUrl = port?.let { "http://127.0.0.1:$it/$SAMPLE_PATH" } ?: "")
  }
}
