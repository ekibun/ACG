package soko.ekibun.acg.ui.comp.webview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 浏览器工具栏：后退 / 前进 / 刷新、标题、尾部动作，下面一条固定高度的进度条。
 *
 * 只吃普通参数，两处调用各喂各的状态。[history] 为假时不画后退 / 前进——脚本发起的页面
 * 有明确目的，让用户乱逛只会把流程带偏。
 */
@Composable
fun WebToolbar(
  title: String,
  progress: Float,
  modifier: Modifier = Modifier,
  history: Boolean = true,
  canGoBack: Boolean = false,
  canGoForward: Boolean = false,
  onBack: () -> Unit = {},
  onForward: () -> Unit = {},
  onReload: () -> Unit = {},
  /** 给了它，加载中就把「刷新」换成「停止」。 */
  onStop: (() -> Unit)? = null,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  Column(modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (history) {
        ToolbarButton("←", enabled = canGoBack, onClick = onBack)
        ToolbarButton("→", enabled = canGoForward, onClick = onForward)
      }
      val stoppable = onStop != null && progress < 1f
      ToolbarButton(if (stoppable) "停止" else "刷新") { if (stoppable) onStop?.invoke() else onReload() }
      Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
      )
      trailing()
    }

    // 高度固定：这行一变高变矮会带着 WebView 重新贴边，页面跟着抖一下
    Box(Modifier.fillMaxWidth().height(PROGRESS_HEIGHT)) {
      if (progress < 1f) {
        LinearProgressIndicator(
          progress = { progress.coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

/**
 * 工具栏按钮：固定高度、宽度随文字，不可用时压暗。
 *
 * 只用 ← → 与中文标签，不引图标库：`⟳` 这类符号在 Android 系统字体里不一定有，会渲染成方块。
 */
@Composable
private fun ToolbarButton(
  text: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .height(BUTTON_HEIGHT)
        .clip(CircleShape)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = if (text.length == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
      color =
        if (enabled) {
          MaterialTheme.colorScheme.onSurface
        } else {
          MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
        },
    )
  }
}

/** 进度条占的高度，无论加载与否都不变。 */
private val PROGRESS_HEIGHT = 2.dp

private val BUTTON_HEIGHT = 36.dp
