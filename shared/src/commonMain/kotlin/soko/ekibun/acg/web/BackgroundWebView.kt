package soko.ekibun.acg.web

import androidx.compose.runtime.Composable

/**
 * 后台 WebView —— 插件 JS 里 `webview(...)` 的宿主抽象。
 *
 * 对应 BangumiPlugin `app/src/main/assets/modules/http.js` 里的 `webviewload` /
 * `__webview__`：脚本要一个「能跑页面 JS、能拦请求、但不占界面」的 WebView。
 *
 * 两端都是**引擎层的全量请求钩子**，契约一致：
 *
 * | | 实现 | 请求可见范围 |
 * | --- | --- | --- |
 * | Android | 命令式建一个无头 `android.webkit.WebView` | `WebViewClient.shouldInterceptRequest`，含全部子资源 |
 * | 桌面 (Windows) | 自研 C++ 宿主（`cxx/webview/webview.cpp`）里 1×1 的隐藏窗口 | `add_WebResourceRequested` + `AddWebResourceRequestedFilter("*", ALL)`，含全部子资源 |
 *
 * 桌面端以前用的是预编译的 `dev.nucleusframework:composewebview`，它的
 * `RequestInterceptor` 只接在 `addNavigateListener`（= 主框架导航）上，子资源一律
 * 拦不到，于是「靠拦媒体分片（m3u8/ts）拿真实地址」这类写法在桌面上永远失效。
 * 现在钩子接到了引擎的 `WebResourceRequested` 上，两端能力对齐。
 *
 * cookie 也是统一的：桌面端可见页与后台页共用同一个 WebView2 environment
 * （同一份 user data folder），Android 端共用系统 `CookieManager`。在这一页登录过，
 * 脚本那边就是登录态；反之亦然。
 *
 * 有一条**两端都一样**的硬约束，见 [WebViewTask.onInterceptRequest] 的说明：
 * 拦截回调不能从 JS 调用内部同步触发。
 */

/**
 * 后台 WebView 看到的一个请求。
 *
 * - 两端 [url] / [headers] / [method] / [isForMainFrame] 都是真的
 *   （桌面端来自 `ICoreWebView2HttpRequestHeaders`，所以 `Range` 之类的头拿得到）。
 * - [isRedirect] 只有 Android 有（`WebResourceRequest.isRedirect`）；WebView2 的
 *   请求事件里没有这个信息，桌面上**恒为 false**。别用它当「有没有命中」的依据。
 */
data class WebViewRequest(
    val url: String,
    val headers: Map<String, String>,
    val method: String,
    val isForMainFrame: Boolean,
    val isRedirect: Boolean,
)

/**
 * 插件脚本对某个请求给出的拦截答复。
 *
 * 返回非 null 即「命中」：加载立刻结束，[WebViewTaskResult.Intercepted] 带着这份
 * 数据回到脚本里 —— 脚本再拿它自己去 `fetch`（这正是 `http.js` 里
 * `onInterceptRequest` + `makeRequest` 的用法）。
 */
data class WebViewInterception(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * 一次后台 WebView 任务。
 *
 * 字段与 `webview(url, header, script, onInterceptRequest)` 的位置一一对应。
 */
data class WebViewTask(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 页面加载完成后注入并取回其返回值的脚本；null 表示不注入。 */
    val script: String? = null,
    /**
     * 命中即中止加载。返回 null 表示放行。**会在 WebView 的回调线程上被调用**，
     * 实现里不要假设自己在哪条线程。
     *
     * 但有一条硬约束：**不能从 JS 调用内部同步回调它**。脚本调 `webviewAsync`
     * 时 `_java` 仍在 native `evaluate` 里没返回，此时反向去调 JS 函数是对引擎的
     * 重入调用，本桥会让整轮 Promise 停摆（实测挂死不返回，耗时也不增长）。
     * 所以拦截回调必须来自平台侧（`shouldInterceptRequest` / `WebResourceRequested`），
     * 也就是「脚本已经挂在 `await` 上、派发线程空闲」的时刻 —— 两端实现都是这么做的。
     */
    val onInterceptRequest: ((WebViewRequest) -> WebViewInterception?)? = null,
) {
    /**
     * 补齐后台加载默认要带的头，对齐 `http.js`：`header.referer = header.referer || url`。
     *
     * 原实现是直接改传进来的对象，这里改成纯函数 —— 任务对象要能安全复用。
     */
    fun effectiveHeaders(): Map<String, String> =
        if (headers.keys.any { it.equals("Referer", ignoreCase = true) }) headers
        else headers + ("Referer" to url)
}

/** 一次后台 WebView 任务的结果。 */
sealed interface WebViewTaskResult {

    /** 命中了 [WebViewTask.onInterceptRequest]，加载被中止。 */
    data class Intercepted(val interception: WebViewInterception) : WebViewTaskResult

    /** [WebViewTask.script] 的返回值，已由平台侧序列化成 JSON 字符串（可能为 null）。 */
    data class Scripted(val json: String?) : WebViewTaskResult

    /**
     * 没跑起来或者超时：WebView 环境不可用（比如 Windows 没装 WebView2 运行时）、
     * 导航失败、脚本执行失败。
     *
     * 之所以要单独一个分支而不是简单返回 null：这些情况以前会被后端**静默降级成
     * 空壳**（既不加载也不报错），脚本侧只会看到一个永远为空的结果。变成一条明确的
     * 失败信息，比让人去猜哪一步没生效强。
     */
    data class Failed(val message: String) : WebViewTaskResult
}

/** 后台任务的默认超时。`http.js` 是「页面加载完再等 10s」，这里给整轮一个上限。 */
const val BACKGROUND_WEBVIEW_TIMEOUT_MS: Long = 30_000

/**
 * 消费后台任务的 Compose 宿主。
 *
 * - **桌面**：什么窗口都不用挂（native 自己有隐藏窗口），这里只顺手预热一下环境。
 * - **Android**：顺手把 `Context` 记下来（命令式建 WebView 要用），什么都不画。
 */
@Composable
expect fun BackgroundWebViewHost()

/**
 * 跑一次后台 WebView 任务；调用方挂起直到页面出结果、命中拦截或超时。
 */
expect suspend fun loadBackgroundWebView(task: WebViewTask): WebViewTaskResult
