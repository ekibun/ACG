package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Webview2Context] 的**直构**用例：不走驱动，直接建上下文、导航、执行脚本。
 *
 * 这里收的是「验平台这一侧自己的管道」的那几条 —— 上下文能建、能导航、`evaluate` 的收件人
 * 接上了。**任务级**的用例（拦截、cookie 跨任务共享、整轮放行）验的是驱动骨架，已随驱动
 * 搬去 `:shared` 的 `soko.ekibun.acg.engine.WebViewHostTest`：那边能直接用 `webviewAsync`，
 * 而 `:platform` 的测试不该反向依赖 `:shared`。
 *
 * 「建上下文 → 挂回调 → 导航 → 归还」那半个冒烟不单独跑：它没有任何断言，唯一能证明的
 * 「导航真的发生了」下面这条用例已经用真页面证明了。
 *
 * **不自己调 [Webview2Context.prepareEnvironment]**：伴生对象的 `init` 已经用产品那套
 * （浏览器参数 + user data folder）备好了环境；再调一次 `prepareEnvironment(null, null)` 会把
 * **浏览器参数清空**、并把 user data folder 换成 WebView2 的默认值 —— 那就不是在测产品配置了。
 *
 * 宿主坏掉时（WebView2 运行时不行）用例跳过（记成 skipped，不是 passed）：跳过前会先跑一次
 * [webviewHostHealth] 体检，原因原样进跳过消息 —— 静默跳过会让人误以为「验过了」。
 */
class Webview2Test {
  private lateinit var pageServer: TestPageServer

  @BeforeTest
  fun startServer() {
    TestPaths.ensureInitialized()
    pageServer = TestPageServer()
  }

  @AfterTest
  fun stopServer() {
    pageServer.close()
  }

  private fun url(path: String): String = pageServer.url(path)

  private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(60_000) { block() }

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
      skipUnlessHostIsAvailable(webviewHostHealth())

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
          "a script without a return value should yield the JSON string null",
        )
      } finally {
        ctx.close()
      }
    }
}
