package soko.ekibun.web

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun getWebviewCookieManager(): WebviewCookieManager = AndroidWebViewCookieManager

/**
 * ktor 的 cookie 仓库桥接到 [CookieManager]（WebView 共用的那套）。
 *
 * `CookieManager.getCookie` 返回 `"n1=v1; n2=v2"`，`setCookie(url, 完整Set-Cookie串)`
 * 会按属性（Domain / Path / Expires / Secure / HttpOnly …）落库 —— 与 WebView2 侧同口径。
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
