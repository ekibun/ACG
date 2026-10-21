package soko.ekibun.acg.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import soko.ekibun.acg.TestPaths
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 拦截回调里记下一次请求的四个入参。 */
private data class SeenRequest(
  val url: String,
  val headers: Map<String, String>,
  val method: String,
  val isForMainFrame: Boolean,
)

/** WebView2 宿主的端到端用例：拦截回调看得见子资源、脚本注入拿得回结果、cookie 跨任务共享。 */
class WebViewHostTest {
  private lateinit var pageServer: TestPageServer

  @BeforeTest
  fun startServer() {
    // 后台任务建上下文前必须注入 Paths
    TestPaths.ensureInitialized()
    pageServer =
      TestPageServer(
        onRespond = { println("[WebViewHostTest] server got ${it.requestMethod} ${it.requestURI}") },
      )
  }

  @AfterTest
  fun stopServer() {
    pageServer.close()
  }

  private fun url(path: String): String = pageServer.url(path)

  private fun webViewReady() = skipUnlessHostIsAvailable(hostHealth)

  /** 35 s 护栏。 */
  private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(35_000) { block() }

  /** `<script src="/sub.js">` 这种子资源必须出现在拦截回调里，且字段是真的。 */
  @Test
  fun subResourcesAreVisibleToInterceptor() =
    runBlocking {
      webViewReady()

      val seen = Collections.synchronizedList(mutableListOf<SeenRequest>())
      val result =
        guarded {
          JsEngine.instance.webviewAsync(
            url("/page.html"),
            null,
            null,
            { u, headers, method, isForMainFrame ->
              seen += SeenRequest(u, headers, method, isForMainFrame)
              // 只在子资源上命中，回被拦请求自己的 url + headers；主框架返回 false 放行
              if (u.endsWith("/sub.js")) mapOf("url" to u, "headers" to headers) else false
            },
          )
        }

      // 回调可能还在别的线程上追加，读之前整份拷出来
      val seenSnapshot = synchronized(seen) { seen.toList() }
      val seenUrls = seenSnapshot.map { it.url }
      val hit =
        assertIs<Map<*, *>>(
          result,
          "the subresource /sub.js was not intercepted (result is $result). Seen: $seenUrls",
        )
      val hitUrl = assertIs<String>(hit["url"], "an interception result should carry back the intercepted request url")
      assertTrue(
        hitUrl.endsWith("/sub.js"),
        "the interception should hit the subresource: $hitUrl",
      )

      val main = seenSnapshot.firstOrNull { it.url.endsWith("/page.html") }
      assertTrue(main != null, "main-frame navigation should be visible; seen: ${seenSnapshot.map { it.url }}")
      assertTrue(main.isForMainFrame, "main-frame navigation must have isForMainFrame == true")

      val sub = seenSnapshot.first { it.url.endsWith("/sub.js") }
      assertEquals("GET", sub.method, "method must be the real HTTP method")
      assertTrue(!sub.isForMainFrame, "<script src> is not a main frame")
      assertTrue(
        sub.headers.isNotEmpty(),
        "subresource request headers should be real (WebView2's ICoreWebView2HttpRequestHeaders)",
      )
    }

  /** 页面加载完后注入脚本，返回值要能拿回来；DOM 里也该有子资源留下的副作用。 */
  @Test
  fun scriptResultReflectsLoadedPage() =
    runBlocking {
      webViewReady()

      val result =
        guarded {
          JsEngine.instance.webviewAsync(
            url("/page.html"),
            null,
            // 返回对象而不是字符串：结果本身就是 JSON，字符串会被再包一层引号
            "({ sub: !!window.__subLoaded, imgs: document.images.length })",
            null,
          )
        }

      val json = assertIs<String>(result, "this should take the script branch, got $result")
      assertTrue(json.contains("\"sub\":true"), "the external script should have run: $json")
      assertTrue(json.contains("\"imgs\":1"), "the image subresource should be in the DOM: $json")
    }

  /** 拦截回调全程放行（返回 falsy）之后，加载能自己跑完。 */
  @Test
  fun releasingEveryRequestLetsPageFinish() =
    runBlocking {
      webViewReady()

      val seen = Collections.synchronizedList(mutableListOf<SeenRequest>())
      val result =
        guarded {
          JsEngine.instance.webviewAsync(
            url("/page.html"),
            null,
            "({ sub: !!window.__subLoaded, imgs: document.images.length })",
            { u, headers, method, isForMainFrame ->
              seen += SeenRequest(u, headers, method, isForMainFrame)
              false
            },
          )
        }

      // 回调被反复调用过，且主框架与子资源都到过
      // 回调可能还在别的线程上追加，读之前整份拷出来
      val seenSnapshot = synchronized(seen) { seen.toList() }
      val urls = seenSnapshot.map { it.url }
      assertTrue(
        seenSnapshot.any { it.url.endsWith("/page.html") && it.isForMainFrame },
        "the main-frame request should be in the callback: $urls",
      )
      assertTrue(
        seenSnapshot.any { it.url.endsWith("/sub.js") && !it.isForMainFrame },
        "the <script> request should have been seen: $urls",
      )
      assertTrue(
        seenSnapshot.size >= 2,
        "the callback should fire for every request, but it fired only ${seenSnapshot.size} time(s): $urls",
      )

      // 全程放行 ⇒ 无命中 ⇒ 整轮以「脚本跑完」收场
      val json =
        assertIs<String>(
          result,
          "releasing everything must not produce an Intercepted outcome, got $result",
        )
      assertTrue(
        json.contains("\"sub\":true"),
        "after releasing, the <script> should really have executed (this is the evidence for the 'released' outcome): $json",
      )
      assertTrue(
        json.contains("\"imgs\":1"),
        "after releasing, the <img> subresource should also have been loaded into the DOM (proves releasing is not main-frame-only): $json",
      )
    }

  /** cookie 跨任务共享：两次任务各建自己的 environment，但共用同一份 user data folder。 */
  @Test
  fun cookiesAreSharedAcrossTasks() =
    runBlocking {
      webViewReady()

      val wrote =
        guarded {
          JsEngine.instance.webviewAsync(
            url("/page.html"),
            null,
            "document.cookie = 'webview_probe=1; path=/; max-age=3600'; " +
              "document.cookie.includes('webview_probe=1')",
            null,
          )
        }
      assertEquals(
        "true",
        assertIs<String>(wrote, "the cookie writer did not take the script branch: $wrote"),
        "the cookie should be writable",
      )

      val read =
        guarded {
          JsEngine.instance.webviewAsync(url("/page.html"), null, "document.cookie.includes('webview_probe=1')", null)
        }
      assertEquals(
        "true",
        assertIs<String>(read, "the cookie reader did not take the script branch: $read"),
        "another view should see the cookie written by the previous view -- otherwise the two are not sharing one store",
      )
    }

  internal companion object {
    /** 体检超时上界。 */
    const val HOST_HEALTH_TIMEOUT_MS = 40_000L

    /** 环境体检结果，`null` 表示健康。各用例共用一份。 */
    val hostHealth: String? by lazy { checkHost() }

    /** 宿主可用性体检：真跑一次 `about:blank` 导航并执行 `1 + 1`；null 表示健康。 */
    fun checkHost(): String? {
      // about:blank 不碰网络
      val result =
        runBlocking {
          withTimeoutOrNull(HOST_HEALTH_TIMEOUT_MS) {
            runCatching {
              JsEngine.instance.webviewAsync("about:blank", null, "1 + 1", null)
            }.getOrNull()
          }
        }
      return when {
        result == null ->
          "the host builds but the engine never responds: about:blank did not reach " +
            "NavigationCompleted within ${HOST_HEALTH_TIMEOUT_MS}ms. Most likely the WebView2 runtime on " +
            "this machine is broken (reinstalling the Evergreen Runtime usually fixes it)."
        result is String && result == "2" -> null
        else -> "host failure: the about:blank health check returned $result"
      }
    }
  }
}
