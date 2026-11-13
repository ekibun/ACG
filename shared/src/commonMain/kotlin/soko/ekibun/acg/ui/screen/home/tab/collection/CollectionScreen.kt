package soko.ekibun.acg.ui.screen.home.tab.collection

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_account_circle
import acg.shared.generated.resources.ic_bookmark
import acg.shared.generated.resources.ic_refresh
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.resources.painterResource
import soko.ekibun.acg.data.Account
import soko.ekibun.acg.data.AccountRepository
import soko.ekibun.acg.data.CollectionCategory
import soko.ekibun.acg.data.CollectionItem
import soko.ekibun.acg.data.CollectionRepository
import soko.ekibun.acg.data.CollectionTable
import soko.ekibun.acg.data.DarkMode
import soko.ekibun.acg.data.ThemeSettings
import soko.ekibun.acg.ui.comp.EmptyState
import soko.ekibun.acg.ui.comp.LoadingState
import soko.ekibun.acg.ui.comp.PullRefreshBox
import soko.ekibun.acg.ui.comp.RetryState
import soko.ekibun.acg.ui.screen.home.HomeTab
import soko.ekibun.acg.ui.theme.AppTheme

/**
 * 收藏：类型与状态两排筛选，下面是当前档的列表。
 *
 * 状态全在 [CollectionRepository] 的表里（`loadingPage` / `error.page`），这一页不另存；
 * 没有账号就整页一句提示 —— 收藏跟着账号走。
 */
@Composable
fun CollectionScreen(modifier: Modifier = Modifier) {
  val account = AccountRepository.current.collectAsState().value
  val table by CollectionRepository.table.collectAsState()
  var picked by rememberSaveable(account?.key) { mutableStateOf<String?>(null) }

  CollectionPage(
    account = account,
    table = table,
    picked = picked,
    onPick = { picked = it },
    load = CollectionRepository::load,
    modifier = modifier,
  )
}

/**
 * 页面本体：账号与表从外面给，只决定画什么 —— 预览不必真登录就能摆整页。
 * 两个 `LaunchedEffect` 也在这里，只读 [table] 与 [account]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionPage(
  account: Account?,
  table: CollectionTable,
  picked: String?,
  onPick: (String?) -> Unit,
  load: (Account, String?, Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  // 表属于当前账号才拿它算档：换账号那一帧表还是上一个人的，不能把它的档画出来
  val owned = account != null && table.accountKey == account.key
  val entry =
    if (owned) {
      table.categories.entries.firstOrNull { it.key == picked } ?: table.categories.entries.firstOrNull()
    } else {
      null
    }

  // load 每次重组都是一个新引用，进 effect 键会把它重启成死循环；只取最新值
  val currentLoad by rememberUpdatedState(load)
  // 第一次打开、换账号：读整份键表
  LaunchedEffect(account?.key) {
    if (account != null && (table.accountKey != account.key || table.categories.isEmpty())) {
      currentLoad(account, null, 1)
    }
  }
  // 落到还没读过的档（page == 0）就读它；读过的一律直接摆
  LaunchedEffect(owned, entry?.key) {
    if (owned && entry != null && entry.value.page == 0) {
      currentLoad(account, entry.key, 1)
    }
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(HomeTab.Collection.title) },
        actions = {
          // 手指能下拉，鼠标与键盘不能，桌面上这里是唯一入口；没有档就重读整份，按钮不留空
          if (account != null) {
            IconButton(onClick = { load(account, entry?.key, 1) }) {
              Icon(painterResource(Res.drawable.ic_refresh), contentDescription = "刷新")
            }
          }
        },
      )
    },
  ) { padding ->
    val body = Modifier.padding(padding)
    val error = table.error
    when {
      account == null ->
        EmptyState(
          icon = Res.drawable.ic_account_circle,
          title = "还没有登录",
          description = "从导航条上的账号入口加一个站点账号，收藏跟着它",
          modifier = body,
        )
      !owned -> LoadingState(body)
      // 还没有键表：读失败就停在错误上，读完却一个档都没有说明插件没给，别一直转圈
      entry == null ->
        when {
          // 在读（含点重试的那一轮）：开始读的时候 `error` 已经清掉了，这里不必替它挡
          table.loading -> LoadingState(body)
          error != null ->
            RetryState(
              "收藏没读下来",
              error,
              onRetry = { load(account, null, 1) },
              modifier = body,
            )
          else ->
            EmptyState(
              icon = Res.drawable.ic_bookmark,
              title = "没有收藏档",
              description = "插件没有给出任何一档收藏",
              modifier = body,
            )
        }
      else ->
        Column(body.fillMaxSize()) {
          CollectionFilters(
            keys = table.categories.keys.toList(),
            selected = entry.key,
            onSelect = onPick,
          )
          // 换档换一整棵子树：滚动位置跟着档走，不带到下一档
          key(account.key, entry.key) {
            CategoryBody(
              category = entry.value,
              onRefresh = { load(account, entry.key, 1) },
              onRetry = { page -> load(account, entry.key, page) },
              onNext = { load(account, entry.key, entry.value.page + 1) },
              modifier = Modifier.weight(1f),
            )
          }
        }
    }
  }
}

/**
 * 一档的内容：顶上刷新失败提示，下面是列表或整页状态。全部判断都从 [category] 推出来，没有自己的
 * `remember`；只有列表能下拉 —— 空页与错误页没有可滚的东西，它们各走顶栏刷新与「重试」。
 */
@Composable
private fun CategoryBody(
  category: CollectionCategory,
  onRefresh: () -> Unit,
  onRetry: (Int) -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val error = category.error
  Column(modifier) {
    // 刷新失败：内容还是好的，顶上提醒一句，列表照常
    category.refreshError?.let { RefreshErrorBar(it.message) { onRetry(1) } }
    when {
      // 有内容就是列表：刷新中、刷新失败都只叠在它身上，不遮内容
      category.data.isNotEmpty() ->
        PullRefreshBox(
          isRefreshing = category.refreshing,
          onRefresh = onRefresh,
          modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
          CollectionList(category, onRetry, onNext)
        }
      // 空白时：读 > 失败 > 空。三者同时成立只有「读到过空页又刷新失败」这一种，
      // 那时该说没读下来，而不是画成「还没有收藏」
      category.loadingPage != null -> LoadingState(Modifier.weight(1f))
      error != null ->
        RetryState(
          "这一档没读下来",
          error.message,
          onRetry = { onRetry(error.page) },
          modifier = Modifier.weight(1f),
        )
      else ->
        EmptyState(
          icon = Res.drawable.ic_bookmark,
          title = "这一档还没有收藏",
          description = "换一个筛选看看，或者去站点上标记几条",
          modifier = Modifier.weight(1f),
        )
    }
  }
}

/** 整页：顶栏、两排筛选、列表都按真实装配摆，试的是「这一页最后长什么样」。 */
@Preview(name = "收藏页", widthDp = 420, heightDp = 720)
@Composable
private fun CollectionPagePreview() {
  var picked by remember { mutableStateOf<String?>(null) }
  PreviewFrame {
    CollectionPage(
      account = SAMPLE_ACCOUNT,
      table = SAMPLE_TABLE,
      picked = picked,
      onPick = { picked = it },
      load = { _, _, _ -> },
    )
  }
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
  AppTheme(ThemeSettings(darkMode = DarkMode.Light)) {
    Surface(Modifier.fillMaxSize()) { content() }
  }
}

private val SAMPLE_ACCOUNT = Account(site = "bgm", id = "ekibun", name = "ekibun", session = "")

/** 站点上真实存在的三种名字形态混在一起：有中文名、中文名与原名相同、只有原名。 */
private val SAMPLE_ITEMS =
  listOf(
    CollectionItem(1, "Sousou no Frieren", "葬送的芙莉莲", "2023 年 9 月 29 日 / 28 话", progress = "12/28"),
    CollectionItem(2, "君の名は。", "君の名は。", "2016 年 8 月 26 日 / 1 话", progress = "看过"),
    CollectionItem(3, "Vivy: Fluorite Eye's Song", summary = "2021 年 4 月 3 日 / 13 话", progress = "3/13"),
    CollectionItem(4, "Steins;Gate", "命运石之门", "2011 年 4 月 6 日 / 24 话", progress = "24/24"),
    CollectionItem(5, "Mushishi", "虫师", progress = "11/26"),
    CollectionItem(6, "Odd Taxi", "奇巧计程车", "2021 年 4 月 6 日 / 13 话", progress = "7/13"),
  )

private val SAMPLE_TABLE =
  CollectionTable(
    accountKey = SAMPLE_ACCOUNT.key,
    categories =
      mapOf(
        "动画|在看" to CollectionCategory(data = SAMPLE_ITEMS, page = 1, pageTotal = 1),
        "动画|想看" to CollectionCategory(page = 1, pageTotal = 1),
        "书籍|在读" to CollectionCategory(page = 1, pageTotal = 1),
        "三次元|在看" to CollectionCategory(page = 1, pageTotal = 1),
      ),
  )
