package soko.ekibun.common

import android.webkit.CookieManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.toHttpDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(OkHttp) {
    this.block()
    // 桥到 Android 系统 CookieManager —— 那正是 WebView 用的那一份，于是 HTTP 请求
    // 与 WebView 页面共享同一套登录态。OkHttp 本身不存 cookie，全交给 ktor 的存储。
    install(HttpCookies) {
      storage = AndroidWebViewCookieStorage
    }
  }

/**
 * ktor 的 cookie 仓库桥接到 [CookieManager]（WebView 共用的那套）。
 *
 * `CookieManager.getCookie` 返回 `"n1=v1; n2=v2"`，`setCookie(url, 完整Set-Cookie串)`
 * 会按属性（Domain / Path / Expires / Secure / HttpOnly …）落库 —— 与 WebView2 侧同口径。
 */
private object AndroidWebViewCookieStorage : CookiesStorage {
  private val manager = CookieManager.getInstance()

  override suspend fun get(requestUrl: Url): List<Cookie> =
    withContext(Dispatchers.IO) {
      val raw = manager.getCookie(requestUrl.toString()) ?: return@withContext emptyList()
      if (raw.isEmpty()) return@withContext emptyList()
      raw.split(";").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) {
          null
        } else {
          Cookie(part.substring(0, eq).trim(), part.substring(eq + 1).trim())
        }
      }
    }

  override suspend fun addCookie(
    requestUrl: Url,
    cookie: Cookie,
  ) = withContext(Dispatchers.IO) {
    manager.setCookie(requestUrl.toString(), renderForManager(cookie))
  }

  override fun close() {}

  private fun renderForManager(cookie: Cookie): String =
    buildString {
      append(cookie.name).append('=').append(cookie.value)
      cookie.domain?.let { append("; Domain=").append(it) }
      cookie.path?.let { append("; Path=").append(it) }
      val expires = cookie.expires
      when {
        cookie.maxAge != null -> append("; Max-Age=").append(cookie.maxAge)
        expires != null -> append("; Expires=").append(expires.toHttpDate())
      }
      if (cookie.secure) append("; Secure")
      if (cookie.httpOnly) append("; HttpOnly")
      cookie.extensions["SameSite"]?.let { append("; SameSite=").append(it) }
    }
}
