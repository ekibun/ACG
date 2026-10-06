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

/**
 * dev 控制条：**无标题栏**长条，[ComposeDialog] 以应用主窗口为 owner 挂上去（owner 同进程才成立，
 * 所以由 [DevToolsAgent] 在应用进程里创建），**内容直接由 Compose 绘制** —— material3 的
 * 按钮与状态文本。窗口层留给 AWT：owner / 跟随移动 / 不抢焦点都要靠它，而 ComposeWindow 是
 * JFrame、拿不到 owner。
 *
 * 透明只在这层有开关：它要同时把面板置为非 opaque、把 skia 表面切到透明、并换掉 skiko 的窗口底色
 * —— 只设 `window.background` 不管用；所以 `isTransparent` 必须在窗口 displayable（即 `pack()`）
 * 之前设定，且要求 `isUndecorated`，否则 CMP 直接抛异常。没有系统边框也就没有系统阴影：圆角、
 * 1dp 边框与圆角外的透明留白都在 Compose 里自绘。
 *
 * Compose 类的加载靠 **parent-first**：子 ClassLoader 的 parent 是应用类加载器，所有
 * `androidx.compose.*` 与 skiko 都由应用自己的那份提供（版本天然一致）；dev 类路径里的
 * compose jar 只在编译期可见，运行时被遮蔽 —— 不存在两套运行时打架的问题。
 *
 * 跟随：监听 owner 的 componentMoved / componentResized，把控制条贴回主窗口右上角（顶边上方），
 * 主窗口移动 / 缩放都跟；owner 关闭时跟着 dispose，最小化时藏起。
 *
 * 反馈：状态文本**定宽**（`weight` + 单行截断），每秒轮询只读的 `/__control/status` —— 不点按钮
 * 也看得到 frozen / bps / served 的实时变化（播放时 served 自己往上涨）；状态再长也不改布局尺寸。
 *
 * 与 dev 服务器的对话走 `/__control` 的各动作（HttpURLConnection，回环毫秒级，UI 线程上直接发）。
 */
object DevStrip {
  @JvmStatic
  @OptIn(ExperimentalComposeUiApi::class)
  fun attach(
    owner: Window,
    port: String,
  ) {
    val strip = ComposeDialog(owner)
    // 不抢焦点：点控制条不影响应用正在输入的东西；鼠标事件照常到达面板。
    strip.focusableWindowState = false
    strip.isUndecorated = true
    // undecorated 下 CMP 会留一圈不可见的边缘拖拽区，贴边 8dp 的点击会被它吃掉。
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
        }.getOrElse { "服务未响应（$action）" }
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
        Action("冻结") { send("freeze") }
        Action("恢复") { send("resume") }
        Action("限速") { send("throttle?bps=30000") }
        Action("不限") { send("throttle?bps=0") }
        // 定宽 + 单行截断：文本变化不改布局尺寸，行高恒定。
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
