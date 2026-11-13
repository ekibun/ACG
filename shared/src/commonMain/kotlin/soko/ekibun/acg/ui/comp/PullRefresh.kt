package soko.ekibun.acg.ui.comp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerType

/**
 * 下拉刷新：只认手指，鼠标与滚轮不参与。
 *
 * 材料的 `pullToRefresh` 只看 `source == UserInput`，而 `NestedScrollSource.Wheel` 就是它的别名 ——
 * 滚轮拖出来的指示器收不回去。拦法是在它**内层**挂 [TouchOnlyOverscroll] 吃掉非触摸的余量
 * （挂外面拿到的已是被扣过的量），判据读全应用一份的 [LocalActiveInputSource]，不按平台写死。
 *
 * 指示器不按输入方式隐藏：桌面上没有下拉手势，顶栏刷新那个圈用的也是它。推演见
 * `docs/development/shared-layer.md` §5.11。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshBox(
  isRefreshing: Boolean,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  state: PullToRefreshState = rememberPullToRefreshState(),
  content: @Composable BoxScope.() -> Unit,
) {
  val input = LocalActiveInputSource.current
  val touchOnly = remember(input) { TouchOnlyOverscroll(input) }
  Box(
    modifier
      // 指示器静止时整个平移到框上方（`translationY = -高度`），不裁就会画到上面那排筛选上
      .clipToBounds()
      .pullToRefresh(isRefreshing = isRefreshing, state = state, onRefresh = onRefresh)
      .nestedScroll(touchOnly),
  ) {
    content()
    PullToRefreshDefaults.Indicator(
      state = state,
      isRefreshing = isRefreshing,
      modifier = Modifier.align(Alignment.TopCenter),
    )
  }
}

/** 手指以外（滚轮、触控板、鼠标拖动）多出来的下拉位移在这里吃掉；挂在 `pullToRefresh` 之后才是内层。 */
private class TouchOnlyOverscroll(
  private val input: ActiveInputSource,
) : NestedScrollConnection {
  override fun onPostScroll(
    consumed: Offset,
    available: Offset,
    source: NestedScrollSource,
  ): Offset {
    if (source == NestedScrollSource.UserInput && input.latest != PointerType.Touch && available.y > 0f) {
      return Offset(0f, available.y)
    }
    return Offset.Zero
  }
}
