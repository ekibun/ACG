package soko.ekibun.acg.ui.screen.home

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_account_circle
import acg.shared.generated.resources.ic_check
import acg.shared.generated.resources.ic_settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.compose.resources.painterResource
import soko.ekibun.acg.data.AccountRepository
import soko.ekibun.acg.ui.comp.MonogramAvatar
import soko.ekibun.acg.ui.nav.BackHandler
import soko.ekibun.acg.ui.screen.home.tab.CollectionScreen
import soko.ekibun.acg.ui.screen.home.tab.CommunityScreen
import soko.ekibun.acg.ui.screen.home.tab.SearchScreen
import soko.ekibun.acg.ui.screen.home.tab.TimetableScreen

/**
 * 主页：只管自己的 tab 与账号面板，导航在 [soko.ekibun.acg.App] 那一层。
 *
 * 切 tab 只换正文；打开设置由窗口根压栈，设置页替换掉主页（连 tab 一起），回来时 tab 由 saveable 还原。
 */
@Composable
fun HomeScreen(
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // 只有 tab 属于这一页：换下去再回来要还原。账号是全局的，面板开合是临时状态，都不该被记住
  var tab by rememberSaveable { mutableStateOf(HomeTab.Collection) }
  var accountOpen by remember { mutableStateOf(false) }
  val users by AccountRepository.all.collectAsState()
  val currentUser by AccountRepository.current.collectAsState()

  // 返回：面板开着先关面板，否则从别的 tab 回收藏。面板里不能放 handler（见 shared-layer §5.7）。
  BackHandler(enabled = accountOpen || tab != HomeTab.Collection) {
    if (accountOpen) accountOpen = false else tab = HomeTab.Collection
  }

  BoxWithConstraints(modifier.fillMaxSize()) {
    HomeTabLayout(
      selected = tab,
      currentUser = currentUser,
      onSelect = { tab = it },
      onOpenAccount = { accountOpen = true },
      onOpenSettings = onOpenSettings,
    ) {
      TabContent(tab)
    }

    if (accountOpen) {
      AccountPopup(
        users = users,
        currentUser = currentUser,
        onSelectUser = AccountRepository::select,
        // 侧栏已经把设置摆出来了，面板里就别再重复一条
        onOpenSettings = if (maxWidth >= RailBreakpoint) null else onOpenSettings,
        onDismiss = { accountOpen = false },
      )
    }
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

/**
 * 账号面板：从左下角（头像所在处）向上展开的圆角卡，外加一层 32% 遮罩。
 *
 * 只在 [HomeScreen] 里用，所以跟着主页走，不进 `ui/comp`。
 */
@Composable
private fun AccountPopup(
  users: List<String>,
  currentUser: String,
  onSelectUser: (String) -> Unit,
  onOpenSettings: (() -> Unit)?,
  onDismiss: () -> Unit,
) {
  Popup(
    alignment = Alignment.BottomStart,
    properties = PopupProperties(),
    onDismissRequest = onDismiss,
  ) {
    // 铺满的遮罩兼「点外面关闭」；不拦截的话点击会穿透到下面的页面
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
            .padding(bottom = 72.dp, start = 8.dp)
            .widthIn(max = 360.dp)
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
          users.forEach { user ->
            ListItem(
              // 面板自己有底色，条目透明才不会在圆角卡里铺一层 surface
              colors = ListItemDefaults.colors(containerColor = Color.Transparent),
              headlineContent = { Text(user) },
              leadingContent = { MonogramAvatar(user) },
              trailingContent = {
                if (user == currentUser) {
                  Icon(painterResource(Res.drawable.ic_check), contentDescription = null)
                }
              },
              modifier =
                Modifier.clickable {
                  onSelectUser(user)
                  onDismiss()
                },
            )
          }
          ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("添加账号") },
            leadingContent = {
              Icon(painterResource(Res.drawable.ic_account_circle), contentDescription = null)
            },
            modifier = Modifier.clickable { onDismiss() },
          )
          // 底栏没有位置放设置按钮，那条入口就留在面板里；侧栏给了独立按钮，传 null 即可
          if (onOpenSettings != null) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ListItem(
              colors = ListItemDefaults.colors(containerColor = Color.Transparent),
              headlineContent = { Text("设置") },
              leadingContent = {
                Icon(painterResource(Res.drawable.ic_settings), contentDescription = null)
              },
              modifier =
                Modifier.clickable {
                  onOpenSettings()
                  onDismiss()
                },
            )
          }
        }
      }
    }
  }
}
