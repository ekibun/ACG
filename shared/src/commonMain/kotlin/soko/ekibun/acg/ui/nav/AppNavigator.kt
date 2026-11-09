package soko.ekibun.acg.ui.nav

import androidx.compose.runtime.mutableStateListOf

/**
 * 主页之上打开的页面。
 *
 * 只描述「打开了什么」，不描述「长什么样」：内容归 ui 层，导航不认识 Compose，也不认识 tab。
 * 加一个页面就在这里加一个成员，然后在 App 的 `when` 里加一条分支。
 *
 * [key] 是保存页面状态用的稳定标识（Android 只收能进 Bundle 的键，见
 * `docs/development/shared-layer.md` §5.8）。带参数的页面把参数拼进来（`"player/<id>"`），
 * 不同参数各有各的状态。
 */
sealed interface AppRoute {
  val key: String

  data object Settings : AppRoute {
    override val key: String get() = "settings"
  }
}

/**
 * 独立页面的栈，与主页的 tab 互不相干：切 tab 不动这里，打开页面才压栈。
 *
 * 栈空就是在主页。
 */
class AppNavigator {
  private val stack = mutableStateListOf<AppRoute>()

  /** 栈顶页面；null 表示在主页。 */
  val current: AppRoute? get() = stack.lastOrNull()

  fun open(route: AppRoute) {
    if (route != current) stack.add(route)
  }

  /** 关掉栈顶；栈空时返回 false（界面按 [current] 判断要不要拦，不看返回值）。 */
  fun back(): Boolean {
    if (stack.isEmpty()) return false
    stack.removeAt(stack.lastIndex)
    return true
  }
}
