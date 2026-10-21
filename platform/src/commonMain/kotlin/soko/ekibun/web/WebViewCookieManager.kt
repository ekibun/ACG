package soko.ekibun.web

/** 取当前平台的 cookie 管理器。 */
expect fun getWebviewCookieManager(): WebviewCookieManager

/** cookie 读写接口。 */
interface WebviewCookieManager {
  /** 读 [url] 下的 cookie，返回 `"n1=v1; n2=v2"`，没有则空串。 */
  suspend fun get(url: String): String

  /** 写一条 `Set-Cookie` 串到 [url]。 */
  suspend fun set(
    url: String,
    cookie: String,
  )

  /** 清空全部 cookie。 */
  suspend fun clearAll()
}
