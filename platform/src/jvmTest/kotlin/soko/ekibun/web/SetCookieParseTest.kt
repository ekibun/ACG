package soko.ekibun.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [parseSetCookie] 的字段口径 —— 手写解析里最容易错的那几处。
 *
 * 为什么单独测：这些字段要喂给 WebView2 的 `AddOrUpdateCookie`，它**没有返回值**、属性不对也不报错，
 * 症状是「调用全正常、cookie 永远读不到」。端到端用例（[WebViewCookieManagerTest]）在这条路上只能
 * 看到「读不回」，定位不到是哪一项拼错了，而且要 WebView2 宿主可用才跑得起来。
 */
class SetCookieParseTest {
  private val url = "https://example.com:8443/path/page.html"

  private fun parse(
    rawCookie: String,
    nowEpochSeconds: Long = 1_000_000,
  ) = assertNotNull(parseSetCookie(url, rawCookie, nowEpochSeconds), "这个串应当解析得出来：$rawCookie")

  @Test
  fun nameValueAndHostDefaults() {
    val cookie = parse("sid=abc")
    assertEquals("sid", cookie.name)
    assertEquals("abc", cookie.value)
    assertEquals("example.com", cookie.domain, "域缺省取 URL 的 host，不带端口")
    assertEquals("/", cookie.path, "路径缺省是根")
    assertEquals(0.0, cookie.expiresEpochSeconds, "没有过期时间就是会话 cookie，记 0")
    assertEquals(1, cookie.sameSite, "缺省 LAX（Chromium 的默认值）")
    assertFalse(cookie.secure)
    assertFalse(cookie.httpOnly)
  }

  @Test
  fun domainPathAndFlagAttributesAreCaseInsensitive() {
    val cookie = parse("sid=abc; DOMAIN=.Example.COM; pAtH=/x; sEcUrE; hTtPoNlY")
    assertEquals("Example.COM", cookie.domain, "前导点要去掉 —— WebView2 不认 .example.com 那种写法")
    assertEquals("/x", cookie.path)
    assertTrue(cookie.secure)
    assertTrue(cookie.httpOnly)
  }

  @Test
  fun maxAgeWinsOverExpires() {
    val cookie = parse("sid=abc; Max-Age=60; Expires=Wed, 21 Oct 2015 07:28:00 GMT")
    assertEquals(1_000_060.0, cookie.expiresEpochSeconds, "Max-Age 是相对秒数，按 now 换算（RFC 6265 §5.3）")
  }

  @Test
  fun expiresAcceptsAllThreeHttpDateFormats() {
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Sun, 06 Nov 1994 08:49:37 GMT").expiresEpochSeconds,
      "RFC 1123",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Sunday, 06-Nov-94 08:49:37 GMT").expiresEpochSeconds,
      "RFC 850（两位年份 94 归 1994，不是 2094）",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Sun Nov  6 08:49:37 1994").expiresEpochSeconds,
      "asctime（月份与日期之间是空格补齐，不是零补齐）",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=06 Nov 1994 08:49:37 GMT").expiresEpochSeconds,
      "不带星期几也认",
    )
    assertEquals(
      784111777.0,
      parse("sid=a; Expires=Monday, 06 Nov 1994 08:49:37 GMT").expiresEpochSeconds,
      "星期几写错了也不该让整条 cookie 变成会话 cookie",
    )
    assertEquals(
      0.0,
      parse("sid=a; Expires=not a date").expiresEpochSeconds,
      "认不出的日期按会话 cookie —— 不抛，也不许拿乱数当过期的依据",
    )
  }

  @Test
  fun sameSiteNoneDragsSecureAlong() {
    assertEquals(2, parse("sid=a; SameSite=Strict").sameSite)
    assertEquals(0, parse("sid=a; SameSite=None").sameSite)
    val none = parse("sid=a; SameSite=None")
    assertTrue(none.secure, "SameSite=None 必须同时置 Secure —— 否则 WebView2 直接拒收这条 cookie")
    val strict = parse("sid=a; SameSite=Strict")
    assertFalse(strict.secure, "显式 Strict 不带 Secure 时不许替它加")
  }

  @Test
  fun namelessCookieIsRejected() {
    assertNull(parseSetCookie(url, ""), "空串没有名字")
    assertNull(parseSetCookie(url, "=abc; Path=/"), "只有值没有名字")
    assertNull(
      parseSetCookie("http:///no-host", "sid=abc"),
      "URL 里没有 host、又没给 Domain 时无从落库，只能放弃",
    )
  }
}
