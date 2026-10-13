package soko.ekibun.web

expect fun getWebviewCookieManager(): WebviewCookieManager

interface WebviewCookieManager {
  suspend fun get(url: String): String

  suspend fun set(
    url: String,
    cookie: String,
  )

  suspend fun clearAll()
}
