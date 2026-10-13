package soko.ekibun.web

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Webview2Context] 的**直构**用例：不走驱动，直接建上下文、导航、执行脚本。
 *
 * 这里收的是「验平台这一侧自己的管道」的那几条 —— 冒烟（能建、能导航、事件通）与
 * `evaluate` 的收件人接上了。**任务级**的用例（拦截、cookie 跨任务共享、整轮放行）验的是
 * 驱动骨架，已随驱动搬去 `:shared` 的 `soko.ekibun.acg.engine.WebViewHostTest`：那边能直接用
 * `loadBackgroundWebView`，而 `:platform` 的测试不该反向依赖 `:shared`。
 *
 * **不自己调 [Webview2Context.prepareEnvironment]**：伴生对象的 `init` 已经用产品那套
 * （浏览器参数 + user data folder）备好了环境；再调一次 `prepareEnvironment(null, null)` 会把
 * **浏览器参数清空**、并把 user data folder 换成 WebView2 的默认值 —— 那就不是在测产品配置了。
 *
 * 宿主坏掉时（WebView2 运行时不行）两条用例都跳过：跳过前会先跑一次
 * [webviewHostHealth] 体检并把原因打到 stdout，静默跳过会让人误以为「验过了」。
 */
class Webview2Test {
  private lateinit var server: HttpServer
  private lateinit var serverPool: ExecutorService
  private var port = 0

  private val pageHtml =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>webview2-test</title>
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

  private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(60_000) { block() }

  /** 冒烟：建上下文 → 挂两个回调 → 导航 → 归还。 */
  @Test
  fun initWebview2Context() {
    runBlocking {
      Webview2Context().use { ctx ->
        ctx.onEvent = { event -> println("onEvent($event)") }
        ctx.onInterceptor = { url, headers, method, isForMainFrame ->
          println("onWebResourceRequested($url, $method $isForMainFrame ${headers.toList()})")
          false
        }
        ctx.navigate("https://www.google.com")
        Thread.sleep(10000)
      }
    }
  }

  /**
   * 可见视图上的 `evaluate` —— 和后台任务同一个 `ExecuteScript`，只是收件人换成了
   * [Webview2Context.evaluate] 挂的那个 `CompletableDeferred`。**这条管道以前
   * 没有收件人**（结果回到 native 就掉地上了），所以这条用例锁的就是「接上了」。
   *
   * **不 attach 也能测**，这也是它能在 jvmTest 里跑的原因：
   *
   * - 上下文建出来就是隐藏的顶层窗口，父子关系要等 [Webview2Context.attach]（经
   *   JAWT 取 AWT 组件的 HWND）才建立 —— 测试里没有 AWT 组件，它就一直是个没有
   *   父窗口的隐藏窗口，**附到桌面上**；
   * - `ExecuteScript` 既不要求窗口可见，也不要求它有父窗口。
   *
   * 所以这里完全不碰 AWT，只验「建上下文 → 导航 → 执行脚本 → 拿回值」。
   * 反过来，涉及 attach / 尺寸跟随 / 焦点的部分**在 jvmTest 里测不了**，那需要真的
   * 组合树和窗口（得起 Compose 窗口，会真弹出来）。
   */
  @Test
  fun visibleViewEvaluateReturnsScriptResult() =
    runBlocking {
      val reason = webviewHostHealth()
      if (reason != null) {
        println("[Webview2Test] skip visibleViewEvaluateReturnsScriptResult: $reason")
        return@runBlocking
      }

      val ctx = Webview2Context()
      val loaded = CompletableDeferred<Unit>()
      ctx.onEvent = { event -> if (event is WebViewEvent.PageFinished) loaded.complete(Unit) }
      try {
        // 没 attach 的上下文没有「父窗口客户区」可用，尺寸得自己给；否则 controller
        // 一直是 0×0，页面未必会真的排版。
        ctx.setBounds(800, 600)
        ctx.navigate(url("/page.html"))
        guarded { loaded.await() }

        val json = ctx.evaluate("({ sub: !!window.__subLoaded, imgs: document.images.length })")
        assertTrue(json != null, "evaluate must return a result, got null (timeout or script failure)")
        assertTrue(json.contains("\"sub\":true"), "the external script should have run: $json")
        assertTrue(json.contains("\"imgs\":1"), "the image subresource should be in the DOM: $json")

        // 「没有返回值」的实际形态是**字符串 `"null"`**（WebView2 把 `undefined` 序列化成 JSON
        // null），不是 Kotlin 的 null —— 后者只表示**失败**（超时 / 脚本抛错）。
        assertEquals(
          "null",
          ctx.evaluate("undefined"),
          "没有返回值的脚本应该给 JSON 的 null 字符串",
        )
      } finally {
        ctx.dispose()
      }
    }

  private fun com.sun.net.httpserver.HttpExchange.respond(
    contentType: String,
    body: ByteArray,
  ) {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(200, body.size.toLong())
    responseBody.use { it.write(body) }
  }

  private companion object {
    /** 1×1 的透明 PNG。用真的图，免得 WebView2 那边报解码错误干扰日志。 */
    const val PNG_1X1 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
  }
}
