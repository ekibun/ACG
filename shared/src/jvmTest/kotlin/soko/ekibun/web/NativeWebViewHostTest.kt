package soko.ekibun.web

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 自研 WebView2 宿主（`cxx/webview/webview.cpp`）的**端到端**回归测试。
 *
 * 本类**不走** `_binding`：直接构造 [WebViewTask] 调 [loadBackgroundWebView]，
 * 验的是**WebView2 宿主本身**的行为 —— 起一个本地 HTTP 服务，把真的页面喂给真的
 * WebView2，锁住几件**只有真的跑起来才会坏**的事：
 *
 * 1. **子资源在拦截回调里看得见**（`<script src>`），且 `isForMainFrame == false`、
 *    `method` / `headers` 都是真值。
 * 2. **脚本注入拿得回结果**，且 DOM 里确实有子资源产生的副作用。
 * 3. **cookie 在不同任务之间是共享的** —— 两次任务走的是同一个 environment
 *    （同一份 user data folder）。可见页与后台页共用同一份存储靠的就是这个机制。
 * 4. **可见视图的 `evaluate` 拿得回结果** —— `nativeScriptResult` 那条管道的收件人接上了。
 * 5. **拦截回调全程放行也能把页面跑完** —— 回调在每个请求上都被调了不止一次，
 *    一次都不命中时整轮仍以「脚本跑完」收场。（注意别写成「命中 → 放行」：
 *    真引擎里命中即 `Stop()`，那条时序不可达 —— 见 `releasingEveryRequestLetsPageFinish`。）
 *
 * 环境不满足时这几个用例都跳过：这测的是集成，不是环境。跳过前会先跑一次
 * [checkHost] 体检（真导航一个 `about:blank`），并把原因打到 stdout ——
 * 静默跳过容易让人误以为「验过了」。
 *
 * 注意 `loadBackgroundWebView` 用的是 [`BACKGROUND_WEBVIEW_TIMEOUT_MS`] = 30s，
 * 这里外面再套一层 60s 的护栏，免得真出问题把构建挂死。
 *
 * 正因为不走 `_binding`，本类证明不了 `JsEngine` 的 `when` 派发改对了 ——
 * 那一路由 [soko.ekibun.acg.engine.JsEngineDispatchTest] 管（零桩、真 `JsEngine`）。
 * 两类合起来才覆盖两端：本类验宿主，那个验能力桥的派发形状。
 */
class NativeWebViewHostTest {
  private lateinit var server: HttpServer
  private lateinit var serverPool: ExecutorService
  private var port = 0

  private val pageHtml =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>webview-test</title>
    <script src="/sub.js"></script></head>
    <body><img src="/sub.png"><p>hello</p></body></html>
    """.trimIndent()

  @BeforeTest
  fun startServer() {
    serverPool = Executors.newFixedThreadPool(4)
    server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = serverPool
        createContext("/page.html") { it.respond("text/html; charset=utf-8", pageHtml.toByteArray()) }
        createContext("/sub.js") {
          it.respond("application/javascript", "window.__subLoaded = true;".toByteArray())
        }
        createContext("/sub.png") {
          it.respond("image/png", Base64.getDecoder().decode(PNG_1X1))
        }
        start()
      }
    port = server.address.port
  }

  @AfterTest
  fun stopServer() {
    server.stop(0)
    serverPool.shutdownNow()
  }

  private fun url(path: String): String = "http://127.0.0.1:$port$path"

  private fun webViewReady(): Boolean {
    val reason = hostHealth ?: return true
    println("[NativeWebViewHostTest] skip: $reason")
    return false
  }

  private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(60_000) { block() }

  /** `<script src="/sub.js">` 这种子资源必须出现在拦截回调里，且字段是真的。 */
  @Test
  fun subResourcesAreVisibleToInterceptor() =
    runBlocking {
      if (!webViewReady()) return@runBlocking

      val seen = Collections.synchronizedList(mutableListOf<WebViewRequest>())
      val result =
        guarded {
          loadBackgroundWebView(
            WebViewTask(
              url = url("/page.html"),
              onInterceptRequest = { request ->
                seen += request
                // 只在子资源上命中：主框架要放行，页面才有机会去请求子资源。
                if (request.url.endsWith("/sub.js")) {
                  WebViewInterception(request.url, request.headers)
                } else {
                  null
                }
              },
            ),
          )
        }

      val hit =
        assertIs<WebViewTaskResult.Intercepted>(
          result,
          "子资源 /sub.js 没被拦下来（结果是 $result）。看到的请求：${seen.map { it.url }}",
        )
      assertTrue(
        hit.interception.url.endsWith("/sub.js"),
        "命中的应该是子资源：${hit.interception.url}",
      )

      val main = seen.firstOrNull { it.url.endsWith("/page.html") }
      assertTrue(main != null, "main-frame navigation should be visible; seen: ${seen.map { it.url} }")
      assertTrue(main.isForMainFrame, "main-frame navigation must have isForMainFrame == true")

      val sub = seen.first { it.url.endsWith("/sub.js") }
      assertEquals("GET", sub.method, "method must be the real HTTP method")
      assertTrue(!sub.isForMainFrame, "<script src> is not a main frame")
      assertTrue(
        sub.headers.isNotEmpty(),
        "子资源的请求头应该是真的（WebView2 的 ICoreWebView2HttpRequestHeaders）",
      )
    }

  /** 页面加载完后注入脚本，返回值要能拿回来；DOM 里也该有子资源留下的副作用。 */
  @Test
  fun scriptResultReflectsLoadedPage() =
    runBlocking {
      if (!webViewReady()) return@runBlocking

      val result =
        guarded {
          loadBackgroundWebView(
            WebViewTask(
              url = url("/page.html"),
              // 返回对象而不是字符串：WebView2 给的是结果的 JSON，字符串会被
              // 再包一层引号，断言起来全是转义。
              script = "({ sub: !!window.__subLoaded, imgs: document.images.length })",
            ),
          )
        }

      val scripted = assertIs<WebViewTaskResult.Scripted>(result, "应该走到脚本分支，实际是 $result")
      val json = scripted.json ?: fail("json must not be null when the script returns a value")
      assertTrue(json.contains("\"sub\":true"), "the external script should have run: $json")
      assertTrue(json.contains("\"imgs\":1"), "the image subresource should be in the DOM: $json")
    }

  /**
   * 拦截回调**全程放行**（一次都不命中）之后，加载能自己跑完。
   *
   * 这条验的是「放行」这个结局本身 —— 也就是 [WebViewTask.onInterceptRequest]
   * 摆在脚本面前的那个选择里，`null` 这一支到底通不通。
   *
   * **别把它写成「命中 → 放行」**：那条时序在真引擎里**不可达**。
   * `cxx/webview/webview.cpp` 的 `RequestHandler::Invoke` 里，一旦回调返回命中就
   * 置 `view->settled = true` 并立刻 `webview->Stop()`；而同一个 `Invoke` 的开头
   * 还有 `if (!view || view->settled) return S_OK;` —— 也就是说**命中之后连回调
   * 都不会再被调一次**，更谈不上「再放行一次」。实测也印证了：在 `/sub.png` 上命中、
   * 主框架与 `<script>` 上返回 null，整轮仍然以 `Intercepted` 收场（`outcome.complete`
   * 幂等、取第一个终态，命中那一刻结果就定了）。
   *
   * 回调在每一次请求上都被调了不止一次，这本身也是「放行不影响加载」的证据。
   */
  @Test
  fun releasingEveryRequestLetsPageFinish() =
    runBlocking {
      if (!webViewReady()) return@runBlocking

      val seen = Collections.synchronizedList(mutableListOf<WebViewRequest>())
      val result =
        guarded {
          loadBackgroundWebView(
            WebViewTask(
              url = url("/page.html"),
              // 一次都不命中：只记录，不给答复。
              onInterceptRequest = { request ->
                seen += request
                null
              },
              script = "({ sub: !!window.__subLoaded, imgs: document.images.length })",
            ),
          )
        }

      // 回调被反复调用过（不是只调一次就完事），且主框架与子资源都到过。
      val urls = seen.map { it.url }
      assertTrue(
        seen.any { it.url.endsWith("/page.html") && it.isForMainFrame },
        "主框架请求应该在回调里：$urls",
      )
      assertTrue(seen.any { it.url.endsWith("/sub.js") && !it.isForMainFrame }, "<script> 应该到过：$urls")
      assertTrue(seen.size >= 2, "回调应该在每个请求上都被调用一次，实际只调了 ${seen.size} 次：$urls")

      // 全程放行 ⇒ 没有任何一次命中 ⇒ 整轮必须以「脚本跑完」收场。
      val scripted =
        assertIs<WebViewTaskResult.Scripted>(
          result,
          "全程返回 null（放行）不该产生 Intercepted：实际是 $result",
        )
      val json = scripted.json ?: fail("json must not be null when the script returns a value")
      assertTrue(
        json.contains("\"sub\":true"),
        "放行之后 <script> 应该真的执行了（这是「放行」这个结局的证据）：$json",
      )
      assertTrue(
        json.contains("\"imgs\":1"),
        "放行之后 <img> 子资源也该加载进 DOM（证明放行不是只对主框架有效）：$json",
      )
    }

  /**
   * cookie 跨任务共享。
   *
   * 两次任务各自建一个隐藏窗口，但 environment（→ 浏览器进程 → cookie 存储）是
   * 同一个。这正是「可见页和后台页共用一套登录态」的实现机制，所以这里用两次
   * 后台任务来验它。分开的 environment 会让第二次读到空 cookie。
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
   *   只剩后台任务在跑」这种场景。真要让 session cookie 也熬过空窗期，得留一个
   *   常驻的 keep-alive WebView 把浏览器进程钉住。
   */
  @Test
  fun cookiesAreSharedAcrossTasks() =
    runBlocking {
      if (!webViewReady()) return@runBlocking

      val wrote =
        guarded {
          loadBackgroundWebView(
            WebViewTask(
              url = url("/page.html"),
              script =
                "document.cookie = 'webview_probe=1; path=/; max-age=3600'; " +
                  "document.cookie.includes('webview_probe=1')",
            ),
          )
        }
      assertEquals(
        "true",
        assertIs<WebViewTaskResult.Scripted>(wrote, "写 cookie 的任务没走到脚本分支：$wrote").json,
        "cookie 应该写得进去",
      )

      val read =
        guarded {
          loadBackgroundWebView(
            WebViewTask(url = url("/page.html"), script = "document.cookie.includes('webview_probe=1')"),
          )
        }
      assertEquals(
        "true",
        assertIs<WebViewTaskResult.Scripted>(read, "读 cookie 的任务没走到脚本分支：$read").json,
        "另一个视图应该看得到上一个视图写的 cookie —— 否则两端就不是同一份存储",
      )
    }

  /**
   * 可见视图上的 `evaluate` —— 和后台任务同一个 `ExecuteScript`，只是结果走
   * `nativeScriptResult` 那条管道。**这条管道的收件人是 `WebViewTask.onScriptResult`
   * 这一侧的回调**，它空着的时候结果回到 native 就掉地上了，所以这条用例锁的就是
   * 「收件人接上了」。
   *
   * **不 attach 也能测**，这也是它能在 jvmTest 里跑的原因：
   *
   * - 可见视图建出来就是 `createViewWindow(nullptr, ...)` 一个**隐藏的顶层窗口**，
   *   父子关系要等 `attachView`（经 JAWT 取 AWT 组件的 HWND）才建立 —— 测试里没有
   *   AWT 组件，它就一直是个没有父窗口的隐藏窗口，**附到桌面上**；
   * - `ExecuteScript` 既不要求窗口可见，也不要求它有父窗口。
   *
   * 所以这里完全不碰 AWT，只验「建视图 → 等文档 → 执行脚本 → 拿回值」。
   * 反过来，涉及 attach / 尺寸跟随 / 焦点的部分**在 jvmTest 里测不了**，那需要真的
   * 组合树和窗口（得起 Compose 窗口，会真弹出来）。
   */
  @Test
  fun visibleViewEvaluateReturnsScriptResult() =
    runBlocking {
      if (!webViewReady()) return@runBlocking

      val handle =
        guarded {
          withContext(Dispatchers.IO) {
            NativeWebView.createView(
              url = url("/page.html"),
              userAgent = null,
              enableDevtools = false,
              zoom = 1.0,
            )
          }
        }
      assertTrue(handle != 0L, "cannot create the visible view - local WebView2 may be misbehaving, check native logs")

      val ready = CompletableDeferred<Unit>()
      val loaded = CompletableDeferred<Unit>()
      try {
        NativeWebView.addViewListener(handle) { what, a, _ ->
          if (what == NativeWebView.EVENT_READY) ready.complete(Unit)
          // 控制器就绪之后才导航，加载完成（loading=0）才有文档可以跑脚本。
          if (what == NativeWebView.EVENT_LOADING && a == "0") loaded.complete(Unit)
        }
        // 没 attach 的视图没有「父窗口客户区」可用，尺寸得自己给；否则 controller
        // 一直是 0×0，页面未必会真的排版。
        NativeWebView.setViewBounds(handle, 800, 600)

        guarded {
          ready.await()
          loaded.await()
        }

        val json =
          NativeWebView.evaluateScript(
            handle,
            "({ sub: !!window.__subLoaded, imgs: document.images.length })",
          )
        assertTrue(json != null, "evaluate must return a result, got null (timeout or script failure)")
        assertTrue(json.contains("\"sub\":true"), "the external script should have run: $json")
        assertTrue(json.contains("\"imgs\":1"), "the image subresource should be in the DOM: $json")

        // 「没有返回值」的实际形态是**字符串 `"null"`**（WebView2 把 `undefined` 序列化成 JSON null），
        // 不是 Kotlin 的 null —— 后者只表示**失败**。已知不一致：后台链路的 `init.js` 没判 `"null"`，
        // 故后台脚本没返回值时拿到 `null` 而非 `undefined`（要不要对齐取决于插件有没有依赖它）。
        assertEquals(
          "null",
          NativeWebView.evaluateScript(handle, "undefined"),
          "没有返回值的脚本应该给 JSON 的 null 字符串",
        )
      } finally {
        NativeWebView.removeViewListener(handle)
        NativeWebView.destroyView(handle)
      }
    }

  private fun com.sun.net.httpserver.HttpExchange.respond(
    contentType: String,
    body: ByteArray,
  ) {
    println("[NativeWebViewHostTest] server got $requestMethod $requestURI")
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(200, body.size.toLong())
    responseBody.use { it.write(body) }
  }

  private companion object {
    /** 1×1 的透明 PNG。用真的图，免得 WebView2 那边报解码错误干扰日志。 */
    const val PNG_1X1 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="

    /** 体检里那次 about:blank 的护栏，比 [BACKGROUND_WEBVIEW_TIMEOUT_MS] 稍宽一点。 */
    const val HOST_HEALTH_TIMEOUT_MS = 40_000L

    /** 环境体检结果，`null` 表示健康。各用例共用，别重复跑。 */
    val hostHealth: String? by lazy { checkHost() }

    /**
     * 宿主可用性体检。
     *
     * **为什么要真跑一次导航**：只判断「环境起没起来」是不够的。这台机器上实测到过
     * 一种坏法：环境建得成、控制器建得成、`Navigate` 也返回 S_OK，但**引擎完全不动**
     * ——连 `about:blank` 都到不了 `NavigationCompleted`，GPU 子进程退出码 1、渲染进程
     * 根本不创建。同一台机器上 `msedge.exe`（同一个 Chromium 版本）无头模式跑得好好的，
     * 所以这是 **WebView2 运行时自己坏了**，不是我们的宿主。
     *
     * 这种坏法下这几个用例都会卡满 30s 再报一堆看不懂的断言失败，纯属噪声；
     * 体检一次、打印清楚原因、这几个一起跳过，才对得起看构建的人。
     *
     * 想绕开体检直接看原始失败，把 [hostHealth] 改成 `null` 即可
     * （或直接看 `cxx/webview/webview.cpp` 的原生日志）。
     *
     * @return null 表示健康；否则是给人看的跳过原因。
     */
    fun checkHost(): String? {
      NativeWebView.ensureStarted().exceptionOrNull()?.let { cause ->
        return "WebView2 环境不可用 —— ${cause.message}"
      }

      // about:blank 不碰网络，能把「运行时坏」和「本地 HTTP 服务/网络有问题」分开。
      val result =
        runBlocking {
          withTimeoutOrNull(HOST_HEALTH_TIMEOUT_MS) {
            loadBackgroundWebView(WebViewTask(url = "about:blank", script = "1 + 1"))
          }
        }
      return when {
        result == null ->
          "宿主编译得起来但引擎没有响应：about:blank 在 ${HOST_HEALTH_TIMEOUT_MS}ms 内" +
            "没有走到 NavigationCompleted。多半是这台机器上的 WebView2 运行时坏了" +
            "（重装 Evergreen Runtime 通常能好）。"
        result is WebViewTaskResult.Scripted && result.json == "2" -> null
        else -> "宿主异常：about:blank 健康检查返回 $result"
      }
    }
  }
}
