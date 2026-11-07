package soko.ekibun.acg.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import soko.ekibun.quickjs.JSError
import soko.ekibun.web.WebViewEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `WebviewTask.kt` 两半：任务注册表（互斥、倒计时、关闭）与 `WebviewPage.reduce` 的进度 / 地址 / 标题。 */
class WebviewTaskTest {
  private val created = mutableListOf<WebviewTask>()

  private fun task(
    url: String,
    foregroundOnly: Boolean = false,
  ): WebviewTask = WebviewTask(url, foregroundOnly = foregroundOnly).also { created += it }

  @AfterTest
  fun clear() {
    created.forEach(WebviewTasks::unregister)
    assertEquals(emptyList(), WebviewTasks.all.value)
    assertNull(WebviewTasks.foreground.value)
  }

  /** 注册 → 前台 → 后台挂倒计时 → 回前台撤倒计时 → 注销。倒计时没撤掉会在用户操作时掐断页面。 */
  @Test(timeout = 60000)
  fun registryTracksForegroundAndCountdown() {
    val first = task("https://a.example")
    val second = task("https://b.example")
    WebviewTasks.register(first)
    assertEquals(listOf(first), WebviewTasks.all.value, "register puts the task in the list")

    WebviewTasks.register(second)
    WebviewTasks.toForeground(first)
    assertSame(first, WebviewTasks.foreground.value)

    // 提第二个时，第一个自动退到后台，不会两个叠在一起
    WebviewTasks.toForeground(second)
    assertSame(second, WebviewTasks.foreground.value, "only one task sits in the foreground")

    WebviewTasks.toBackground(second)
    assertNull(WebviewTasks.foreground.value, "going to background clears the slot")
    assertTrue(WebviewTasks.all.value.contains(second), "the task keeps running in the background")
    assertTrue(second.expiry != null, "going to background must arm the 30s countdown")

    WebviewTasks.toForeground(second)
    assertNull(second.expiry, "coming back to the foreground must disarm the countdown")

    WebviewTasks.unregister(first)
    WebviewTasks.unregister(second)
    assertEquals(emptyList(), WebviewTasks.all.value, "unregister removes the task")
    assertNull(WebviewTasks.foreground.value, "…and clears the slot it held")
  }

  /** 登录这类页面没有「后台」可去：不缩成胶囊、不挂倒计时，也不因后来者让位。 */
  @Test(timeout = 60000)
  fun foregroundOnlyTaskStaysInTheForeground() {
    val login = task("https://login.example/", foregroundOnly = true)
    val other = task("https://other.example")
    WebviewTasks.register(login)
    WebviewTasks.register(other)
    WebviewTasks.toForeground(login)

    WebviewTasks.toBackground(login)
    assertSame(login, WebviewTasks.foreground.value, "a foreground-only task has no background to go to")
    assertNull(login.expiry, "and it must not get the 30s countdown either")

    WebviewTasks.toForeground(other)
    assertSame(login, WebviewTasks.foreground.value, "the user is right in the middle of the login page")
  }

  /** 关掉任务不能让等结果的插件脚本看起来像成功。 */
  @Test(timeout = 60000)
  fun closingFailsTheWaitingCaller() {
    runBlocking {
      val only = task("https://a.example")
      WebviewTasks.register(only)
      val waiting = async { runCatching { only.outcome.await() } }

      WebviewTasks.close(only)

      assertIs<JSError>(waiting.await().exceptionOrNull(), "closing must not look like success")
    }
  }

  // ---- WebviewPage：一个页面从打开到加载完的状态 ----

  /** 一个页面从打开到加载完：进度、地址、标题；坏掉不响的几处单独钉。 */
  @Test(timeout = 60000)
  fun loadingAPageDrivesProgressAddressAndTitle() {
    val home = "https://page.example/"
    val idle = WebviewPage(url = home)
    assertEquals(1f, idle.progress, "nothing is loading until the page reports PageStarted")

    // 正常流程：开始 → 加载中 → 完成，落定在 1
    val started = idle.reduce(WebViewEvent.PageStarted)
    val half = started.reduce(WebViewEvent.Progress(0.4f))
    val finished = half.reduce(WebViewEvent.PageFinished)
    assertEquals(1f, finished.progress, "PageFinished settles the progress at 1")

    // 页面自己跳转：地址跟着走
    val login = "https://page.example/login"
    assertEquals(login, finished.reduce(WebViewEvent.UrlChanged(login)).url, "a navigation updates the address")

    // 下面几条坏了都不会抛、不会崩，只表现成界面错位 —— 只有断言能钉住
    val overrun = idle.reduce(WebViewEvent.Progress(1.4f))
    assertEquals(1f, overrun.progress, "progress must clamp to 1, or the bar overflows")
    assertNull(
      idle.reduce(WebViewEvent.TitleChanged("   ")).title,
      "a blank title must fall back to the url, not paint blanks in the title bar",
    )
    assertEquals("示例站点", idle.reduce(WebViewEvent.TitleChanged("示例站点")).title)
    assertEquals(
      idle,
      idle.reduce(WebViewEvent.LoadFailed("boom")),
      "an event unrelated to the title bar must leave the state alone (LoadFailed resetting progress makes the bar jump)",
    )
    assertEquals(idle, idle.reduce(WebViewEvent.DocumentLoaded(home)))
  }
}
