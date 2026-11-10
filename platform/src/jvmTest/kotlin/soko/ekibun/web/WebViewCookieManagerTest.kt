package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.common.TestEnv
import soko.ekibun.common.TestServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `WebViewCookieManager.jvm.kt` 两半：`parseSetCookie` 的字段口径，与写下的 cookie 能被全新 webview 看到。 */
class WebViewCookieManagerTest {
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

  private fun webViewReady() = TestEnv.skipUnlessHostAvailable()

  /** 经 `set` 写入的 cookie 落进共享存储（`set` → `get` 往返），并被另一个新建 webview 读到。 */
  @Test(timeout = 120000)
  fun cookieWrittenViaStorageIsSeenByFreshWebView() =
    runBlocking {
      webViewReady()
      val pageUrl = url("/page.html")
      val name = "acg_xctx"
      val value = "captured"

      val storage = WebView2CookieManager

      // 走产品路径写 cookie（非 secure / 非 httpOnly）
      storage.set(pageUrl, "$name=$value; Domain=127.0.0.1; Path=/")

      // 先确认它落进了共享存储。`set` 内部把异常吞成 no-op，落没落只能靠回读，
      // 所以这里用真的截止时间轮询，不是「试几次就下结论」。
      val deadline = System.nanoTime() + READ_BACK_TIMEOUT_MS * 1_000_000
      var stored = storage.get(pageUrl).contains("$name=$value")
      while (!stored && System.nanoTime() < deadline) {
        delay(POLL_INTERVAL_MS)
        stored = storage.get(pageUrl).contains("$name=$value")
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

  // ---- parseSetCookie：字段口径 ----

  private val parseUrl = "https://example.com:8443/path/page.html"

  private fun parse(
    rawCookie: String,
    nowEpochSeconds: Long = 1_000_000,
  ) = assertNotNull(parseSetCookie(parseUrl, rawCookie, nowEpochSeconds), "this string should parse: $rawCookie")

  /** 缺省值与属性名大小写：`Domain` 保留前导点、`Path` 回落根、无过期即会话 cookie。 */
  @Test(timeout = 120000)
  fun defaultsAndAttributeCase() {
    val plain = parse("sid=abc")
    assertEquals("sid", plain.name)
    assertEquals("abc", plain.value)
    assertEquals("example.com", plain.domain, "the domain defaults to the URL host, without the port")
    assertEquals("/", plain.path, "the path defaults to root")
    assertEquals(0.0, plain.expiresEpochSeconds, "no expiry means a session cookie, recorded as 0")
    assertEquals(1, plain.sameSite, "defaults to LAX (Chromium's default)")
    assertFalse(plain.secure)
    assertFalse(plain.httpOnly)

    val cookie = parse("sid=abc; DOMAIN=.Example.COM; pAtH=/x; sEcUrE; hTtPoNlY")
    assertEquals(
      ".Example.COM",
      cookie.domain,
      "a domain cookie keeps its leading dot -- stripping it makes a host-only cookie",
    )
    assertEquals("/x", cookie.path)
    assertTrue(cookie.secure)
    assertTrue(cookie.httpOnly)
  }

  /** 过期时间：`Max-Age` 压过 `Expires`，四种日期写法，坏日期回落会话 cookie。 */
  @Test(timeout = 120000)
  fun expiries() {
    val maxAge = parse("sid=abc; Max-Age=60; Expires=Wed, 21 Oct 2015 07:28:00 GMT")
    assertEquals(
      1_000_060.0,
      maxAge.expiresEpochSeconds,
      "Max-Age is relative seconds, resolved against now (RFC 6265 5.3)",
    )

    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Sun, 06 Nov 1994 08:49:37 GMT").expiresEpochSeconds,
      "RFC 1123",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Sunday, 06-Nov-94 08:49:37 GMT").expiresEpochSeconds,
      "RFC 850 (two-digit year 94 means 1994, not 2094)",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Sun Nov  6 08:49:37 1994").expiresEpochSeconds,
      "asctime (day is space-padded, not zero-padded)",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=06 Nov 1994 08:49:37 GMT").expiresEpochSeconds,
      "no weekday is also accepted",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Monday, 06 Nov 1994 08:49:37 GMT").expiresEpochSeconds,
      "a wrong weekday must not turn the whole cookie into a session cookie",
    )
    assertEquals(
      0.0,
      parse("sid=a; Expires=not a date").expiresEpochSeconds,
      "an unparsable date falls back to a session cookie -- no throw, and no garbage value used as an expiry",
    )
  }

  /** `SameSite=None` 必须带上 `Secure`，而显式 `Strict` 不许被额外加上 `Secure`。 */
  @Test(timeout = 120000)
  fun sameSiteDrivesSecure() {
    assertEquals(2, parse("sid=a; SameSite=Strict").sameSite)
    assertEquals(0, parse("sid=a; SameSite=None").sameSite)
    val none = parse("sid=a; SameSite=None")
    assertTrue(none.secure, "SameSite=None must also set Secure -- otherwise WebView2 rejects the cookie outright")
    val strict = parse("sid=a; SameSite=Strict")
    assertFalse(strict.secure, "an explicit Strict without Secure must not have Secure added for it")
  }

  /** 拒绝面：空串、有值无名、URL 无主机且无 `Domain`。 */
  @Test(timeout = 120000)
  fun rejections() {
    assertNull(parseSetCookie(parseUrl, ""), "an empty string has no name")
    assertNull(parseSetCookie(parseUrl, "=abc; Path=/"), "there is a value but no name")
    assertNull(
      parseSetCookie("http:///no-host", "sid=abc"),
      "with no host in the URL and no Domain given there is nowhere to store it, so it must be dropped",
    )
  }

  private companion object {
    const val READ_BACK_TIMEOUT_MS = 12_000L
    const val POLL_INTERVAL_MS = 20L
    const val NAV_TIMEOUT_MS = 20_000L
  }
}
