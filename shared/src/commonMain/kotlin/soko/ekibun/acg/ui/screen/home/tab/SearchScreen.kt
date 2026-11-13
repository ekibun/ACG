package soko.ekibun.acg.ui.screen.home.tab

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_search
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import soko.ekibun.acg.ui.comp.EmptyState

/** 搜索：输入框已就位，查询还没接 —— 结果要来自各插件的数据源。 */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
  var query by rememberSaveable { mutableStateOf("") }

  Column(modifier.fillMaxSize()) {
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      placeholder = { Text("番剧、角色、用户") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    EmptyState(
      icon = Res.drawable.ic_search,
      title = "搜索还没接",
      description = "结果要来自各插件的数据源",
      modifier = Modifier.weight(1f),
    )
  }
}
