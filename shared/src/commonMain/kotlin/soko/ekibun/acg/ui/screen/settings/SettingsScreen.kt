package soko.ekibun.acg.ui.screen.settings

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_arrow_back
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource

/**
 * 设置页：分组标题 + 一张 `surfaceContainerHigh` 圆角卡片装条目，卡片内用分割线隔开。
 *
 * 比裸 ListItem 好在边界清楚 —— 设置项多起来以后，分组之间的留白就是唯一的层次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("设置") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = "返回")
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
      SettingGroup(title = "外观") {
        SettingRow(headline = "主题色", supporting = "还没接")
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        SettingRow(headline = "亮暗", supporting = "还没接")
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun SettingGroup(
  title: String,
  content: @Composable () -> Unit,
) {
  Column {
    Text(
      title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
    Surface(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Column(content = { content() })
    }
  }
}

@Composable
private fun SettingRow(
  headline: String,
  supporting: String,
) {
  ListItem(
    // 卡片自己有底色，条目必须透明，否则每行都会铺一层 surface 把卡片盖掉
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    headlineContent = { Text(headline) },
    supportingContent = {
      Text(
        supporting,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
  )
}
