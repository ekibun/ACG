package soko.ekibun.web

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** [WebviewContext] 的冒烟用例：建上下文 → 导航 → 注入脚本 → 归还。 */
class Webview2Test {
  @Test
  fun initWebview2Context() {
    runBlocking {
      WebviewContext.prepareEnvironment()
      WebviewContext().use { ctx ->
        ctx.listener =
          object : WebviewContext.Listener {
            override fun onEvent(
              event: String,
              data: String,
            ) {
              println("onEvent($event, $data)")
            }

            override fun onWebResourceRequested(
              url: String,
              method: String,
              isForMainFrame: Boolean,
              headers: Map<String, String>,
            ): Boolean {
              println("onWebResourceRequested($url, $method $isForMainFrame ${headers.toList()})")
              return false
            }
          }
        ctx.navigate("https://www.google.com")
        Thread.sleep(10000)
      }
      WebviewContext.releaseEnvironment()
    }
  }
}
