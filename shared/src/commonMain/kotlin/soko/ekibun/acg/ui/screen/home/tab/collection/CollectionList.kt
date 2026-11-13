package soko.ekibun.acg.ui.screen.home.tab.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soko.ekibun.acg.data.CollectionCategory
import soko.ekibun.acg.data.CollectionItem
import soko.ekibun.acg.data.DarkMode
import soko.ekibun.acg.data.ThemeSettings
import soko.ekibun.acg.ui.comp.RemoteImage
import soko.ekibun.acg.ui.theme.AppTheme

/** 一档读过的内容：条目接着条目，尾巴管续读。 */
@Composable
internal fun CollectionList(
  category: CollectionCategory,
  onRetry: (Int) -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
    items(category.data, key = { it.id }) { CollectionRow(it) }
    item(key = "footer") { CollectionFooter(category, onRetry, onNext) }
  }
}

/**
 * 列表尾巴：没读完就接着要，没得要不画。续读只看档自己的状态（没在飞、没出错、还有下一页）——
 * 刷新那一轮也满足前两条，但那时 `loadingMore` 是 false，两者由同一个 `loadingPage` 区分。
 */
@Composable
private fun CollectionFooter(
  category: CollectionCategory,
  onRetry: (Int) -> Unit,
  onNext: () -> Unit,
) {
  val error = category.error
  // 回调每次重组都是新的，进 effect 键会把续读重启成死循环；只取最新值
  val currentOnNext by rememberUpdatedState(onNext)
  LaunchedEffect(category.page, category.pageTotal, category.loadingPage, error) {
    if (category.loadingPage == null && error == null && category.hasMore) currentOnNext()
  }

  when {
    // 续读失败：停在这一页上，重试还是要它；刷新失败由顶上那条提示管，这里不重复
    error != null && error.page > 1 ->
      Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          error.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
        )
        TextButton(onClick = { onRetry(error.page) }) { Text("重试") }
      }
    category.loadingMore ->
      Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
      }
  }
}

/** 刷新失败的一行提醒：内容还在，只是这次没读到，给一次重试。 */
@Composable
internal fun RefreshErrorBar(
  message: String,
  onRetry: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.errorContainer)
      .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "刷新失败：$message",
      Modifier.weight(1f),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onErrorContainer,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    TextButton(onClick = onRetry) { Text("重试") }
  }
}

/** 一条收藏：竖版封面加名字。中文名是主标题，原名退成副标题；[CollectionItem.summary] 是站点那行灰字。 */
@Composable
private fun CollectionRow(item: CollectionItem) {
  val nameCn = item.nameCn
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
    RemoteImage(
      url = item.image,
      contentDescription = null,
      modifier = Modifier.width(60.dp).height(84.dp).clip(MaterialTheme.shapes.medium),
    )
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f).heightIn(min = 84.dp)) {
      Text(
        nameCn ?: item.name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      // 站点上 `nameCn` 可能与原名一字不差（实测见 `docs/development/shared-layer.md` §5.4），
      // 只看「有没有中文名」会把同一行字画两遍
      if (nameCn != null && nameCn != item.name) {
        Text(
          item.name,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      val summary = item.summary
      if (summary != null) {
        Text(
          summary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      val progress = item.progress
      if (progress != null) {
        Text(progress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
    }
  }
}

/** 条目：单条的样子，看封面比例、行距与四行文字是否对齐。整页的装配在 `CollectionScreen` 那张预览里。 */
@Preview(name = "条目")
@Composable
private fun CollectionRowPreview() {
  AppTheme(ThemeSettings(darkMode = DarkMode.Light)) {
    Surface {
      CollectionRow(
        CollectionItem(
          id = 1,
          name = "Sousou no Frieren",
          nameCn = "葬送的芙莉莲",
          summary = "2023 年 9 月 29 日 / 28 话",
          progress = "12/28",
        ),
      )
    }
  }
}
