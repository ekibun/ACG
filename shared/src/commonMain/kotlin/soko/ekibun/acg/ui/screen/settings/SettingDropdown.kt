package soko.ekibun.acg.ui.screen.settings

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_check
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource

/**
 * 设置里的下拉项：左边标题，右边当前值与箭头，点开在值下方弹选项，当前项打勾。
 *
 * 菜单锚在右侧那段文字上而不是整行 —— 它只要内容宽，漂在值下面；
 * 又因为默认从锚点左边展开，量出两者宽度差把菜单左移，右边缘才与值对齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingDropdown(
  headline: String,
  selected: T,
  options: List<Pair<T, String>>,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  var anchorWidth by remember { mutableIntStateOf(0) }
  var menuWidth by remember { mutableIntStateOf(0) }

  ListItem(
    modifier = modifier,
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    headlineContent = { Text(headline) },
    trailingContent = {
      Box(Modifier.onSizeChanged { anchorWidth = it.width }) {
        TextButton(
          onClick = { expanded = true },
          // 右侧不留内边距：值要贴着 ListItem 自己的边距
          contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 0.dp, bottom = 8.dp),
          colors =
            ButtonDefaults.textButtonColors(
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
          Text(options.firstOrNull { it.first == selected }?.second.orEmpty())
          ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        DropdownMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
          offset =
            with(LocalDensity.current) {
              DpOffset(x = (anchorWidth - menuWidth).toDp(), y = 0.dp)
            },
          modifier = Modifier.onSizeChanged { menuWidth = it.width },
        ) {
          options.forEach { (value, label) ->
            DropdownMenuItem(
              text = { Text(label) },
              onClick = {
                onSelect(value)
                expanded = false
              },
              trailingIcon = {
                if (value == selected) {
                  Icon(painterResource(Res.drawable.ic_check), contentDescription = null)
                }
              },
            )
          }
        }
      }
    },
  )
}
