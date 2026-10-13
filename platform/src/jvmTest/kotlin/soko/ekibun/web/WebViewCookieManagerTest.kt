package soko.ekibun.web

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [WebView2CookieManager] 这条产品链路的回归测试。
 *
 * 背景：ktor 的 cookie 读写在桌面端桥到 WebView2 的 cookie 库。[WebView2CookieManager] 内部
 * **惰性缓存一个 [Webview2Context]**（第一次用时才建，之后所有读写都走它），不再「每次读 / 写
 * 都现建一个上下文、用完即关」。上下文自己的 `ICoreWebView2CookieManager` 在 controller
 * 就绪时一次取好缓存在 native 侧，随取随用。这条路能成立的前提是：
 *
 * **manager 属于 profile，且共享给同一 environment 下所有 webview。**
 *
 * 本类就钉这一条，以及它带来的两个后果：
 *
 * 1. [cookieRoundTripsThroughStorage] —— 经 [WebView2CookieManager.set] 写下的持久
 *    cookie，能被同一个缓存上下文读回来（`set` → `get` 往返）。
 * 2. [cookieWrittenViaStorageIsSeenByFreshWebView] —— 经产品路径写下的 cookie，会被一个
 *    **全新的、独立的** webview 在加载页面时看到（`document.cookie`）—— 即「别的 webview
 *    捕捉得到」。
 *
 * 两个用例写的是**会话 cookie**（不带 max-age）：只要同一个 environment 的浏览器进程还
 * 活着就读得到，覆盖本类要钉的行为足够；要验跨 webview 生命周期的持久性得用持久
 * cookie（会话 cookie 只活在浏览器进程内存里，坑见
 * [soko.ekibun.acg.engine.WebViewHostTest.cookiesAreSharedAcrossTasks] 的 KDoc）。
 *
 * **不自己调 [Webview2Context.prepareEnvironment]**：伴生对象的 `init` 已经用产品那套
 * （浏览器参数 + user data folder）备好了环境；再调一次 `prepareEnvironment(null, null)` 会把
 * **浏览器参数清空**、并把 user data folder 换成 WebView2 的默认值 —— 那就不是在测产品配置了。
 *
 * 宿主不可用（WebView2 运行时坏）时整体跳过：先跑一次 [webviewHostHealth] 体检（直构
 * [Webview2Context]、真导航一个 `about:blank`），跟 `:shared` 那边的 `WebViewHostTest`
 * 一个口径 —— 静默跳过会让人误以为「验过了」。
 */
class WebViewCookieManagerTest {
  private lateinit var server: HttpServer
  private lateinit var serverPool: ExecutorService
  private var port = 0

  private val pageHtml =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>cookie-mgr-test</title></head>
    <body><p>hello</p></body></html>
    """.trimIndent()

  @BeforeTest
  fun startServer() {
    serverPool = Executors.newFixedThreadPool(2)
    server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = serverPool
        createContext("/page.html") {
          it.respond("text/html; charset=utf-8", pageHtml.toByteArray())
        }
        start()
      }
    port = server.address.port
  }

  @AfterTest
  fun stopServer() {
    server.stop(0)
    serverPool.shutdownNow()
  }

  private fun url(path: String): String = "http://127.0.0.1:$port$path"

  private fun webViewReady(): Boolean {
    val reason = hostHealth ?: return true
    println("[WebViewCookieManagerTest] skip: $reason")
    return false
  }

  /** 轮询到条件成立或超时；AddOrUpdateCookie 是异步提交的，读得等一下。 */
  private suspend fun pollUntil(cond: suspend () -> Boolean): Boolean {
    repeat(5) {
      if (cond()) return true
      delay(200)
    }
    return cond()
  }

  /**
   * 经产品路径 [WebView2CookieManager.set] 写下的持久 cookie，能被读回来。
   *
   * 这是整条链路的地基：storage 内部那个**缓存上下文**（连同它自己的 cookie manager）确实
   * 能写进共享 cookie 库、也能从中读回。`get` 走的 native `getCookie` 与 `set` 走的
   * `setCookie` 用的是同一个 manager。
   */
  @Test
  fun cookieRoundTripsThroughStorage() =
    runBlocking {
      if (!webViewReady()) return@runBlocking
      val pageUrl = url("/page.html")
      val name = "acg_roundtrip"
      val value = "via_cached_ctx"

      val storage = WebView2CookieManager

      // 走产品路径写 cookie（非 secure / 非 httpOnly，http 源才发得出来）。
      storage.set(pageUrl, "$name=$value; Domain=127.0.0.1; Path=/")

      val seen =
        withTimeout(READ_BACK_TIMEOUT_MS) {
          pollUntil {
            storage.get(pageUrl).contains("$name=$value")
          }
        }
      assertTrue(
        seen,
        "set 写的 cookie 应能被同一个 storage 读回来（add → get 往返）",
      )
    }

  /**
   * 经产品路径 [WebView2CookieManager.set] 写下的 cookie，被一个**全新的、独立的**
   * webview 在加载页面时看到 —— 即「别的 webview 捕捉得到」。
   *
   * 先确认它落进了共享存储（AddOrUpdateCookie 异步提交），再用另一个 [Webview2Context] 真的
   * 导航到该 url，页面加载完后读 `document.cookie`（非 httpOnly 的 cookie 会出现在里面）。
   */
  @Test
  fun cookieWrittenViaStorageIsSeenByFreshWebView() =
    runBlocking {
      if (!webViewReady()) return@runBlocking
      val pageUrl = url("/page.html")
      val name = "acg_xctx"
      val value = "captured"

      val storage = WebView2CookieManager

      // 走产品路径写 cookie（非 secure / 非 httpOnly，http 源才发得出来、document.cookie 才看得见）。
      storage.set(pageUrl, "$name=$value; Domain=127.0.0.1; Path=/")

      // 先确认它落进了共享存储（AddOrUpdateCookie 异步提交）。
      val stored =
        withTimeout(READ_BACK_TIMEOUT_MS) {
          pollUntil {
            storage.get(pageUrl).contains("$name=$value")
          }
        }
      assertTrue(stored, "set 写的 cookie 应先能在共享存储里读到")

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
          "另一个 webview 加载该 url 后，document.cookie 应包含 set 写的 cookie；实际=[$docCookies]",
        )
      } finally {
        ctx.close()
      }
    }

  private fun com.sun.net.httpserver.HttpExchange.respond(
    contentType: String,
    body: ByteArray,
  ) {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(200, body.size.toLong())
    responseBody.use { it.write(body) }
  }

  private companion object {
    const val READ_BACK_TIMEOUT_MS = 12_000L
    const val NAV_TIMEOUT_MS = 20_000L

    /** 环境体检结果，`null` 表示健康。两个用例共用，别重复跑。 */
    val hostHealth: String? by lazy { webviewHostHealth() }
  }
}
