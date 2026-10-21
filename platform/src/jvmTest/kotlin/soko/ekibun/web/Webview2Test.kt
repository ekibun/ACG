package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Webview2Context] 的直构用例：导航后 `evaluate` 能把脚本结果送回调用方。 */
class Webview2Test {
  private lateinit var pageServer: TestPageServer

  @BeforeTest
  fun startServer() {
    TestPaths.ensureInitialized()
    pageServer = TestPageServer()
  }

  @AfterTest
  fun stopServer() {
    pageServer.close()
  }

  private fun url(path: String): String = pageServer.url(path)

  private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(60_000) { block() }

  /** 导航后 `evaluate` 返回脚本 JSON；无返回值的脚本得到字符串 `"null"`。 */
  @Test
  fun visibleViewEvaluateReturnsScriptResult() =
    runBlocking {
      skipUnlessHostIsAvailable(webviewHostHealth())

      val ctx = Webview2Context()
      val loaded = CompletableDeferred<Unit>()
      ctx.onEvent = { event -> if (event is WebViewEvent.PageFinished) loaded.complete(Unit) }
      try {
        // 没 attach 就没有父窗口客户区可用，尺寸得自己给
        ctx.setBounds(800, 600)
        ctx.navigate(url("/page.html"))
        guarded { loaded.await() }

        val json = ctx.evaluate("({ sub: !!window.__subLoaded, imgs: document.images.length })")
        assertTrue(json != null, "evaluate must return a result, got null (timeout or script failure)")
        assertTrue(json.contains("\"sub\":true"), "the external script should have run: $json")
        assertTrue(json.contains("\"imgs\":1"), "the image subresource should be in the DOM: $json")

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
}
