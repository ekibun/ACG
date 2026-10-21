package soko.ekibun.web

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun getWebviewCookieManager(): WebviewCookieManager = AndroidWebViewCookieManager

/**
 * 基于 [CookieManager] 的 cookie 实现，与 WebView 共用同一套 cookie 库。
 * `get` 返回 `"n1=v1; n2=v2"`，`set` 接完整的 `Set-Cookie` 串。
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
