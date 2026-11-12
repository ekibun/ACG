package soko.ekibun.acg.ui.screen.home

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_account_circle
import acg.shared.generated.resources.ic_check
import acg.shared.generated.resources.ic_home
import acg.shared.generated.resources.ic_logout
import acg.shared.generated.resources.ic_settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import soko.ekibun.acg.data.Account
import soko.ekibun.acg.data.AccountRepository
import soko.ekibun.acg.ui.comp.Avatar
import soko.ekibun.acg.ui.nav.AppRoute
import soko.ekibun.acg.ui.nav.BackHandler
import soko.ekibun.acg.ui.screen.account.AddAccountDialog
import soko.ekibun.acg.ui.screen.home.tab.CollectionScreen
import soko.ekibun.acg.ui.screen.home.tab.CommunityScreen
import soko.ekibun.acg.ui.screen.home.tab.SearchScreen
import soko.ekibun.acg.ui.screen.home.tab.TimetableScreen

/**
 * 主页：只管自己的 tab、账号面板与添加账号弹层，页面导航都交给 [onNavigate]。
 *
 * tab 不是路由，切它只换正文；设置是压栈的页面，从窗口根替换掉主页，回来时 tab 由 saveable 还原。
 */
@Composable
fun HomeScreen(
  onNavigate: (AppRoute) -> Unit,
  modifier: Modifier = Modifier,
) {
  // 只有 tab 属于这一页：换下去再回来要还原。账号是全局的，面板开合是临时状态，都不该被记住
  var tab by rememberSaveable { mutableStateOf(HomeTab.Collection) }
  var accountOpen by remember { mutableStateOf(false) }
  var addAccountOpen by remember { mutableStateOf(false) }
  val accounts by AccountRepository.accounts.collectAsState()
  val currentAccount by AccountRepository.current.collectAsState()
  // 账号的写在收起面板之后还要跑完，所以挂在主页上：面板自己的 scope 一收就没了
  val scope = rememberCoroutineScope()

  // 返回：面板开着先关面板，否则从别的 tab 回收藏。添加账号弹层有自己的返回处理。
  BackHandler(enabled = accountOpen || tab != HomeTab.Collection) {
    if (accountOpen) accountOpen = false else tab = HomeTab.Collection
  }

  Box(modifier.fillMaxSize()) {
    HomeTabLayout(
      selected = tab,
      currentAccount = currentAccount,
      onSelect = { tab = it },
      onOpenAccount = { accountOpen = true },
    ) {
      TabContent(tab)
    }

    if (accountOpen) {
      AccountPopup(
        accounts = accounts,
        current = currentAccount,
        onSelect = { id -> scope.launch { AccountRepository.select(id) } },
        onRemove = { id -> scope.launch { AccountRepository.remove(id) } },
        onAddAccount = {
          accountOpen = false
          addAccountOpen = true
        },
        onNavigate = { route ->
          accountOpen = false
          onNavigate(route)
        },
        onDismiss = { accountOpen = false },
      )
    }

    // 添加账号只是「挑一个站点、等插件登录完」，没有要留下的状态，居中一张卡就够
    if (addAccountOpen) AddAccountDialog(onDismiss = { addAccountOpen = false })
  }
}

/** tab 的顶栏由主页统一给，正文各页自己写：四个 tab 各一个文件，加 tab 就在这里加一条。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabContent(tab: HomeTab) {
  // 切 tab 会销毁上一个 tab 的组合；按 tab 存住各自的 saveable 状态，切回来输入词之类的还在
  val tabStates = rememberSaveableStateHolder()
  Scaffold(
    topBar = { TopAppBar(title = { Text(tab.title) }) },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      tabStates.SaveableStateProvider(tab) {
        when (tab) {
          HomeTab.Timetable -> TimetableScreen()
          HomeTab.Collection -> CollectionScreen()
          HomeTab.Community -> CommunityScreen()
          HomeTab.Search -> SearchScreen()
        }
      }
    }
  }
}

/** 账号面板：左下角（头像处）展开的圆角卡 —— 上面是账号列表，底部是当前账号的一排操作（主页、设置、登出）。 */
@Composable
private fun AccountPopup(
  accounts: Map<String, Account>,
  current: Account?,
  onSelect: (String) -> Unit,
  onRemove: (String) -> Unit,
  onAddAccount: () -> Unit,
  onNavigate: (AppRoute) -> Unit,
  onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var error by remember { mutableStateOf<String?>(null) }

  // 铺满窗口的一层：接住「点外面关闭」，同时挡住点击穿透到下面的页面（`Popup` 做不到后者）
  Box(
    Modifier
      .fillMaxSize()
      .clickable(interactionSource = null, indication = null, onClick = onDismiss)
      .background(Color.Black.copy(alpha = 0.32f)),
    contentAlignment = Alignment.BottomStart,
  ) {
    Surface(
      modifier =
        Modifier
          // 这层覆盖物在窗口根上，各页 Scaffold 的内缩管不到它：Android 的导航条 / 状态栏要自己避开
          .windowInsetsPadding(WindowInsets.systemBars)
          .padding(bottom = 72.dp, start = 8.dp)
          .widthIn(max = 360.dp)
          // 卡片自己吃掉点击，免得不小心点到卡片也把面板关了
          .clickable(interactionSource = null, indication = null, onClick = {}),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Column(Modifier.padding(vertical = 8.dp)) {
        Text(
          "账号",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (accounts.isEmpty()) {
          Text(
            "还没有账号。添加一个之后，收藏与历史都跟着它。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
        accounts.values.forEach { account ->
          ListItem(
            // 面板自己有底色，条目透明才不会在圆角卡里铺一层 surface
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(account.name) },
            supportingContent = { Text(account.site) },
            leadingContent = { Avatar(account.name, account.avatar) },
            trailingContent = {
              if (account.key == current?.key) {
                Icon(painterResource(Res.drawable.ic_check), contentDescription = null)
              }
            },
            modifier =
              Modifier.clickable {
                onSelect(account.key)
                onDismiss()
              },
          )
        }
        ListItem(
          colors = ListItemDefaults.colors(containerColor = Color.Transparent),
          headlineContent = { Text("添加账号") },
          leadingContent = {
            Icon(painterResource(Res.drawable.ic_account_circle), contentDescription = null, Modifier.size(28.dp))
          },
          modifier = Modifier.clickable(onClick = onAddAccount),
        )
        // 当前账号的操作排：点左边开站点主页，右边两个按钮是设置与登出
        if (current != null) {
          HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
          ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("主页") },
            leadingContent = {
              Icon(painterResource(Res.drawable.ic_home), contentDescription = null)
            },
            trailingContent = {
              Row {
                IconButton(onClick = { onNavigate(AppRoute.Settings) }) {
                  Icon(painterResource(Res.drawable.ic_settings), contentDescription = "设置")
                }
                // 登出只删这一条账号，连它的会话一起；删的正好是当前账号就切到剩下的第一个
                IconButton(
                  onClick = {
                    onRemove(current.key)
                    onDismiss()
                  },
                ) {
                  Icon(painterResource(Res.drawable.ic_logout), contentDescription = "退出登录")
                }
              }
            },
            modifier =
              Modifier.clickable {
                error = null
                scope.launch {
                  try {
                    AccountRepository.openHome(current.key)
                    onDismiss()
                  } catch (e: CancellationException) {
                    throw e
                  } catch (e: Throwable) {
                    // JSError 继承 Throwable：不接住会把应用带崩。没开成就在面板里说一声
                    error = e.message ?: e.toString()
                  }
                }
              },
          )
        }
        val message = error
        if (message != null) {
          Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
      }
    }
  }
}
