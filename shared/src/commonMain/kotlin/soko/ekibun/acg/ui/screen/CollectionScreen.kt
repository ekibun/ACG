package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soko.ekibun.acg.source.BgmCollectionItem
import soko.ekibun.acg.source.BgmCollectionStatus
import soko.ekibun.acg.source.BgmSubjectType
import soko.ekibun.acg.source.BgmUser
import soko.ekibun.acg.ui.comp.RemoteImage
import kotlin.math.round

/** Bangumi 收藏页：未登录画凭据输入，已登录画条目类型页签、状态筛选与列表。 */
@Composable
fun CollectionScreen(modifier: Modifier = Modifier) {
  val state = remember { CollectionState() }

  // 首帧加载一次：登录态与第一页
  LaunchedEffect(Unit) { state.load() }

  Surface(modifier = modifier.fillMaxSize()) {
    val loggedInUser = state.user
    if (loggedInUser == null) {
      LoginPane(state)
    } else {
      CollectionPane(state, loggedInUser)
    }
  }
}

/** 未登录：凭据输入与登录入口。 */
@Composable
private fun LoginPane(
  state: CollectionState,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  // 回填已存的凭据；值到货时跟着换一次
  var clientId by remember(state.storedClientId) { mutableStateOf(state.storedClientId) }
  var clientSecret by remember(state.storedClientSecret) { mutableStateOf(state.storedClientSecret) }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("登录 Bangumi", style = MaterialTheme.typography.titleMedium)
    Text(
      "收藏页走 OAuth 授权码流程，得先自备一个应用：在 https://bgm.tv/dev/app 建一个，" +
        "回调地址填回环地址（例如 http://127.0.0.1/）。bgm 不校验端口与路径，" +
        "应用在本机随机端口上接住回调，所以填哪个回环地址都行，端口不用对齐。",
      style = MaterialTheme.typography.bodySmall,
    )

    OutlinedTextField(
      value = clientId,
      onValueChange = { clientId = it },
      label = { Text("App ID") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = clientSecret,
      onValueChange = { clientSecret = it },
      label = { Text("App Secret") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    Button(
      enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && !state.loggingIn,
      onClick = { scope.launch { state.login(clientId.trim(), clientSecret.trim()) } },
    ) {
      Text(if (state.loggingIn) "等待浏览器授权…" else "登录")
    }

    val error = state.error
    if (error != null) ErrorText(error)
  }
}

@Composable
private fun CollectionPane(
  state: CollectionState,
  user: BgmUser,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()

  Column(modifier = modifier.fillMaxSize()) {
    UserBar(user, onLogout = { scope.launch { state.logout() } })

    // 条目类型页签
    SecondaryTabRow(selectedTabIndex = BgmSubjectType.entries.indexOf(state.subjectType)) {
      BgmSubjectType.entries.forEach { type ->
        Tab(
          selected = type == state.subjectType,
          onClick = { scope.launch { state.selectSubjectType(type) } },
          text = { Text(type.label) },
        )
      }
    }

    // 收藏状态筛选
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      BgmCollectionStatus.entries.forEach { candidate ->
        FilterChip(
          selected = candidate == state.status,
          onClick = { scope.launch { state.selectStatus(candidate) } },
          label = { Text(candidate.label) },
        )
      }
    }

    Text(
      text = "共 ${state.total} 条",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 12.dp),
    )

    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
      val error = state.error
      when {
        state.loading ->
          CircularProgressIndicator(Modifier.align(Alignment.Center))

        error != null ->
          ErrorText(error, Modifier.align(Alignment.Center).padding(24.dp))

        state.items.isEmpty() ->
          Text(
            text = "这一档还没有收藏",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.Center),
          )

        else ->
          LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = BgmCollectionItem::subjectId) { item ->
              CollectionRow(item)
            }
            if (state.hasMore) {
              item {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  if (state.loadingMore) {
                    CircularProgressIndicator()
                  } else {
                    TextButton(onClick = { scope.launch { state.loadMore() } }) {
                      Text("加载更多")
                    }
                  }
                }
              }
            }
          }
      }
    }
  }
}

@Composable
private fun UserBar(
  user: BgmUser,
  onLogout: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    RemoteImage(
      url = user.avatarUrl,
      contentDescription = null,
      modifier = Modifier.width(28.dp).height(28.dp).clip(MaterialTheme.shapes.small),
    )
    Text(
      text = user.nickname.ifEmpty { user.username },
      style = MaterialTheme.typography.titleSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onLogout) { Text("退出") }
  }
}

@Composable
private fun CollectionRow(
  item: BgmCollectionItem,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // 固定 56×78 的封面
    RemoteImage(
      url = item.coverUrl,
      contentDescription = null,
      modifier = Modifier.width(56.dp).height(78.dp).clip(MaterialTheme.shapes.small),
    )
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        // 中文名优先，原文名做副标题
        text = item.nameCn ?: item.name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      item.nameCn?.let { chineseName ->
        if (chineseName != item.name) {
          Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Text(
        text = itemMeta(item),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ErrorText(
  message: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = message,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
    modifier = modifier,
  )
}

/** 一行里能同时放下的信息：评分、排名、进度（没进度就看放送日期）。 */
private fun itemMeta(item: BgmCollectionItem): String =
  listOfNotNull(
    item.score.takeIf { it > 0.0 }?.let { "★ ${formatScore(it)}" },
    item.rank.takeIf { it > 0 }?.let { "排名 #$it" },
    when {
      item.eps > 0 -> "进度 ${item.epStatus}/${item.eps}"
      else -> item.date?.let { "放送 $it" }
    },
  ).joinToString(" · ")

/** 格式化成一位小数。 */
private fun formatScore(score: Double): String {
  val tenths = round(score * 10).toInt()
  return "${tenths / 10}.${tenths % 10}"
}
