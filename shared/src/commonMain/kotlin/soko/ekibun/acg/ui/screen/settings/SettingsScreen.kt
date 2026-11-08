package soko.ekibun.acg.ui.screen.settings

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_arrow_back
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import soko.ekibun.acg.data.DarkMode
import soko.ekibun.acg.data.SettingsRepository
import soko.ekibun.acg.ui.comp.ColorPickerDialog

/** 亮暗四档的文案。纯黑是深色的变体，所以排在深色后面。 */
private val DarkModeOptions =
  listOf(
    DarkMode.Auto to "跟随系统",
    DarkMode.Light to "浅色",
    DarkMode.Dark to "深色",
    DarkMode.Black to "纯黑",
  )

/**
 * 设置页：现在只有外观 —— 主题色（取色器）与亮暗。
 *
 * 数据直接读写 [SettingsRepository] —— 设置是全应用一份，不需要从外面传进来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val settings by SettingsRepository.settings.collectAsState()
  val scope = rememberCoroutineScope()
  var picking by remember { mutableStateOf(false) }

  fun setDarkMode(mode: DarkMode) {
    scope.launch {
      SettingsRepository.update { it.copy(theme = it.theme.copy(darkMode = mode)) }
    }
  }

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
        ListItem(
          colors = ListItemDefaults.colors(containerColor = Color.Transparent),
          headlineContent = { Text("主题色") },
          trailingContent = {
            Box(
              Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(settings.theme.seedColor)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
          },
          modifier = Modifier.clickable { picking = true },
        )
        RowDivider()
        SettingDropdown(
          headline = "深色主题",
          selected = settings.theme.darkMode,
          options = DarkModeOptions,
          onSelect = ::setDarkMode,
        )
      }
      Spacer(Modifier.height(24.dp))
    }
  }

  if (picking) {
    ColorPickerDialog(
      initial = settings.theme.seedColor,
      onDismiss = { picking = false },
      onConfirm = { color ->
        picking = false
        scope.launch {
          SettingsRepository.update { it.copy(theme = it.theme.copy(seedColor = color)) }
        }
      },
    )
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
private fun RowDivider() {
  HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
