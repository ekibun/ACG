package soko.ekibun.acg.ui.screen.home.tab

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_calendar_month
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import soko.ekibun.acg.ui.comp.EmptyState

/** 放送时间表：按星期排番剧的周表。数据要等插件的放送接口。 */
@Composable
fun TimetableScreen(modifier: Modifier = Modifier) {
  EmptyState(
    icon = Res.drawable.ic_calendar_month,
    title = "时间表还没接",
    description = "周表要等插件的放送数据",
    modifier = modifier,
  )
}
