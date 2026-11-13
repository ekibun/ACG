package soko.ekibun.acg.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/*
 * 整屏占位三态：读、失败、空。同一时刻只出现一个，占满整块内容区 ——
 * 与 Avatar、RemoteImage 这类内容积木不同：它们知道自己画什么，这三个只回答「这里现在有什么」。
 *
 * 撑满归组件，调用方只给定位（weight、padding）；判定顺序（读 > 失败 > 空）归调用点。
 */

/** 整页加载：正中一个圈。与 [EmptyState] 相对 —— 那边是「没有」，这边是「还不知道」。 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

/** 读不下来的一页：说清是什么事、给一个重试。与 [EmptyState] 的区别是可恢复，原因用 `error` 色。 */
@Composable
fun RetryState(
  title: String,
  description: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  StateColumn(modifier) {
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
      description,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
      textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry) { Text("重试") }
  }
}

/** 空状态：96dp 圆底图标 + 标题 + 一句说明。M3 没有这个组件，按「图标、大标题、为什么」凑。 */
@Composable
fun EmptyState(
  icon: DrawableResource,
  title: String,
  description: String,
  modifier: Modifier = Modifier,
) {
  StateColumn(modifier) {
    Box(
      Modifier
        .size(96.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(40.dp),
      )
    }
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
      description,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/** 占位共用的排版壳：撑满、左右 32dp、间距 16dp、居中。 */
@Composable
private fun StateColumn(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier.fillMaxSize().padding(horizontal = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
    content = content,
  )
}
