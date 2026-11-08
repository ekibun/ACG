package soko.ekibun.acg.ui.screen.home.tab

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_bookmark
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import soko.ekibun.acg.ui.comp.EmptyState

/** 收藏：类型页签、状态筛选、条目列表，都要从头写。 */
@Composable
fun CollectionScreen(modifier: Modifier = Modifier) {
  EmptyState(
    icon = Res.drawable.ic_bookmark,
    title = "收藏还没接",
    description = "登录、类型页签、状态筛选与列表都要重写",
    modifier = modifier.fillMaxSize(),
  )
}
