package soko.ekibun.acg.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import soko.ekibun.quickjs.JSError
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [JsEngine.openBrowserAsync] 的编排：占位符换成了什么、回给插件的是什么形状。
 *
 * 「开浏览器」用注入的 `launch` 顶掉 —— 真开的话这条用例会在跑测试的机器上弹一个浏览器，
 * 那是测试不能碰的动作。信道本身（回环服务器）由 `CallbackSessionTest` 验。
 *
 * JS 那一侧的真派发归 [JsEngineDispatchTest]：那边只能走到模板校验那一支（同样因为不能真开浏览器）。
 */
class BrowserLoginTest {
  @Test
  fun substitutesEncodedCallbackAndReturnsRawCallbackUrl() =
    runBlocking {
      val launched = CompletableDeferred<String>()
      val login =
        async {
          JsEngine.instance.openBrowserAsync(TEMPLATE) { launched.complete(it) }
        }

      val authorizeUrl = launched.await()
      assertFalse(authorizeUrl.contains("{{callback}}"), "占位符必须被换掉：$authorizeUrl")
      assertTrue(
        authorizeUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A"),
        "换进去的要是百分号编码形式（授权链接把它放在查询串里）：$authorizeUrl",
      )

      val callbackUrl =
        URLDecoder.decode(authorizeUrl.substringAfter("redirect_uri=").substringBefore('&'), Charsets.UTF_8)
      val uri = URI(callbackUrl)
      assertEquals("127.0.0.1", uri.host, callbackUrl)

      // 浏览器按这个地址回来：带上授权码，值里故意塞一个会被误解码的 %26。
      val origin = callbackUrl.substringBeforeLast('/')
      val target = callbackUrl.removePrefix(origin) + "?code=4808dfb7&state=probe%26x"
      assertEquals(200, request(origin, target))

      assertEquals(
        "$origin$target",
        login.await(),
        "插件拿到的是浏览器实际请求的那个完整 URL：原样、未解码（解码归插件按段做）",
      )
    }

  @Test
  fun templateWithoutPlaceholderRejectsBeforeLaunching() =
    runBlocking {
      val failure =
        assertFailsWith<JSError> {
          JsEngine.instance.openBrowserAsync("https://example.com/auth?x=1") { _ -> error("不该开浏览器") }
        }
      assertTrue(
        failure.message.orEmpty().contains("{{callback}}"),
        "拒绝的理由要说清是缺占位符：${failure.message}",
      )
    }

  /** 直接打原始 HTTP：要验的正是「request-target 原样到手」，套客户端会把编码抹平。 */
  private fun request(
    origin: String,
    target: String,
  ): Int {
    val uri = URI(origin)
    Socket(uri.host, uri.port).use { socket ->
      socket.soTimeout = REQUEST_TIMEOUT_MS
      socket.getOutputStream().apply {
        write("GET $target HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n".toByteArray())
        flush()
      }
      val response = socket.getInputStream().readBytes().decodeToString()
      val statusLine = response.substringBefore("\r\n")
      return statusLine.split(' ')[1].toInt()
    }
  }

  private companion object {
    const val REQUEST_TIMEOUT_MS = 10_000

    /**
     * 授权链接的典型形状：client_id / response_type / state 都在，回调地址留占位符。
     * 刻意不带任何服务商信息 —— 引擎侧不含服务商知识，这条契约对谁都一样。
     */
    const val TEMPLATE =
      "https://example.com/oauth/authorize?client_id=client-id&response_type=code" +
        "&state=probe123&redirect_uri={{callback}}"
  }
}
