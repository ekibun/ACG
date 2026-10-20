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

/**
 * 拦截回调里记下一次请求 —— `onInterceptRequest` 的四个入参全留：
 * `headers.isNotEmpty()` 那条断言要它，`isForMainFrame` / `method` 各自有一条。
 */
private data class SeenRequest(
  val url: String,
  val headers: Map<String, String>,
  val method: String,
  val isForMainFrame: Boolean,
)

/**
 * 自研 WebView2 宿主（`cxx/webview/webview.cpp`）的**端到端**回归测试。
 *
 * 本类**不走** `_binding`：直接调同包的 [webviewAsync] 驱动，
 * 验的是**WebView2 宿主本身**的行为 —— 起一个本地 HTTP 服务，把真的页面喂给真的
 * WebView2，锁住几件**只有真的跑起来才会坏**的事：
 *
 * 1. **子资源在拦截回调里看得见**（`<script src>`），且 `isForMainFrame == false`、
 *    `method` / `headers` 都是真值。
 * 2. **脚本注入拿得回结果**，且 DOM 里确实有子资源产生的副作用。
 * 3. **cookie 在不同任务之间是共享的** —— 两次任务走的是同一个 user data folder
 *    （可见页与后台页共用同一份存储靠的就是这个机制）。
 * 4. **拦截回调全程放行也能把页面跑完** —— 回调在每个请求上都被调了不止一次，
 *    一次都不命中时整轮仍以「脚本跑完」收场。（注意别写成「命中 → 放行」：
 *    命中即 `Stop()`，整轮加载当场中止，那条时序不可达 —— 见
 *    [releasingEveryRequestLetsPageFinish]。）
 *
 * 环境不满足时这几个用例都跳过（记成 skipped，不是 passed）：跳过前会先跑一次
 * [checkHost] 体检（真导航一个 `about:blank`），原因原样进跳过消息 ——
 * 静默跳过容易让人误以为「验过了」。
 *
 * 注意 [webviewAsync] 内部用的是 `30.seconds` 护栏（约 30s），
 * 这里外面再套一层 35s 的护栏，免得真出问题把构建挂死。
 *
 * 正因为不走 `_binding`，本类证明不了 `JsEngine` 的 `when` 派发改对了 ——
 * 那一路由同包的 [JsEngineDispatchTest] 管（零桩、真 `JsEngine`）。两类合起来才
 * 覆盖两端：本类验宿主，那个验能力桥的派发形状。
 *
 * 驱动（[webviewAsync]）随 2026-10-13 的
 * 接口重设计搬进了本包，所以本类从 `:platform` 的 jvmTest 迁到这里。留在 `:platform`
 * 的是另一种东西：**直接构平台类**验 `Webview2Context` 自己的 `evaluate` 管道
 * （`platform/jvmTest/.../web/Webview2Test.kt`、`WebViewCookieManagerTest.kt`）。
 */
class WebViewHostTest {
  private lateinit var pageServer: TestPageServer

  @BeforeTest
  fun startServer() {
    // WebView2 的 user data folder 由 `Paths` 决定：后台任务建上下文之前必须注入。
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

  /**
   * `webviewAsync` 内部是 `30.seconds` 的护栏，外面只多留 5 s：
   * 真挂住时不必让构建多等 25 s，正常路径也不受这条上限影响。
   */
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
              // 只在子资源上命中，并直接回「被拦请求自己」的 url + headers；主框架放行，
              // 页面才有机会去请求子资源。返回 false 即放行。
              if (u.endsWith("/sub.js")) mapOf("url" to u, "headers" to headers) else false
            },
          )
        }

      // 回调可能还在别的线程上追加，读之前必须整份拷出来，否则边迭代边改。
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
            // 返回对象而不是字符串：WebView2 给的是结果的 JSON，字符串会被
            // 再包一层引号，断言起来全是转义。
            "({ sub: !!window.__subLoaded, imgs: document.images.length })",
            null,
          )
        }

      val json = assertIs<String>(result, "this should take the script branch, got $result")
      assertTrue(json.contains("\"sub\":true"), "the external script should have run: $json")
      assertTrue(json.contains("\"imgs\":1"), "the image subresource should be in the DOM: $json")
    }

  /**
   * 拦截回调**全程放行**（一次都不命中）之后，加载能自己跑完。
   *
   * 这条验的是「放行」这个结局本身 —— 也就是 [webviewAsync] 的 interceptor
   * 摆在脚本面前的那个选择里，返回 falsy 这一支到底通不通。
   *
   * **别把它写成「命中 → 放行」**：那条时序在真引擎里**不可达**。
   * `cxx/webview/webview.cpp` 的 `WebResourceRequested` 回调里，一旦 JVM 侧返回
   * 命中，native 立刻给一个空 204 并 `webview_->Stop()` —— 整轮加载当场中止，
   * 不会再有第二次回调。结果在命中那一刻就定了（[webviewAsync] 的
   * `outcome.complete` 幂等、只取第一个终态）。
   *
   * 回调在每一次请求上都被调了不止一次，这本身也是「放行不影响加载」的证据。
   */
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

      // 回调被反复调用过（不是只调一次就完事），且主框架与子资源都到过。
      // 回调可能还在别的线程上追加，读之前必须整份拷出来，否则边迭代边改。
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

      // 全程放行 ⇒ 没有任何一次命中 ⇒ 整轮必须以「脚本跑完」收场。
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

  /**
   * cookie 跨任务共享。
   *
   * 两次任务各自建一个自己的 environment，但 **user data folder 是同一份**
   * （`Webview2Context.defaultUserDataDir`），所以 cookie 存储也是同一份。
   * 这正是「可见页和后台页共用一套登录态」的实现机制，所以这里用两次后台任务来验它。
   * 分开的目录会让第二次读到空 cookie。
   *
   * **用带 `max-age` 的持久 cookie，而不是 session cookie** —— 这一点踩过坑：
   *
   *   session cookie（`document.cookie = 'x=1; path=/'` 这种没有 expires/max-age 的）
   *   只活在浏览器进程的内存里，Chromium 不会把它落进 `Default/Network/Cookies`。
   *   而**两个后台任务之间一个 WebView 都不存在**时，浏览器进程会把内存态丢掉，
   *   于是第二个视图读不到 —— 当时误判成「cookie 存储没共享」，其实是丢了内存态。
   *   加 `max-age` 之后立刻通过，`Cookies` 库里也能查到 `webview_probe`。
   *
   *   对产品的影响是有限的：可见页开着的时候浏览器进程就活着，后台任务照样看得到
   *   它写的 session cookie（登录态的常见路径）。真正会丢的只有「所有视图都关掉、
   *   只剩后台任务在跑」这种场景。
   */
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
    /** 体检里那次 about:blank 的护栏，比 `30.seconds` 稍宽一点。 */
    const val HOST_HEALTH_TIMEOUT_MS = 40_000L

    /** 环境体检结果，`null` 表示健康。各用例共用，别重复跑 —— [JsEngineDispatchTest] 也读这一份。 */
    val hostHealth: String? by lazy { checkHost() }

    /**
     * 宿主可用性体检。
     *
     * **为什么要真跑一次导航**：只判断「环境起没起来」是不够的。这台机器上实测到过
     * **两种**坏法，都长得很像「代码没写对」：
     *
     * 1. **引擎完全不动** —— 环境建得成、控制器建得成、`Navigate` 也返回 S_OK，但连
     *    `about:blank` 都到不了 `NavigationCompleted`，GPU 子进程退出码 1、渲染进程根本不创建；
     * 2. **子进程崩了**（2026-10-13 实测）—— `processFailed` 直接报
     *    `kind=6/4 reason=3 exit=7`（GPU / utility 子进程 CRASHED），连 `about:blank` 都过不去；
     *    宿主 JVM 随后还会随机死在 `0xC0000005` / `0x80000003`。
     *
     * 两种情况里同一台机器上的 `msedge.exe`（同一个 Chromium 版本）无头模式都跑得好好的，
     * 所以是 **WebView2 运行时自己坏了**，不是我们的宿主（重装 Evergreen Runtime 通常能好）。
     *
     * 这种坏法下这几个用例都会卡满 30s 再报一堆看不懂的断言失败，纯属噪声；
     * 体检一次、说清原因、这几个一起记成 skipped，才对得起看构建的人。
     *
     * 想绕开体检直接看原始失败，跑测试时加 `-Dacg.test.skipUnavailableHost=false`
     * （或直接看 `cxx/webview/webview.cpp` 的原生日志）。
     *
     * @return null 表示健康；否则是给人看的跳过原因。
     */
    fun checkHost(): String? {
      // about:blank 不碰网络，能把「运行时坏」和「本地 HTTP 服务/网络有问题」分开。
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
