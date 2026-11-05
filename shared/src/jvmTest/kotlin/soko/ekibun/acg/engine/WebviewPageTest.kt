package soko.ekibun.acg.engine

import soko.ekibun.web.WebViewEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 标题栏的三样状态怎么从平台事件折出来。 */
class WebviewPageTest {
  private val idle = WebviewPage(url = "https://bgm.tv/")

  @Test
  fun newTaskIsNotLoading() {
    assertEquals(1f, idle.progress, "nothing is loading until the page reports PageStarted")
  }

  @Test
  fun startingResetsProgressAndFinishingSettlesIt() {
    val started = idle.reduce(WebViewEvent.PageStarted)
    assertEquals(0f, started.progress)

    val half = started.reduce(WebViewEvent.Progress(0.4f))
    assertEquals(0.4f, half.progress)

    val done = half.reduce(WebViewEvent.PageFinished)
    assertEquals(1f, done.progress)
  }

  @Test
  fun progressIsClampedToOne() {
    assertEquals(1f, idle.reduce(WebViewEvent.Progress(1.4f)).progress)
  }

  @Test
  fun navigationUpdatesTheAddress() {
    val moved = idle.reduce(WebViewEvent.UrlChanged("https://bgm.tv/login"))

    assertEquals("https://bgm.tv/login", moved.url)
  }

  @Test
  fun blankTitleKeepsTheFallbackAddress() {
    assertNull(idle.reduce(WebViewEvent.TitleChanged("   ")).title)
    assertEquals("Bangumi 番组计划", idle.reduce(WebViewEvent.TitleChanged("Bangumi 番组计划")).title)
  }

  @Test
  fun unrelatedEventsChangeNothing() {
    assertEquals(idle, idle.reduce(WebViewEvent.LoadFailed("boom")))
    assertEquals(idle, idle.reduce(WebViewEvent.DocumentLoaded("https://bgm.tv/")))
  }
}
