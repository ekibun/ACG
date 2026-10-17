package soko.ekibun.web

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun getWebviewCookieManager(): WebviewCookieManager = AndroidWebViewCookieManager

/**
 * 插件 JS 的 `Cookies.get` / `Cookies.set` 落到 [CookieManager]（与 WebView 共用同一套 cookie 库）。
 *
 * `CookieManager.getCookie` 返回 `"n1=v1; n2=v2"`，`setCookie(url, 完整Set-Cookie串)`
 * 会按属性（Domain / Path / Expires / Secure / HttpOnly …）落库 —— 与 WebView2 侧同口径。
 * HTTP 层没有 cookie 仓库，登录态由 JS 侧读出 cookie、显式塞进请求头。
 */
private object AndroidWebViewCookieManager : WebviewCookieManager {
  private val manager = CookieManager.getInstance()

  override suspend fun get(url: String): String =
    withContext(Dispatchers.IO) {
      manager.getCookie(url) ?: ""
    }

  override suspend fun set(
    url: String,
    cookie: String,
  ) = withContext(Dispatchers.IO) {
    manager.setCookie(url, cookie)
  }

  override suspend fun clearAll() {
    manager.removeAllCookies { }
  }
}
