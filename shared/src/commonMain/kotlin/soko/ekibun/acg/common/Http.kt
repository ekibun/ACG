package soko.ekibun.acg.common

import soko.ekibun.common.Http

/**
 * 全应用的 User-Agent：`webview` 页面与出站请求共用这一份 —— 站点按 UA 认会话，两处换一个串就成游客页。
 *
 * 取值顺序与覆盖口见 `docs/development/shared-layer.md` §5.9。
 */
const val DEFAULT_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * [Http.request] 的默认 UA 版：调用方显式给了 `User-Agent`（头名不区分大小写）就用它的，
 * 否则补上 [DEFAULT_USER_AGENT]；脚本 `fetch`、播放器、图片因此是同一个串。
 */
suspend fun Http.requestWithDefaultUserAgent(options: Map<Any, Any?>): Http.Response {
  val headers = options["headers"] as? Map<*, *> ?: emptyMap<Any, Any?>()
  if (headers.keys.any { it.toString().equals("User-Agent", ignoreCase = true) }) return Http.request(options)
  val merged = headers.entries.associate { it.key.toString() to it.value } + ("User-Agent" to DEFAULT_USER_AGENT)
  return Http.request(options + ("headers" to merged))
}
