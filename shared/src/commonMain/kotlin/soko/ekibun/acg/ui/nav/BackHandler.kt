package soko.ekibun.acg.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * 系统返回：Android 的返回键 / 返回手势，桌面端是 Esc —— 两端在 Compose 里已接成同一个事件。
 *
 * 同一份组合里可以放多个，**后组合的先收到**，所以页面里的比 `App` 那层先拿到。[enabled] 为 false
 * 时不上报，继续往更早注册的那些找，最后是平台默认。不要用 `if` 决定调不调 —— 组合顺序一变，谁先
 * 收到就跟着变。
 *
 * **不要在 `Popup` / `Dialog` 的内容里用**：Android 上那是独立窗口，取不到返回事件的 owner，组合到
 * 这里会当场抛；判据放宿主。见 `docs/development/shared-layer.md` §5.7。
 */
@Composable
fun BackHandler(
  enabled: Boolean = true,
  onBack: () -> Unit,
) {
  // 每个 handler 必须独占一份 state（复用同一个会抛 IllegalArgumentException），所以只在内部建。
  val state = rememberNavigationEventState(NavigationEventInfo.None)
  NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = onBack)
}
