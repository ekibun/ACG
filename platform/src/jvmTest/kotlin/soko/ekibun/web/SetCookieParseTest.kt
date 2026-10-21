package soko.ekibun.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [parseSetCookie] 的字段口径：默认值、属性大小写、日期三种格式、SameSite/Secure 联动、无名拒绝。 */
class SetCookieParseTest {
  private val url = "https://example.com:8443/path/page.html"

  private fun parse(
    rawCookie: String,
    nowEpochSeconds: Long = 1_000_000,
  ) = assertNotNull(parseSetCookie(url, rawCookie, nowEpochSeconds), "this string should parse: $rawCookie")

  @Test
  fun nameValueAndHostDefaults() {
    val cookie = parse("sid=abc")
    assertEquals("sid", cookie.name)
    assertEquals("abc", cookie.value)
    assertEquals("example.com", cookie.domain, "the domain defaults to the URL host, without the port")
    assertEquals("/", cookie.path, "the path defaults to root")
    assertEquals(0.0, cookie.expiresEpochSeconds, "no expiry means a session cookie, recorded as 0")
    assertEquals(1, cookie.sameSite, "defaults to LAX (Chromium's default)")
    assertFalse(cookie.secure)
    assertFalse(cookie.httpOnly)
  }

  @Test
  fun domainPathAndFlagAttributesAreCaseInsensitive() {
    val cookie = parse("sid=abc; DOMAIN=.Example.COM; pAtH=/x; sEcUrE; hTtPoNlY")
    assertEquals(
      "Example.COM",
      cookie.domain,
      "a leading dot must be removed -- WebView2 rejects the .example.com form",
    )
    assertEquals("/x", cookie.path)
    assertTrue(cookie.secure)
    assertTrue(cookie.httpOnly)
  }

  @Test
  fun maxAgeWinsOverExpires() {
    val cookie = parse("sid=abc; Max-Age=60; Expires=Wed, 21 Oct 2015 07:28:00 GMT")
    assertEquals(
      1_000_060.0,
      cookie.expiresEpochSeconds,
      "Max-Age is relative seconds, resolved against now (RFC 6265 5.3)",
    )
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

  @Test
  fun sameSiteNoneDragsSecureAlong() {
    assertEquals(2, parse("sid=a; SameSite=Strict").sameSite)
    assertEquals(0, parse("sid=a; SameSite=None").sameSite)
    val none = parse("sid=a; SameSite=None")
    assertTrue(none.secure, "SameSite=None must also set Secure -- otherwise WebView2 rejects the cookie outright")
    val strict = parse("sid=a; SameSite=Strict")
    assertFalse(strict.secure, "an explicit Strict without Secure must not have Secure added for it")
  }

  @Test
  fun namelessCookieIsRejected() {
    assertNull(parseSetCookie(url, ""), "an empty string has no name")
    assertNull(parseSetCookie(url, "=abc; Path=/"), "there is a value but no name")
    assertNull(
      parseSetCookie("http:///no-host", "sid=abc"),
      "with no host in the URL and no Domain given there is nowhere to store it, so it must be dropped",
    )
  }
}
