package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** [WebView2CookieManager] 写下的 cookie 能被全新 webview 在 `document.cookie` 里看到；宿主不可用则跳过。 */
class WebViewCookieManagerTest {
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

  private fun webViewReady() = skipUnlessHostIsAvailable(hostHealth)

  /** 轮询到条件成立或超时。 */
  private suspend fun pollUntil(cond: suspend () -> Boolean): Boolean {
    repeat(5) {
      if (cond()) return true
      delay(20L * (it + 1))
    }
    return cond()
  }

  /** 经 `set` 写入的 cookie 落进共享存储（`set` → `get` 往返），并被另一个新建 webview 读到。 */
  @Test
  fun cookieWrittenViaStorageIsSeenByFreshWebView() =
    runBlocking {
      webViewReady()
      val pageUrl = url("/page.html")
      val name = "acg_xctx"
      val value = "captured"

      val storage = WebView2CookieManager

      // 走产品路径写 cookie（非 secure / 非 httpOnly）
      storage.set(pageUrl, "$name=$value; Domain=127.0.0.1; Path=/")

      // 先确认它落进了共享存储
      val stored =
        withTimeout(READ_BACK_TIMEOUT_MS) {
          pollUntil {
            storage.get(pageUrl).contains("$name=$value")
          }
        }
      assertTrue(stored, "a cookie written via set should be readable from the shared store")

      // 新建一个独立 webview 导航到该 url，读 document.cookie
      val ctx = Webview2Context()
      val loaded = CompletableDeferred<Unit>()
      ctx.onEvent = { event -> if (event is WebViewEvent.PageFinished) loaded.complete(Unit) }
      try {
        ctx.setBounds(800, 600)
        ctx.navigate(pageUrl)
        withTimeout(NAV_TIMEOUT_MS) { loaded.await() }

        val docCookies = ctx.evaluate("document.cookie")
        assertTrue(
          docCookies != null && docCookies.contains("$name=$value"),
          "after another webview loads the url, document.cookie should contain the cookie; got [$docCookies]",
        )
      } finally {
        ctx.close()
      }
    }

  private companion object {
    const val READ_BACK_TIMEOUT_MS = 12_000L
    const val NAV_TIMEOUT_MS = 20_000L

    /** 环境体检结果，`null` 表示健康。 */
    val hostHealth: String? by lazy { webviewHostHealth() }
  }
}
