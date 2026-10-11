package soko.ekibun.web

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

// 桌面端的后台 WebView：headless 控制器的创建。
//
// 任务骨架（超时、终态收敛、销毁时机）在 commonMain 的 [loadBackgroundWebView]；
// 这里只负责把 [Webview2Context] 的两条回调（请求拦截 / 宿主事件）折成 [WebViewEvent]。
//
// 请求钩子接在引擎层的 `add_WebResourceRequested` +
// `AddWebResourceRequestedFilter(L"*", ALL)` 上，**含全部子资源**。
// cookie 与可见页同源：两边用的是同一个 user data folder
// （`Webview2Context.defaultUserDataDir`）—— 目录相同，WebView2 的 cookie 就是同一套。

/**
 * 桌面端 headless（后台任务）控制器的创建。
 *
 * environment 在 native 侧是**进程内共享**的（同一 user data folder 上不允许并存
 * 多个，见 `cxx/webview/webview.cmake`），但窗口 / controller 是每个上下文一个 ——
 * 拦截与脚本结果都直接落在这个上下文的 [WebviewContext.Listener] 上，不再有按
 * token 的派发表。
 *
 * 失败（环境起不来）在这里抛 [IllegalStateException]，由 common 骨架折成
 * [WebViewTaskResult.Failed]。
 */
internal suspend fun createDesktopHeadlessController(
  initialUrl: String,
  initialHeaders: Map<String, String>,
  script: String?,
  onRequest: ((WebViewRequest) -> WebViewInterception?)?,
  onEvent: (WebViewEvent) -> Unit,
): WebViewController {
  val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  // 终态只认第一个：`loadBackgroundWebView` 那边的 `CompletableDeferred.complete`
  // 是幂等的，但**注入脚本**这种异步动作不该跑两遍，所以自己再拦一道。
  val settled = AtomicBoolean(false)
  val ctx = WebviewContext()

  ctx.listener =
    object : WebviewContext.Listener {
      override fun onWebResourceRequested(
        url: String,
        method: String,
        isForMainFrame: Boolean,
        headers: Map<String, String>,
      ): Boolean {
        val callback = onRequest ?: return false
        // 见 WebViewRequest 的说明：桌面端的 isRedirect 拿不到，恒为 false。
        val hit =
          callback(
            WebViewRequest(
              url = url,
              headers = headers,
              method = method,
              isForMainFrame = isForMainFrame,
              isRedirect = false,
            ),
          ) ?: return false
        if (!settled.compareAndSet(false, true)) return true
        // 结果在这里就已经交出去了，返回 true 之后 native 才给空 204 并停掉加载。
        onEvent(WebViewEvent.RequestIntercepted(hit))
        return true
      }

      override fun onEvent(
        event: String,
        data: String,
      ) {
        when (event) {
          "navigationCompleted" ->
            scope.launch {
              if (!settled.compareAndSet(false, true)) return@launch
              // 脚本在**别的线程**上发：本回调跑在宿主线程上，而 `executeScript`
              // 要挂起等结果 —— 挂在这里等于把那条线程的消息泵卡住。
              val json =
                if (script == null) {
                  null
                } else {
                  runCatching { ctx.executeScript(script) }.getOrNull()
                }
              onEvent(WebViewEvent.ScriptFinished(json))
            }

          "navigationFailed" -> fail("导航失败：$initialUrl")
          // 浏览器 / 渲染进程挂了：这轮加载不可能再完成，直接判失败。
          "processFailed" -> fail("WebView 进程崩溃（$data）")
        }
      }

      private fun fail(message: String) {
        if (settled.compareAndSet(false, true)) onEvent(WebViewEvent.TaskFailed(message))
      }
    }

  try {
//    ctx.start()
  } catch (t: Throwable) {
    scope.cancel()
    closeContext(ctx)
    throw t
  }
  // 后台页固定 1×1 视口：只要不是 0×0，页面就会真的加载 / 跑脚本。
  ctx.setBounds(1, 1)
  runCatching { ctx.navigate(initialUrl, "GET", initialHeaders) }

  val delegate = DesktopWebViewController(ctx)
  return object : WebViewController by delegate {
    override fun dispose() {
      scope.cancel()
      delegate.dispose()
    }
  }
}

/**
 * 桌面端**不需要**预热，这个组合点留着只是为了满足 common 的契约。
 *
 * 提前把环境起起来确实能省掉第一次调用的那几百毫秒，但 environment 是**随第一个
 * 上下文建的、引用计数归零才拆**（见 [createDesktopHeadlessController]），要预热
 * 就得一直养着它（一个常驻的浏览器进程），并不划算。
 */
@Composable
actual fun BackgroundWebViewHost() = Unit
