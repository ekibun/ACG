package soko.ekibun.web

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url

/**
 * ktor 的 cookie 仓库桥接到 WebView2 的 cookie 库：HTTP 请求（ktor）与 WebView
 * 页面共享同一套登录态。
 *
 * 取 / 写都走 [Webview2Context.getCookie] / [Webview2Context.setCookie] —— 底层是本对象
 * **惰性缓存的一个 [Webview2Context]**（第一次用时才建，之后所有读写都复用它）连同它自己
 * 的 `ICoreWebView2CookieManager`（controller 就绪时在 native 侧一次取好缓存；manager
 * 属于 profile，跨上下文共享同一套 cookie 库）。于是 cookie 读写投到 WebView 宿主线程上
 * 跑，却不必每次建 / 销一整个 WebView2 上下文。所有上下文共用同一个 user data folder
 * 下的 cookie 库，无论谁落下的 cookie，可见页 / 后台页 / HTTP 层都能读到，登录态天然共享。
 *
 * 之所以必须桥一个自定义 [CookiesStorage] 而不是手动塞 `Cookie` 请求头：ktor 的
 * `HttpCookies.sendCookiesWith` 会**覆盖** `Cookie` 头（`headers[Cookie] =
 * cookieHeader`），手动种进去的头会被无声抹掉。所以登录态只能从存储这一侧喂给 ktor。
 */
internal object WebViewCookieStorage : CookiesStorage {
  private val lazyCtx = lazy { Webview2Context() }
  private val ctx by lazyCtx

  override suspend fun get(requestUrl: Url): List<Cookie> {
    val raw =
      runCatching { ctx.getCookie(requestUrl.toString()) }.getOrNull()
        ?: return emptyList()
    if (raw.isEmpty()) return emptyList()
    return raw.split("; ").mapNotNull { pair ->
      val eq = pair.indexOf('=')
      if (eq <= 0) null else Cookie(pair.substring(0, eq), pair.substring(eq + 1))
    }
  }

  override suspend fun addCookie(
    requestUrl: Url,
    cookie: Cookie,
  ) {
    // 域 / 路径缺省按 url 推（去掉端口的 host / 根路径），与 Android CookieManager
    // 口径一致。ktor 的 domain 可能带前导点，WebView2 的 CreateCookie 不认，去掉。
    val rawDomain = cookie.domain ?: requestUrl.host
    val domain = if (rawDomain.startsWith(".")) rawDomain.substring(1) else rawDomain
    val path = cookie.path ?: "/"
    // 过期时间换算成「自 Unix 纪元起的秒」。Max-Age 优先于 Expires。
    val maxAge = cookie.maxAge
    val expires = cookie.expires
    val expiresEpochSeconds =
      when {
        maxAge != null -> ((System.currentTimeMillis() / 1000) + maxAge).toDouble()
        expires != null -> expires.timestamp / 1000.0
        else -> 0.0
      }
    // SameSite 口径：默认 LAX（Chromium 的默认值）。显式 none 时必须同时置 Secure ——
    // WebView2 对「SameSite=None 但非 Secure」的 cookie 直接拒收，且 AddOrUpdateCookie
    // 没有返回值、不报错，症状是「调用全正常但 GetCookies 永远读不到」。
    val sameSite =
      when (val v = cookie.extensions["SameSite"]?.lowercase()) {
        "strict" -> 2
        "none" -> 0
        else -> 1
      }
    val secure = cookie.secure || sameSite == 0
    runCatching {
      ctx.setCookie(
        cookie.name,
        cookie.value,
        domain,
        path,
        expiresEpochSeconds,
        secure,
        cookie.httpOnly,
        sameSite,
      )
    }
  }

  override fun close() {
    if (lazyCtx.isInitialized()) ctx.close()
  }
}
