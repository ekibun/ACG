package soko.ekibun.acg.ui.screen.home

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_account_circle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import soko.ekibun.acg.data.Account
import soko.ekibun.acg.data.DarkMode
import soko.ekibun.acg.data.ThemeSettings
import soko.ekibun.acg.ui.comp.Avatar
import soko.ekibun.acg.ui.theme.AppTheme

/** 侧栏与底栏的分界，M3 medium 的起点。 */
private val RailBreakpoint = 600.dp

/**
 * 导航条的两副面孔：<600dp 底栏、≥600dp 侧栏。
 *
 * 两档都只有 tab 与账号入口；设置与登出在账号面板里（见 `HomeScreen` 的 `AccountPopup`）。
 * 两档共用 [NavigationBarDefaults.containerColor]：M3 默认给侧栏 surface、底栏
 * surfaceContainer，并排摆会像两种层次。
 */
@Composable
fun HomeTabLayout(
  selected: HomeTab,
  currentAccount: Account?,
  onSelect: (HomeTab) -> Unit,
  onOpenAccount: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // 换档换的是导航条本身，内容要跨档保活：窗口从底栏拉宽到侧栏时，页面状态不能丢
  val currentContent by rememberUpdatedState(content)
  val contentSlot = remember { movableContentOf { currentContent() } }

  BoxWithConstraints(modifier.fillMaxSize()) {
    when {
      maxWidth >= RailBreakpoint -> {
        Row(Modifier.fillMaxSize()) {
          NavigationRail(containerColor = NavigationBarDefaults.containerColor) {
            Spacer(Modifier.height(8.dp))
            HomeTab.entries.forEach { tab ->
              NavigationRailItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(tab.title) },
              )
            }
            Spacer(Modifier.weight(1f))
            // 80dp 是 M3 NavigationRail 的栏宽：包一层定宽 Box，长昵称只会省略，不会把栏顶开
            Box(Modifier.width(80.dp), contentAlignment = Alignment.Center) {
              AccountItem(currentAccount, onOpenAccount)
            }
            Spacer(Modifier.height(4.dp))
          }
          Box(Modifier.weight(1f)) { contentSlot() }
        }
      }

      else -> {
        Column(Modifier.fillMaxSize()) {
          Box(Modifier.weight(1f)) { contentSlot() }
          NavigationBar {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
              AccountItem(currentAccount, onOpenAccount)
            }
            HomeTab.entries.forEach { tab ->
              NavigationBarItem(
                modifier = Modifier.weight(1f),
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(tab.title) },
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 账号入口：头像 + 用户名，图标在上、文字在下；点了弹面板。宽度由调用处给 —— 侧栏包一层 80dp 的
 * Box（栏宽固定），底栏每项由 `weight(1f)` 定宽。
 */
@Composable
private fun AccountItem(
  currentAccount: Account?,
  onClick: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .clip(MaterialTheme.shapes.medium)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (currentAccount == null) {
      Icon(
        painterResource(Res.drawable.ic_account_circle),
        contentDescription = null,
        modifier = Modifier.size(28.dp),
      )
    } else {
      Avatar(currentAccount.name, currentAccount.avatar)
    }
    Spacer(Modifier.height(4.dp))
    Text(
      currentAccount?.name ?: "账号",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** 档位由窗口宽决定，预览里只能靠 widthDp 摆：窄的一档底栏，宽的一档侧栏（1280 也只是更宽的侧栏）。 */
@Preview(name = "Compact 400dp", widthDp = 400, heightDp = 800)
@Composable
private fun TabCompactPreview() {
  AppTheme(ThemeSettings(darkMode = DarkMode.Light)) { TabPreview() }
}

@Preview(name = "Medium 800dp", widthDp = 800, heightDp = 800)
@Composable
private fun TabMediumPreview() {
  AppTheme(ThemeSettings(darkMode = DarkMode.Light)) { TabPreview() }
}

@Preview(name = "Expanded 1280dp", widthDp = 1280, heightDp = 800)
@Composable
private fun TabExpandedPreview() {
  AppTheme(ThemeSettings(darkMode = DarkMode.Light)) { TabPreview() }
}

@Composable
private fun TabPreview() {
  var selected by remember { mutableStateOf(HomeTab.Timetable) }
  HomeTabLayout(
    selected = selected,
    currentAccount = Account(site = "bgm", id = "ekibun", name = "ekibun", session = ""),
    onSelect = { selected = it },
    onOpenAccount = {},
  ) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("内容区", style = MaterialTheme.typography.titleMedium)
      }
    }
  }
}
