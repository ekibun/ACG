package soko.ekibun.acg.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import soko.ekibun.quickjs.JSError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 任务注册表：登记、前台互斥、关闭、注销。 */
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

  @Test
  fun registerPutsTaskInTheList() {
    val first = task("https://a.example")
    WebviewTasks.register(first)

    assertEquals(listOf(first), WebviewTasks.all.value)
  }

  @Test
  fun onlyOneTaskSitsInTheForeground() {
    val first = task("https://a.example")
    val second = task("https://b.example")
    WebviewTasks.register(first)
    WebviewTasks.register(second)

    WebviewTasks.toForeground(first)
    assertSame(first, WebviewTasks.foreground.value)

    // 提第二个时，第一个自动退到后台，不会两个叠在一起
    WebviewTasks.toForeground(second)
    assertSame(second, WebviewTasks.foreground.value)
  }

  @Test
  fun toBackgroundClearsTheSlotAndArmsTheCountdown() {
    val only = task("https://a.example")
    WebviewTasks.register(only)
    WebviewTasks.toForeground(only)

    WebviewTasks.toBackground(only)
    assertNull(WebviewTasks.foreground.value)
    assertTrue(WebviewTasks.all.value.contains(only), "the task keeps running in the background")
    val armed = only.expiry != null

    // 提到前台必须把倒计时撤掉，否则用户正在操作时会被掐断
    WebviewTasks.toForeground(only)

    assertTrue(armed, "going to background must arm the 30s countdown")
    assertNull(only.expiry, "coming back to the foreground must disarm it")
  }

  @Test
  fun closingFailsTheWaitingCaller() {
    runBlocking {
      val only = task("https://a.example")
      WebviewTasks.register(only)
      val waiting = async { runCatching { only.outcome.await() } }

      WebviewTasks.close(only)

      assertIs<JSError>(waiting.await().exceptionOrNull(), "closing must not look like success")
    }
  }

  @Test
  fun foregroundOnlyTaskRefusesToGoToBackground() {
    val login = task("https://bgm.tv/login", foregroundOnly = true)
    WebviewTasks.register(login)
    WebviewTasks.toForeground(login)

    WebviewTasks.toBackground(login)

    assertSame(login, WebviewTasks.foreground.value, "a foreground-only task has no background to go to")
    assertNull(login.expiry, "and it must not get the 30s countdown either")
  }

  @Test
  fun foregroundOnlyTaskKeepsTheSlotAgainstALaterCaller() {
    val login = task("https://bgm.tv/login", foregroundOnly = true)
    val other = task("https://other.example")
    WebviewTasks.register(login)
    WebviewTasks.register(other)
    WebviewTasks.toForeground(login)

    WebviewTasks.toForeground(other)

    assertSame(login, WebviewTasks.foreground.value, "the user is right in the middle of the login page")
  }

  @Test
  fun unregisterRemovesTaskAndForeground() {
    val only = task("https://a.example")
    WebviewTasks.register(only)
    WebviewTasks.toForeground(only)

    WebviewTasks.unregister(only)

    assertEquals(emptyList(), WebviewTasks.all.value)
    assertNull(WebviewTasks.foreground.value)
  }
}
