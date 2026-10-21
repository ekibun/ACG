package soko.ekibun

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.Timer

/** dev 控制条：无标题栏长条，贴在应用主窗口右上角，按钮与状态轮询走 `/__control` 端点。 */
object DevStrip {
  @JvmStatic
  @OptIn(ExperimentalComposeUiApi::class)
  fun attach(
    owner: Window,
    port: String,
  ) {
    val strip = ComposeDialog(owner)
    // 不抢焦点
    strip.focusableWindowState = false
    strip.isUndecorated = true
    // undecorated 下窗口有一圈不可见的边缘拖拽区
    strip.isResizable = false
    strip.isTransparent = true

    var status by mutableStateOf("…")

    fun send(action: String) {
      status =
        runCatching {
          val conn =
            URI("http://127.0.0.1:$port/__control/$action").toURL().openConnection() as HttpURLConnection
          conn.connectTimeout = 2_000
          conn.readTimeout = 2_000
          conn.inputStream
            .bufferedReader()
            .readText()
            .trim()
            .also { conn.disconnect() }
        }.getOrElse { "service not responding ($action)" }
    }

    val poller = Timer(1_000) { send("status") }

    strip.setContent {
      Row(
        Modifier
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF2B2D30))
          .border(1.dp, Color(0xFF5E6060), RoundedCornerShape(8.dp))
          .width(600.dp)
          .height(36.dp)
          .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("HttpServer:$port", color = Color(0xFF9CDCFE), fontSize = 12.sp)
        Action("Freeze") { send("freeze") }
        Action("Resume") { send("resume") }
        Action("Throttle") { send("throttle?bps=30000") }
        Action("Unthrottle") { send("throttle?bps=0") }
        // 定宽 + 单行截断：文本变化不改布局尺寸
        Text(
          status,
          Modifier.weight(1f),
          color = Color(0xFF6A9955),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    strip.pack()

    fun follow() {
      strip.pack()
      strip.setLocation(owner.x + owner.width - strip.width - 12, owner.y - strip.height - 12)
    }

    owner.addComponentListener(
      object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = follow()

        override fun componentResized(e: ComponentEvent) = follow()
      },
    )
    owner.addWindowListener(
      object : WindowAdapter() {
        override fun windowClosed(e: WindowEvent) {
          poller.stop()
          strip.dispose()
        }

        override fun windowIconified(e: WindowEvent) {
          strip.isVisible = false
        }

        override fun windowDeiconified(e: WindowEvent) {
          strip.isVisible = true
        }
      },
    )
    follow()
    strip.isVisible = true
    poller.start()
  }

  @Composable
  private fun Action(
    text: String,
    color: Color = Color.White,
    onClick: () -> Unit,
  ) {
    TextButton(onClick = onClick) { Text(text, color = color, fontSize = 12.sp) }
  }
}
