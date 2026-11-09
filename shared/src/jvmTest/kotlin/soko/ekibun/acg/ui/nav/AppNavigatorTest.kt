package soko.ekibun.acg.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `AppNavigator.kt`：独立页面的栈语义 —— 压栈去重、逐层退、栈空不做事。 */
class AppNavigatorTest {
  /** 打开设置是一层，返回即回主页；栈空再按返回不做事。 */
  @Test(timeout = 60000)
  fun stackPopsOneLevelAtATime() {
    val navigator = AppNavigator()
    assertNull(navigator.current, "a fresh navigator is on the home page")

    navigator.open(AppRoute.Settings)
    assertEquals(AppRoute.Settings, navigator.current)
    assertTrue(navigator.back(), "back on a non-empty stack pops")
    assertNull(navigator.current, "popping the last page lands on home")
    assertFalse(navigator.back(), "back on home reports nothing to pop")
  }

  /** 同一页面连点两次不该压两层 —— 那要按两次返回才回得去。 */
  @Test(timeout = 60000)
  fun openingTheCurrentPageKeepsOneLevel() {
    val navigator = AppNavigator()
    navigator.open(AppRoute.Settings)
    navigator.open(AppRoute.Settings)

    assertTrue(navigator.back())
    assertNull(navigator.current, "duplicate open must not stack twice")
  }
}
