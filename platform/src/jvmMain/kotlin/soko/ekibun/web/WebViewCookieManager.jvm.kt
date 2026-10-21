package soko.ekibun.web

import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

actual fun getWebviewCookieManager(): WebviewCookieManager = WebView2CookieManager

/**
 * WebView2 的 cookie 门面：读写走一个惰性创建的 [Webview2Context]，
 * 命令投到 WebView 宿主线程执行。
 */
internal object WebView2CookieManager : WebviewCookieManager {
  private val lazyCtx = lazy { Webview2Context() }
  private val ctx by lazyCtx

  override suspend fun get(url: String): String = runCatching { ctx.getCookie(url) }.getOrNull() ?: ""

  override suspend fun set(
    url: String,
    cookie: String,
  ) {
    val parsed = parseSetCookie(url, cookie) ?: return
    runCatching {
      ctx.setCookie(
        parsed.name,
        parsed.value,
        parsed.domain,
        parsed.path,
        parsed.expiresEpochSeconds,
        parsed.secure,
        parsed.httpOnly,
        parsed.sameSite,
      )
    }
  }

  override suspend fun clearAll() = ctx.clearAllCookies()
}

/** 解析后的 `Set-Cookie` 字段。 */
internal data class ParsedCookie(
  val name: String,
  val value: String,
  val domain: String,
  val path: String,
  /** 自 Unix 纪元起的秒；0 = 会话 cookie。 */
  val expiresEpochSeconds: Double,
  val secure: Boolean,
  val httpOnly: Boolean,
  /** 0 = NONE、1 = LAX、2 = STRICT。 */
  val sameSite: Int,
)

/**
 * 按 RFC 6265 把 `Set-Cookie` 串拆成结构化字段；首段没有 `name=` 时返回 null。
 * [nowEpochSeconds] 供 `Max-Age` 换算，可注入。
 */
internal fun parseSetCookie(
  url: String,
  rawCookie: String,
  nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
): ParsedCookie? {
  val parts = rawCookie.split(';')
  val first = parts.first().trim()
  val name = first.substringBefore('=', "").trim()
  if (name.isEmpty()) return null
  val attributes =
    parts
      .drop(1)
      .associate {
        it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=', "").trim()
      }
  // 域 / 路径缺省按 url 推；域去掉前导点
  val domain = attributes["domain"]?.removePrefix(".") ?: URI(url).host ?: return null
  // 默认 LAX；NONE 需要同时置 Secure
  val sameSite =
    when (attributes["samesite"]?.lowercase(Locale.ROOT)) {
      "strict" -> 2
      "none" -> 0
      else -> 1
    }
  return ParsedCookie(
    name = name,
    value = first.substringAfter('=', "").trim(),
    domain = domain,
    path = attributes["path"]?.takeIf { it.isNotEmpty() } ?: "/",
    // Max-Age 优先于 Expires；两者都没有即会话 cookie
    expiresEpochSeconds =
      attributes["max-age"]?.toLongOrNull()?.let { (nowEpochSeconds + it).toDouble() }
        ?: attributes["expires"]?.let(::httpDateEpochSeconds)
        ?: 0.0,
    secure = "secure" in attributes || sameSite == 0,
    httpOnly = "httponly" in attributes,
    sameSite = sameSite,
  )
}

/** `Expires` 接受的三种日期格式：RFC 1123 / RFC 850 / asctime，均不含星期与时区。 */
private val HTTP_DATE_FORMATS: List<DateTimeFormatter> =
  listOf(
    DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss", Locale.US),
    DateTimeFormatterBuilder()
      .appendPattern("dd-MMM-")
      // 两位年份归窗：70-99 → 19xx，00-69 → 20xx
      .appendValueReduced(ChronoField.YEAR, 2, 4, 1970)
      .appendPattern(" HH:mm:ss")
      .toFormatter(Locale.US),
    // asctime 的日期空格补齐（"Nov  6"），用 `ppd`
    DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss uuuu", Locale.US),
  )

/** 星期名前缀（"Sun, " / "Sunday, " / "Sun "）。 */
private val WEEKDAY_PREFIX = Regex("^[A-Za-z]{3,9},?\\s+")

/** 时区后缀（GMT / UTC / UT）。 */
private val TIMEZONE_SUFFIX = Regex("\\s+(?i)(gmt|utc|ut)$")

/** `Expires` 转 Unix 秒；认不出返回 null。 */
private fun httpDateEpochSeconds(value: String): Double? {
  val text = value.trim().replace(TIMEZONE_SUFFIX, "")
  // 两种起手都试：先剥星期，或按原样解析
  return parseHttpDate(text) ?: parseHttpDate(text.replace(WEEKDAY_PREFIX, ""))
}

private fun parseHttpDate(text: String): Double? =
  HTTP_DATE_FORMATS
    .firstNotNullOfOrNull { format ->
      runCatching { LocalDateTime.parse(text, format).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    }?.toDouble()
