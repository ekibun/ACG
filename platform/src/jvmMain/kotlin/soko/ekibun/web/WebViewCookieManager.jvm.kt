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
 * WebView2 的 cookie 库门面：插件 JS 的 `Cookies.get` / `Cookies.set` 落到这里。
 *
 * 取 / 写都走 [Webview2Context.getCookie] / [Webview2Context.setCookie] —— 底层是本对象
 * **惰性缓存的一个 [Webview2Context]**（第一次用时才建，之后所有读写都复用它）连同它自己
 * 的 `ICoreWebView2CookieManager`（controller 就绪时在 native 侧一次取好缓存；manager
 * 属于 profile，跨上下文共享同一套 cookie 库）。于是 cookie 读写投到 WebView 宿主线程上
 * 跑，却不必每次建 / 销一整个 WebView2 上下文。所有上下文共用同一个 user data folder
 * 下的 cookie 库，无论谁落下的 cookie，可见页 / 后台页都能读到。
 *
 * HTTP 层**没有** cookie 仓库（传输层不装 cookieJar）：要带登录态就由 JS 侧读出 cookie、
 * 显式塞进请求头 —— 于是「哪些请求带 cookie」完全由 JS 说了算，本层不增不减。
 */
internal object WebView2CookieManager : WebviewCookieManager {
  private val lazyCtx = lazy { Webview2Context() }
  private val ctx by lazyCtx

  override suspend fun get(url: String): String = runCatching { ctx.getCookie(url) }.getOrNull() ?: ""

  override suspend fun set(
    url: String,
    rawCookie: String,
  ) {
    val cookie = parseSetCookie(url, rawCookie) ?: return
    runCatching {
      ctx.setCookie(
        cookie.name,
        cookie.value,
        cookie.domain,
        cookie.path,
        cookie.expiresEpochSeconds,
        cookie.secure,
        cookie.httpOnly,
        cookie.sameSite,
      )
    }
  }

  override suspend fun clearAll() = ctx.clearAllCookies()
}

/** 解析出来的 `Set-Cookie`：字段口径与 [Webview2Context.setCookie] 一一对应。 */
internal data class ParsedCookie(
  val name: String,
  val value: String,
  val domain: String,
  val path: String,
  /** 「自 Unix 纪元起的秒」；0 = 会话 cookie（缺省口径）。 */
  val expiresEpochSeconds: Double,
  val secure: Boolean,
  val httpOnly: Boolean,
  /** 0 = NONE、1 = LAX、2 = STRICT。 */
  val sameSite: Int,
)

/**
 * 按 RFC 6265 的字段把 `Set-Cookie` 串拆开。**手写而不是借传输库**：解析结果要转成 WebView2 的
 * 结构化字段（域要去掉前导点、SameSite 要变成枚举），库给的 cookie 对象反而更远。
 *
 * 认不出名字（第一段没有 `name=`）时返回 null。**不校验域与 URL 是否匹配** —— 装不进这个域的
 * cookie 由 WebView2 自己拒收。
 *
 * [nowEpochSeconds] 只为 `Max-Age` 换算用（它给的是相对秒数），测试里可注入。
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
  // 域 / 路径缺省按 url 推（去掉端口的 host / 根路径），与 Android CookieManager 口径一致。
  // 前导点要去掉：WebView2 的 CreateCookie 不认那种写法。
  val domain = attributes["domain"]?.removePrefix(".") ?: URI(url).host ?: return null
  // SameSite 口径：默认 LAX（Chromium 的默认值）。显式 none 时必须同时置 Secure ——
  // WebView2 对「SameSite=None 但非 Secure」的 cookie 直接拒收，且 AddOrUpdateCookie
  // 没有返回值、不报错，症状是「调用全正常但 GetCookies 永远读不到」。
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
    // Max-Age 优先于 Expires（RFC 6265 §5.3）；两个都没有就是会话 cookie。
    expiresEpochSeconds =
      attributes["max-age"]?.toLongOrNull()?.let { (nowEpochSeconds + it).toDouble() }
        ?: attributes["expires"]?.let(::httpDateEpochSeconds)
        ?: 0.0,
    secure = "secure" in attributes || sameSite == 0,
    httpOnly = "httponly" in attributes,
    sameSite = sameSite,
  )
}

/**
 * `Expires` 认的三种格式（RFC 6265 §5.1.1 三种都要求认）：RFC 1123 / RFC 850 / asctime。
 *
 * 都**不带星期几与时区**：这两截在 [httpDateEpochSeconds] 里先剥掉 —— 星期几写错了（有源会写）
 * 不该让整条 cookie 变成会话 cookie；三种格式的时区一律是 GMT（RFC 7231），于是按 UTC 解即可。
 */
private val HTTP_DATE_FORMATS: List<DateTimeFormatter> =
  listOf(
    DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss", Locale.US),
    DateTimeFormatterBuilder()
      .appendPattern("dd-MMM-")
      // 两位年份按 RFC 6265 §5.1.1 归窗：70-99 归 19xx、00-69 归 20xx。
      .appendValueReduced(ChronoField.YEAR, 2, 4, 1970)
      .appendPattern(" HH:mm:ss")
      .toFormatter(Locale.US),
    // asctime 的日期是空格补齐的（"Nov  6"），所以是 `ppd` 而不是 `dd`。
    DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss uuuu", Locale.US),
  )

/** 三个字母以上、可带逗号的星期名字前缀（"Sun, " / "Sunday, " / asctime 的 "Sun "）。 */
private val WEEKDAY_PREFIX = Regex("^[A-Za-z]{3,9},?\\s+")

/** 时区后缀（三种格式都只出现在 GMT 语境里）。 */
private val TIMEZONE_SUFFIX = Regex("\\s+(?i)(gmt|utc|ut)$")

/**
 * `Expires` 转「自 Unix 纪元起的秒」。认不出的日期当**没有**过期时间（会话 cookie），不抛 ——
 * 一条解析不了的 `Expires` 不该让整个 `Cookies.set` 失败。
 */
private fun httpDateEpochSeconds(value: String): Double? {
  val text = value.trim().replace(TIMEZONE_SUFFIX, "")
  // 两种起手都试：先按原样（有的源会带星期几），失败再去掉开头的星期名字。
  // 不能无条件先剥：asctime 之外的格式一旦少了星期几，剥掉的就是月份。
  return parseHttpDate(text) ?: parseHttpDate(text.replace(WEEKDAY_PREFIX, ""))
}

private fun parseHttpDate(text: String): Double? =
  HTTP_DATE_FORMATS
    .firstNotNullOfOrNull { format ->
      runCatching { LocalDateTime.parse(text, format).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    }?.toDouble()
