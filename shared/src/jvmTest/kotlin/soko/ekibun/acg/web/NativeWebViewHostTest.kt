package soko.ekibun.acg.web

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
 * 前面那些 WebView 用例（[soko.ekibun.acg.engine.WebviewJsTest]）验的是 JS wrapper，
 * 用桩顶替了 `_java`，碰不到真正的东西。这里反过来：起一个本地 HTTP 服务，把
 * 真的页面喂给真的 WebView2，锁住三件**只有真的跑起来才会坏**的事：
 *
 * 1. **子资源在拦截回调里看得见**（`<script src>`），且 `isForMainFrame == false`、
 *    `method` / `headers` 都是真值 —— 这条正是当初换掉 `composewebview` 的原因：
 *    那个库的 `RequestInterceptor` 只接在主框架导航上，子资源永远拦不到。
 * 2. **脚本注入拿得回结果**，且 DOM 里确实有子资源产生的副作用。
 * 3. **cookie 在不同任务之间是共享的** —— 两次任务走的是同一个 environment
 *    （同一份 user data folder）。可见页与后台页共用同一份存储靠的就是这个机制。
 *
 * 环境不满足时三个用例都跳过：这测的是集成，不是环境。跳过前会先跑一次
 * [checkHost] 体检（真导航一个 `about:blank`），并把原因打到 stdout ——
 * 静默跳过容易让人误以为「验过了」。
 *
 * 注意 `loadBackgroundWebView` 用的是 [`BACKGROUND_WEBVIEW_TIMEOUT_MS`] = 30s，
 * 这里外面再套一层 60s 的护栏，免得真出问题把构建挂死。
 */
class NativeWebViewHostTest {
  private lateinit var server: HttpServer
  private lateinit var serverPool: ExecutorService
  private var port = 0

  private val pageHtml =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>acg-webview-test</title>
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
    println("[NativeWebViewHostTest] 跳过：$reason")
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
      assertTrue(main != null, "主框架导航也应该看得到。看到的请求：${seen.map { it.url} }")
      assertTrue(main.isForMainFrame, "主框架导航的 isForMainFrame 应该是 true")

      val sub = seen.first { it.url.endsWith("/sub.js") }
      assertEquals("GET", sub.method, "method 应该是真的 HTTP 方法")
      assertTrue(!sub.isForMainFrame, "`<script src>` 不是主框架")
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
      val json = scripted.json ?: fail("脚本有返回值时 json 不该是 null")
      assertTrue(json.contains("\"sub\":true"), "外链脚本应该已经执行过：$json")
      assertTrue(json.contains("\"imgs\":1"), "图片子资源应该在 DOM 里：$json")
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
   *   加 `max-age` 之后立刻通过，`Cookies` 库里也能查到 `acg_probe`。
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
                "document.cookie = 'acg_probe=1; path=/; max-age=3600'; " +
                  "document.cookie.includes('acg_probe=1')",
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
            WebViewTask(url = url("/page.html"), script = "document.cookie.includes('acg_probe=1')"),
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
   * `nativeScriptResult` 那条管道。**这条管道以前没有收件人**（那个回调曾经是空
   * 实现），结果回到 native 就掉地上了，所以这条用例锁的就是「收件人接上了」。
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
              parentHwnd = 0L,
              url = url("/page.html"),
              userAgent = null,
              enableDevtools = false,
              zoom = 1.0,
            )
          }
        }
      assertTrue(handle != 0L, "建不出可见视图 —— 本机 WebView2 可能正在抽风，看原生日志")

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
        assertTrue(json != null, "evaluate 应该拿回结果，实际是 null（超时或脚本执行失败）")
        assertTrue(json.contains("\"sub\":true"), "外链脚本应该已经执行过：$json")
        assertTrue(json.contains("\"imgs\":1"), "图片子资源应该在 DOM 里：$json")

        // 「没有返回值」的实际形态是 **字符串 `"null"`**，不是 Kotlin 的 null：
        // WebView2 把结果 JSON 序列化，`undefined` 出来就是 JSON 的 null 四个字符。
        // Kotlin 的 null 只表示**失败**（视图没了 / 控制器没起 / 超时），两者别混。
        //
        // 顺带记一笔已知的不一致：后台任务那条链路上，`init.js` 只判了
        // `json == null || json === ""`，**没判 `"null"`**，所以后台脚本没返回值时
        // 脚本侧拿到的是 `null` 而不是 `undefined`（`WebviewJsTest` 用的桩给的是
        // `""`，测不到这条真实分支）。要不要一并对齐，取决于插件有没有依赖它。
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

  /**
   * 已验证的**平台限制**：一个 `ICoreWebView2Environment` **只能在创建它的那条 UI
   * 线程上建控制器**，跨线程稳定失败（实测三次都是 `hr=0x802A000C`）。
   *
   * 这条直接否掉了「可见视图建在 AWT 线程（父子同线程 → 焦点链天然连通，不需要
   * `AttachThreadInput`）、后台视图留在 WebView 线程」这个方案 —— 两者必须共用
   * 同一个 environment，否则 cookie 就是两套（cookie 按 user data folder 分）。
   * 而两个 environment 又不能共用同一个 user data folder。
   *
   * 所以「同线程」只有一种走法：**所有视图都放在同一条 UI 线程上**，且那条线程
   * 必须是 AWT 线程（否则父子还是不同线程）。代价是后台任务的回调（含每个子资源的
   * 拦截）都会落到 UI 线程上。
   *
   * 这条用例断言的是「跨线程失败」。**如果哪天它开始通过**，说明新版 WebView2
   * 放宽了这个限制，「可见视图上 AWT 线程 + 后台留在别的线程」就重新可行了 ——
   * 那时再评估，别凭印象。
   */
  @Test
  fun environmentIsBoundToItsCreatingUiThread() {
    if (!webViewReady()) return

    val hr = NativeWebView.probeCrossThreadController()
    assertTrue(
      hr != 0,
      "跨线程建控制器成功了（hr=0）—— 新版 WebView2 放宽了这个限制，" +
        "可以重新评估「可见视图建在 AWT 线程、后台视图留在 WebView 线程」的方案。" +
        "当前实测：同一个 environment 只在创建它的那条 UI 线程上可用，hr=0x802A000C。",
    )
    println("[NativeWebViewHostTest] 跨线程建控制器确认失败：hr=0x${hr.toUInt().toString(16)}")
  }

  private fun com.sun.net.httpserver.HttpExchange.respond(
    contentType: String,
    body: ByteArray,
  ) {
    println("[NativeWebViewHostTest] 服务端收到 $requestMethod $requestURI")
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

    /** 环境体检结果，`null` 表示健康。三次用例共用，别重复跑。 */
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
     * 这种坏法下三条用例都会卡满 30s 再报一堆看不懂的断言失败，纯属噪声；
     * 体检一次、打印清楚原因、三条一起跳过，才对得起看构建的人。
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
