package soko.ekibun.acg.ui.screen.home

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import soko.ekibun.acg.data.DarkMode
import soko.ekibun.acg.data.ThemeSettings
import soko.ekibun.acg.ui.comp.MonogramAvatar
import soko.ekibun.acg.ui.theme.AppTheme

/** 侧栏与底栏的分界，M3 medium 的起点；HomeScreen 判面板里是否给「设置」时也用这个值。 */
internal val RailBreakpoint = 600.dp

/**
 * 导航条的两副面孔：<600dp 底栏、≥600dp 侧栏。
 *
 * 两档对「设置」的处理不同 —— 底栏塞不下第六个入口，所以设置留在账号面板里；
 * 侧栏是竖的，设置就单独给一个按钮压在账号上方，面板里不再重复。
 * 两档共用 [NavigationBarDefaults.containerColor]：M3 默认给侧栏 surface、底栏 surfaceContainer，并排摆会像两种层次。
 */
@Composable
fun HomeTabLayout(
  selected: HomeTab,
  currentUser: String,
  onSelect: (HomeTab) -> Unit,
  onOpenAccount: () -> Unit,
  onOpenSettings: () -> Unit,
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
            SettingsItem(onOpenSettings)
            AccountItem(currentUser, onOpenAccount)
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
              AccountItem(currentUser, onOpenAccount)
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

/** 账号入口：首字母头像 + 用户名，和旁边的 tab 项一样「图标在上、文字在下」。不是页面，点了弹面板。 */
@Composable
private fun AccountItem(
  currentUser: String,
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
    MonogramAvatar(currentUser, size = 24.dp)
    Spacer(Modifier.height(4.dp))
    Text(
      currentUser,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SettingsItem(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(painterResource(Res.drawable.ic_settings), contentDescription = "设置")
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
    currentUser = "ekibun",
    onSelect = { selected = it },
    onOpenAccount = {},
    onOpenSettings = {},
  ) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("内容区", style = MaterialTheme.typography.titleMedium)
      }
    }
  }
}
