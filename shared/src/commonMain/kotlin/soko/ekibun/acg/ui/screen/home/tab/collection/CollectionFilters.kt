package soko.ekibun.acg.ui.screen.home.tab.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 类型 tab 换排法的宽度。[BoxWithConstraints] 量的是内容区：窗口到 600 时导航换成 80dp 侧栏，
 * 这里只剩 520，所以不沿用那个 600。
 */
private val TabRowBreakpoint = 520.dp

/** 类型是主维度给成 tab，收藏状态是次维度给成 chip；只有一个类型（或插件只给一档）时 tab 那排不出现。 */
@Composable
internal fun CollectionFilters(
  keys: List<String>,
  selected: String,
  onSelect: (String) -> Unit,
) {
  val type = selected.substringBefore('|')
  val types = keys.map { it.substringBefore('|') }.distinct()
  val states = keys.filter { it.substringBefore('|') == type }

  Column {
    if (types.size > 1) {
      val index = types.indexOf(type)
      val tabs: @Composable () -> Unit = {
        types.forEach { pickedType ->
          Tab(
            selected = pickedType == type,
            // 换类型就落到这一类的第一个状态：键的第一段是类型，插件给的顺序就是默认
            onClick = { onSelect(keys.first { it.substringBefore('|') == pickedType }) },
            text = { Text(pickedType) },
          )
        }
      }
      // 够宽按文字宽靠左排，窄了横向均分，右边不留半行
      BoxWithConstraints {
        if (maxWidth >= TabRowBreakpoint) {
          PrimaryScrollableTabRow(
            selectedTabIndex = index,
            // 默认 52dp 是「右边还能滚」的提示，与下面 chip 的 16dp 对不齐
            edgePadding = 16.dp,
            // 默认最小 90dp 会把两个字的类型撑成一格；宽度跟文字走，排不下才滚
            minTabWidth = 48.dp,
            // 下面紧跟着状态 chip，再加分割线两层都显重
            divider = {},
            tabs = tabs,
          )
        } else {
          PrimaryTabRow(selectedTabIndex = index, divider = {}, tabs = tabs)
        }
      }
    }
    if (states.size > 1) {
      FilterRow(values = states, selected = selected, onSelect = onSelect)
    }
  }
}

/** 一排状态 chip：横向滚动，chip 只显示 `|` 后面那段，传回给上层的仍是整键。 */
@Composable
private fun FilterRow(
  values: List<String>,
  selected: String,
  onSelect: (String) -> Unit,
) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
  ) {
    items(values) { value ->
      FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        label = { Text(value.substringAfter('|')) },
      )
    }
  }
}
