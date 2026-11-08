package soko.ekibun.acg.ui.nav

import androidx.compose.runtime.mutableStateListOf

/**
 * 主页之上打开的页面。
 *
 * 只描述「打开了什么」，不描述「长什么样」：内容归 ui 层，导航不认识 Compose，也不认识 tab。
 * 加一个页面就在这里加一个成员，然后在 App 的 `when` 里加一条分支。
 */
sealed interface AppRoute {
  data object Settings : AppRoute
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

  /** 关掉栈顶；已经在主页时返回 false，交给平台去退出应用。 */
  fun back(): Boolean {
    if (stack.isEmpty()) return false
    stack.removeAt(stack.lastIndex)
    return true
  }
}
