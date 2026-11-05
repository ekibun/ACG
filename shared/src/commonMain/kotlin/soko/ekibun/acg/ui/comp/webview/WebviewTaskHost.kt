package soko.ekibun.acg.ui.comp.webview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import soko.ekibun.acg.engine.WebviewTask
import soko.ekibun.acg.engine.WebviewTasks
import soko.ekibun.web.WebViewHost

/** 进行中的 WebView 任务：没有前台任务时缩成右下角一个胶囊，点开是完整列表。 */
@Composable
fun WebviewTaskHost(modifier: Modifier = Modifier) {
  val tasks by WebviewTasks.all.collectAsState()
  val foreground by WebviewTasks.foreground.collectAsState()

  if (tasks.isEmpty()) return

  Column(modifier.fillMaxSize()) {
    // 每个任务一个常驻宿主，`key(task)` 钉住位置：切前台只改高度。摘出组合会销毁 AWT peer，
    // WebView2 的原生窗口随之被系统销毁，再挂回来就是黑屏（见 shared-layer.md §3.5.1）。
    tasks.forEach { task ->
      key(task) {
        Box(
          Modifier
            .fillMaxWidth()
            .then(if (task == foreground) Modifier.weight(1f) else Modifier.height(0.dp)),
        ) {
          WebviewTaskPane(task)
        }
      }
    }

    if (foreground == null) {
      Box(Modifier.weight(1f).fillMaxWidth()) { WebviewTaskDock(tasks) }
    }
  }
}

/** 一个任务的面板：与「浏览器」页签共用 [WebViewPane]，只是不给后退 / 前进。 */
@Composable
private fun WebviewTaskPane(task: WebviewTask) {
  val page by task.page.collectAsState()

  WebViewPane(
    title = page.title ?: page.url,
    progress = page.progress,
    history = false,
    onReload = { WebviewTasks.reload(task) },
    trailing = {
      // 前台任务没有「后台」：调用方正等着它，收起来也改变不了结果，只有关闭（即失败）
      if (!task.foregroundOnly) {
        TextButton(onClick = { WebviewTasks.toBackground(task) }) { Text("后台") }
      }
      TextButton(onClick = { WebviewTasks.close(task) }) { Text("关闭") }
    },
  ) {
    task.controller?.let { controller -> WebViewHost(controller, Modifier.fillMaxSize()) }
  }
}

/** 后台态：右下角一个胶囊，点开是任务列表。 */
@Composable
private fun WebviewTaskDock(tasks: List<WebviewTask>) {
  var expanded by remember { mutableStateOf(false) }

  Box(Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (expanded) WebviewTaskList(tasks)
      WebviewTaskBadge(count = tasks.size, expanded = expanded) { expanded = !expanded }
    }
  }
}

/** 折叠态：只有一句「N 个任务」。 */
@Composable
private fun WebviewTaskBadge(
  count: Int,
  expanded: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    color = MaterialTheme.colorScheme.primaryContainer,
    shape = CircleShape,
  ) {
    Text(
      text = if (expanded) "收起" else "$count 个任务在后台",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    )
  }
}

/** 展开态：一个一个列出来，点一下切前台，右边的叉直接关掉。 */
@Composable
private fun WebviewTaskList(tasks: List<WebviewTask>) {
  Card(Modifier.widthIn(max = 320.dp)) {
    Column(Modifier.padding(vertical = 4.dp)) {
      tasks.forEach { task ->
        Row(
          modifier = Modifier.fillMaxWidth().clickable { WebviewTasks.toForeground(task) }.padding(start = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = task.url,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { WebviewTasks.close(task) }) { Text("关闭") }
        }
      }
    }
  }
}
