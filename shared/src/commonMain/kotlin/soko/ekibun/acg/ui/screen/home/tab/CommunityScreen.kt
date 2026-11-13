package soko.ekibun.acg.ui.screen.home.tab

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_forum
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import soko.ekibun.acg.ui.comp.EmptyState

/** 社区：超展开与讨论。要接站点的帖子接口。 */
@Composable
fun CommunityScreen(modifier: Modifier = Modifier) {
  EmptyState(
    icon = Res.drawable.ic_forum,
    title = "社区还没接",
    description = "超展开与讨论要等站点接口",
    modifier = modifier,
  )
}
