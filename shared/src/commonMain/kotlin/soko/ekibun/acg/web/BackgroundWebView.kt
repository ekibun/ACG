package soko.ekibun.acg.web

import androidx.compose.runtime.Composable

/**
 * 后台 WebView —— 插件 JS 里 `webview(...)` 的宿主抽象。
 *
 * 对应 BangumiPlugin `app/src/main/assets/modules/http.js` 里的 `webviewload` /
 * `__webview__`：脚本要一个「能跑页面 JS、能拦请求、但不占界面」的 WebView。
 *
 * 两端实现方式不同，但对外契约一致：
 *
 * | | 实现 | 请求可见范围 |
 * | --- | --- | --- |
 * | Android | 命令式建一个无头 `android.webkit.WebView`（照抄 `BackgroundWebView`） | `shouldInterceptRequest`，**含全部子资源** |
 * | 桌面 | 在不显示的 Nucleus 窗口里组合一个 Compose `WebView`（WebView2/WKWebView/WebKit2GTK） | 只有主框架导航：库的 `RequestInterceptor` 接在 `addNavigateListener` 上 |
 *
 * 这个差别**不是平台能力问题，是库把钩子接在了「导航」上**（反编译 1.0.3 确认）：
 * 三端桌面后端的 JNI 桥都只有 `addNavigateListener(handle, (url: String) -> Boolean)`
 * 这一个请求相关入口，Windows 那份 native 只注册了
 * `ICoreWebView2NavigationStartingEventHandler`（DLL 里 `WebResourceRequested`
 * 一次都没出现）；Android 那份也**没有** override `shouldInterceptRequest`，而是把
 * `onInterceptUrlRequest` 接在 `WebViewClient.shouldOverrideUrlLoading` 上 ——
 * 同样是主框架导航。所以 README 那句 "RequestInterceptor does **not** intercept
 * sub-resources" 是四端通吃的结论，不是桌面端独有的毛病。
 *
 * 由此有两条**写脚本时必须知道**的后果：
 *
 * 1. 靠拦媒体分片（m3u8/ts）拿真实地址的写法，在桌面端永远拦不到；
 * 2. 桌面端回调里只有 url 是真的，其余字段是占位值 —— 库那边 `WebRequest` 被构造成
 *    `headers = emptyMap()`、`isForMainFrame = true`、`isRedirect = true`、
 *    `method` 取默认值。所以
 *    `(request) => !request.isForMainFrame && request.headers.Range` 这种判断
 *    **在桌面端恒不成立**，别拿它当「有没有命中」的依据。
 *
 * 要真支持子资源拦截，得改那层 C++ shim 和它的 JNI 面。引擎本身是支持的
 * （WebView2 有 `add_WebResourceRequested` + `AddWebResourceRequestedFilter("*", ALL)`，
 * Android 有 `shouldInterceptRequest`；只有 macOS 的 WKWebView 是真做不到任意 https 拦截，
 * 得靠自定义 scheme + `WKURLSchemeHandler`），但预编译产物里没有这个入口，本工程改不了。
 */

/**
 * 后台 WebView 看到的一个请求。
 *
 * 字段是否可信，取决于哪一端在报（见文件头的说明）：
 *
 * - **Android**（我们自己写的 `shouldInterceptRequest`，含子资源）：五个字段都是真的。
 * - **桌面**（库的 `RequestInterceptor`，只有主框架导航）：只有 [url] 是真的，
 *   [headers] 恒为空、[method] 取默认值、[isForMainFrame] 与 [isRedirect] 恒为 `true`。
 *   拿不到 `Range` 之类的请求头，也就没法照 `http.js` 的路子靠分片请求反推媒体地址。
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
     * 命中即中止加载。返回 null 表示放行。
     *
     * **会在 WebView 的回调线程上被调用**，实现里不要假设自己在哪条线程。
     *
     * 但有一条硬约束：**不能从 JS 调用内部同步回调它**。脚本调 `webviewAsync`
     * 时 `_java` 仍在 native `evaluate` 里没返回，此时反向去调 JS 函数是对引擎的
     * 重入调用，本桥会让整轮 Promise 停摆（实测挂死不返回，耗时也不增长）。
     * 所以拦截回调必须来自平台侧（`shouldInterceptRequest` / `RequestInterceptor`），
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
     * 没跑起来：没有可用的 WebView 后端、宿主窗口没挂上、或者超时。
     *
     * 之所以要单独一个分支而不是简单返回 null：桌面端「没装 WebView2 运行时」时
     * 库会**静默降级成空壳**（既不加载也不报错），脚本侧只会看到一个永远为空的
     * 结果。把它变成一条明确的失败信息，比让人去猜哪一步没生效强。
     */
    data class Failed(val message: String) : WebViewTaskResult
}

/** 后台任务的默认超时。`http.js` 是「页面加载完再等 10s」，这里给整轮一个上限。 */
const val BACKGROUND_WEBVIEW_TIMEOUT_MS: Long = 30_000

/**
 * 消费后台任务的 Compose 宿主。
 *
 * - **桌面**：必须挂在一个**不显示**的窗口里（见 `desktopApp/main.kt`）。
 *   WebView2 需要真实 HWND，拿不到就静默降级成空壳，所以没有捷径可走。
 * - **Android**：只是顺手把 `Context` 记下来（命令式建 WebView 要用），什么都不画。
 */
@Composable
expect fun BackgroundWebViewHost()

/**
 * 跑一次后台 WebView 任务；调用方挂起直到页面出结果、命中拦截或超时。
 *
 * 未注册宿主（桌面忘了挂窗口）时抛 [IllegalStateException]。
 */
expect suspend fun loadBackgroundWebView(task: WebViewTask): WebViewTaskResult
