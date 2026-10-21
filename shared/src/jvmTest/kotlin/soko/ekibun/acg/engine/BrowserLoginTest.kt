package soko.ekibun.acg.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.TestPaths
import soko.ekibun.quickjs.JSError
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [JsEngine.openBrowserAsync] 的编排：占位符换成百分号编码的回调地址，插件收到浏览器请求的原始 URL。 */
class BrowserLoginTest {
  @BeforeTest
  fun initPaths() {
    TestPaths.ensureInitialized()
  }

  @Test
  fun substitutesEncodedCallbackAndReturnsRawCallbackUrl() =
    runBlocking {
      val launched = CompletableDeferred<String>()
      val login =
        async {
          JsEngine.instance.openBrowserAsync(TEMPLATE) { launched.complete(it) }
        }

      val authorizeUrl = launched.await()
      assertFalse(authorizeUrl.contains("{{callback}}"), "the placeholder must be substituted: $authorizeUrl")
      assertTrue(
        authorizeUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A"),
        "the substituted value must be percent-encoded (the authorize URL puts it in the query string): $authorizeUrl",
      )

      val callbackUrl =
        URLDecoder.decode(authorizeUrl.substringAfter("redirect_uri=").substringBefore('&'), Charsets.UTF_8)
      val uri = URI(callbackUrl)
      assertEquals("127.0.0.1", uri.host, callbackUrl)

      // 浏览器按这个地址回来：值里塞一个会被误解码的 %26
      val origin = callbackUrl.substringBeforeLast('/')
      val target = callbackUrl.removePrefix(origin) + "?code=4808dfb7&state=probe%26x"
      assertEquals(200, request(origin, target))

      assertEquals(
        "$origin$target",
        login.await(),
        "the plugin receives the exact URL the browser requested: verbatim, not decoded (decoding is the plugin's job, segment by segment)",
      )
    }

  @Test
  fun templateWithoutPlaceholderRejectsBeforeLaunching() =
    runBlocking {
      val failure =
        assertFailsWith<JSError> {
          JsEngine.instance.openBrowserAsync("https://example.com/auth?x=1") { _ -> error("must not open the browser") }
        }
      // 只钉「拒绝了」，且注入的 launch 一次都没被调
      assertTrue(
        failure.message.orEmpty().isNotEmpty(),
        "a rejection must carry a reason: ${failure.message}",
      )
    }

  /** 直接打原始 HTTP，request-target 原样写出。 */
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

    /** 授权链接的典型形状：query 里留 `{{callback}}` 占位符。 */
    const val TEMPLATE =
      "https://example.com/oauth/authorize?client_id=client-id&response_type=code" +
        "&state=probe123&redirect_uri={{callback}}"
  }
}
