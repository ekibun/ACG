package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AssumptionViolatedException
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 切前后台是「改高度」，不是「卸载再挂回」。
 *
 * 钉住它的前提：canvas 缩到 0 高时，WebView2 的窗口只是跟着缩到 1×1，不会跟着父窗口一起没掉。
 * 真要回到「卸载再挂回」，`detach` 得换回同步（见 docs/development/shared-layer.md §3.5.1）。
 */
class WebviewCanvasVisibilityTest {
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

  private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

  @Test
  fun shrinkingToZeroOnlyResizesTheWindow() =
    runBlocking {
      skipUnlessHostIsAvailable(webviewHostHealth())
      if (GraphicsEnvironment.isHeadless()) throw AssumptionViolatedException("headless")

      val ctx = Webview2Context()
      val canvas = WebViewCanvas(ctx)
      // 组合里由 DisposableEffect 调用；这里是直构，得自己开
      edt { canvas.startTracking() }
      val frame = JFrame("shrink-probe")
      try {
        withTimeout(60_000) {
          edt {
            // 不给布局管理器：否则 revalidate 会把 CENTER 组件重新拉满，改不动尺寸
            frame.contentPane.layout = null
            frame.setSize(600, 400)
            canvas.setBounds(0, 0, 586, 363)
            frame.contentPane.add(canvas)
            frame.isVisible = true
          }
          val loaded = CompletableDeferred<Unit>()
          ctx.onEvent =
            { event -> if (event is WebViewEvent.PageFinished && !loaded.isCompleted) loaded.complete(Unit) }
          delay(1_500)
          ctx.navigate(pageServer.url("/page.html"))
          loaded.await()

          val before = ctx.evaluate("({ w: innerWidth, h: innerHeight })")
          println("SHRINK before=$before")

          edt { canvas.setBounds(0, 0, 586, 0) }
          delay(800)
          val shrunk = ctx.evaluate("({ w: innerWidth, h: innerHeight })")
          println("SHRINK shrunk=$shrunk")
          assertTrue(shrunk != null, "window is gone after shrinking: evaluate returned null")
          val smallHeight =
            firstInt(shrunk, "h")
          assertTrue(smallHeight <= 1, "shrinking to zero should clamp to 1px, got $smallHeight: $shrunk")

          edt { canvas.setBounds(0, 0, 586, 363) }
          delay(800)
          val restored = ctx.evaluate("({ w: innerWidth, h: innerHeight })")
          println("SHRINK restored=$restored")
          val height = firstInt(restored, "h")
          assertTrue(height > 100, "window did not grow back, height=$height: $restored")
        }
      } finally {
        edt { frame.dispose() }
        ctx.close()
      }
    }

  /** 从页面交回的 JSON 里取一个整数字段；取不到返回 -1。 */
  private fun firstInt(
    json: String?,
    field: String,
  ): Int =
    Regex("\"$field\":(\\d+)")
      .find(json.orEmpty())
      ?.groupValues
      ?.get(1)
      ?.toInt() ?: -1
}
