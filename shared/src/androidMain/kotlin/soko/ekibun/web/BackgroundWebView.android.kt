package soko.ekibun.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

// Android 端的后台 WebView：Context 宿主 + 拦截请求的 cookie 折算。
//
// Android 上不需要「宿主窗口」：`android.webkit.WebView` 本来就能脱离视图树独立工作，
// 所以控制器是**命令式**建的那一个，而不是组合出来的。界面上不必画任何东西。
// client 与 settings 的配置都在 `AndroidWebViewController.configureHeadless`
// （WebView.android.kt），任务骨架在 commonMain 的 [loadBackgroundWebView]。
//
// `shouldInterceptRequest` 看得见**全部**请求（含图片/分片等子资源），桌面端的自研
// WebView2 宿主现在也是全量拦截 —— 两端契约一致，靠拦 `Range` 头拿真实媒体地址的
// 脚本在两边都原样可用。

/** 建 WebView 用的 Context，由 [BackgroundWebViewHost] 在组合里顺手记下来。 */
@Volatile
internal var applicationContext: Context? = null

@Composable
actual fun BackgroundWebViewHost() {
  val context = LocalContext.current.applicationContext
  // 只留 applicationContext：整个进程活着它就在，不存在泄漏 Activity 的问题。
  // 可见 WebView 的 [createWebViewController] 同样从这里拿 Context。
  LaunchedEffect(context) { applicationContext = context }
}

/**
 * 把 cookie 折进请求头 —— 对齐 `http.js` 里 `makeRequest` 干的事。
 *
 * 后台页面的请求头本身不带 cookie，但脚本拿到 url 后要自己 `fetch` 一次，
 * 那次请求必须带上同一份会话，否则登录态是断的。
 */
internal fun headersWithCookie(request: WebResourceRequest): Map<String, String> {
  val headers = request.requestHeaders?.toMap() ?: emptyMap()
  return runCatching {
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()
    val cookie = cookieManager.getCookie(request.url.host ?: "") ?: ""
    if (cookie.isEmpty()) headers else headers + ("cookie" to cookie)
  }.getOrDefault(headers)
}
