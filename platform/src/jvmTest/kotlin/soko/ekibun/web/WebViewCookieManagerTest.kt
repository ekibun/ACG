package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [WebView2CookieManager] 这条产品链路的回归测试。
 *
 * 背景：插件 JS 的 cookie 读写在桌面端桥到 WebView2 的 cookie 库。[WebView2CookieManager] 内部
 * **惰性缓存一个 [Webview2Context]**（第一次用时才建，之后所有读写都走它），不再「每次读 / 写
 * 都现建一个上下文、用完即关」。上下文自己的 `ICoreWebView2CookieManager` 在 controller
 * 就绪时一次取好缓存在 native 侧，随取随用。这条路能成立的前提是：
 *
 * **manager 属于 profile，且共享给同一 environment 下所有 webview。**
 *
 * 本类就钉这一条，以及它带来的后果：经 [WebView2CookieManager.set] 写下的 cookie，会被一个
 * **全新的、独立的** webview 在加载页面时看到（`document.cookie`）—— 即「别的 webview
 * 捕捉得到」。往返本身（`set` → `get`）是这条用例的前半段。
 *
 * 用例写的是**会话 cookie**（不带 max-age）：只要同一个 environment 的浏览器进程还
 * 活着就读得到，覆盖本类要钉的行为足够；要验跨 webview 生命周期的持久性得用持久
 * cookie（会话 cookie 只活在浏览器进程内存里，坑见
 * [soko.ekibun.acg.engine.WebViewHostTest.cookiesAreSharedAcrossTasks] 的 KDoc）。
 *
 * **不自己调 [Webview2Context.prepareEnvironment]**：伴生对象的 `init` 已经用产品那套
 * （浏览器参数 + user data folder）备好了环境；再调一次 `prepareEnvironment(null, null)` 会把
 * **浏览器参数清空**、并把 user data folder 换成 WebView2 的默认值 —— 那就不是在测产品配置了。
 *
 * 宿主不可用（WebView2 运行时坏）时跳过（记成 skipped，不是 passed）：先跑一次
 * [webviewHostHealth] 体检（直构 [Webview2Context]、真导航一个 `about:blank`），跟
 * `:shared` 那边的 `WebViewHostTest` 一个口径 —— 静默跳过会让人误以为「验过了」。
 */
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

  /** 轮询到条件成立或超时；AddOrUpdateCookie 是异步提交的，读得等一下（实测首轮即中）。 */
  private suspend fun pollUntil(cond: suspend () -> Boolean): Boolean {
    repeat(5) {
      if (cond()) return true
      delay(20L * (it + 1))
    }
    return cond()
  }

  /**
   * 经产品路径 [WebView2CookieManager.set] 写下的 cookie，被一个**全新的、独立的**
   * webview 在加载页面时看到 —— 即「别的 webview 捕捉得到」。
   *
   * 前半段先确认它落进了共享存储（`set` → `get` 往返，AddOrUpdateCookie 是异步提交的），
   * 后半段用另一个 [Webview2Context] 真的导航到该 url，页面加载完后读 `document.cookie`
   * （非 httpOnly 的 cookie 会出现在里面）。
   */
  @Test
  fun cookieWrittenViaStorageIsSeenByFreshWebView() =
    runBlocking {
      webViewReady()
      val pageUrl = url("/page.html")
      val name = "acg_xctx"
      val value = "captured"

      val storage = WebView2CookieManager

      // 走产品路径写 cookie（非 secure / 非 httpOnly，http 源才发得出来、document.cookie 才看得见）。
      storage.set(pageUrl, "$name=$value; Domain=127.0.0.1; Path=/")

      // 先确认它落进了共享存储：缓存上下文自己的 cookie manager 读得回来。
      val stored =
        withTimeout(READ_BACK_TIMEOUT_MS) {
          pollUntil {
            storage.get(pageUrl).contains("$name=$value")
          }
        }
      assertTrue(stored, "a cookie written via set should be readable from the shared store")

      // 一个全新的、独立的 webview 导航到该 url，页面跑完后看 document.cookie。
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
