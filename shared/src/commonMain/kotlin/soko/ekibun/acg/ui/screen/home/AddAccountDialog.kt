package soko.ekibun.acg.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import soko.ekibun.acg.data.AccountRepository
import soko.ekibun.acg.data.LoginPlugin
import soko.ekibun.acg.ui.nav.BackHandler

/** 添加账号：居中一张卡，列能登录的站点。登录期间不许关 —— 等待的协程活在这个组合里。 */
@Composable
fun AddAccountDialog(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var adding by remember { mutableStateOf<LoginPlugin?>(null) }
  var error by remember { mutableStateOf<String?>(null) }

  BackHandler(enabled = adding == null) { onDismiss() }

  fun add(plugin: LoginPlugin) {
    if (adding != null) return
    error = null
    adding = plugin
    scope.launch {
      try {
        AccountRepository.login(plugin)
        onDismiss()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // JSError 继承 Throwable 而不是 Exception：只捕 Exception 会漏出去，把应用带崩
        error = e.message ?: e.toString()
      } finally {
        adding = null
      }
    }
  }

  // 铺满窗口的一层：居中卡片，点外面关闭并挡住穿透
  Box(
    modifier
      .fillMaxSize()
      .clickable(interactionSource = null, indication = null) { if (adding == null) onDismiss() }
      .background(Color.Black.copy(alpha = 0.32f)),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      modifier =
        Modifier
          .widthIn(max = 360.dp)
          .clickable(interactionSource = null, indication = null, onClick = {}),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Column(Modifier.padding(vertical = 8.dp)) {
        Text(
          "添加账号",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
          "选一个站点，在它的登录页上登一次。登录完成后账号会出现在左下角。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        AccountRepository.loginPlugins.forEach { plugin ->
          ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(plugin.label) },
            supportingContent = { Text(plugin.site) },
            trailingContent = {
              if (adding == plugin) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
              }
            },
            modifier = Modifier.clickable(enabled = adding == null) { add(plugin) },
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
