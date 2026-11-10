package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.common.TestEnv
import soko.ekibun.common.TestServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Webview2Context] 的直构用例：导航后 `evaluate` 能把脚本结果送回调用方。 */
class Webview2ContextTest {
  private lateinit var pageServer: TestServer

  @BeforeTest
  fun startServer() {
    TestEnv.ensurePaths()
    pageServer = TestServer(pages = true)
  }

  @AfterTest
  fun stopServer() {
    pageServer.close()
  }

  private fun url(path: String): String = pageServer.url(path)

  private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(60_000) { block() }

  /** 导航后 `evaluate` 返回脚本 JSON；无返回值的脚本得到字符串 `"null"`。 */
  @Test(timeout = 120000)
  fun visibleViewEvaluateReturnsScriptResult() =
    runBlocking {
      TestEnv.skipUnlessHostAvailable()

      val ctx = Webview2Context()
      val loaded = CompletableDeferred<Unit>()
      ctx.onEvent = { event -> if (event is WebViewEvent.PageFinished) loaded.complete(Unit) }
      try {
        // 没 attach 就没有父窗口客户区可用，尺寸得自己给
        ctx.setBounds(800, 600)
        ctx.navigate(url("/page.html"))
        guarded { loaded.await() }

        val json = ctx.evaluate("({ title: document.title, ready: document.readyState })")
        assertTrue(json != null, "evaluate must return a result, got null (timeout or script failure)")
        assertTrue(json.contains("\"title\":\"acg-test\""), "the page must be the one we served: $json")
        assertTrue(json.contains("\"ready\":\"complete\""), "the page should have finished loading: $json")

        // 无返回值的形态是字符串 "null"；Kotlin null 只表示失败
        assertEquals(
          "null",
          ctx.evaluate("undefined"),
          "a script without a return value should yield the JSON string null",
        )
      } finally {
        ctx.close()
      }
    }

  /** 导航前回调命中时取消本次导航，页面仍停在原地址。 */
  @Test(timeout = 120000)
  fun onNavigateCancelsThePendingNavigation() =
    runBlocking {
      TestEnv.skipUnlessHostAvailable()

      val ctx = Webview2Context()
      val loaded = CompletableDeferred<Unit>()
      ctx.onEvent = { event -> if (event is WebViewEvent.PageFinished) loaded.complete(Unit) }
      try {
        ctx.setBounds(800, 600)
        ctx.navigate(url("/page.html"))
        guarded { loaded.await() }
        val before = ctx.evaluate("location.href")
        assertTrue(before?.contains("page.html") == true, "the first page must load: $before")

        val decided = CompletableDeferred<String>()
        ctx.onNavigate = { target ->
          decided.complete(target)
          true
        }
        ctx.navigate(url("/other.html"))
        assertEquals(url("/other.html"), guarded { decided.await() }, "the hook sees the pending url")
        assertEquals(before, ctx.evaluate("location.href"), "a cancelled navigation leaves the document alone")
      } finally {
        ctx.close()
      }
    }
}
