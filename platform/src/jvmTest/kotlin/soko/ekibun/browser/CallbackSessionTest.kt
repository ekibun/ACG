package soko.ekibun.browser

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 回环回调服务器的行为：地址形状、按路径区分会话、原样交付 request-target、撤掉即 404。 */
class CallbackSessionTest {
  /** 会话地址去掉路径的那截，即本服务器的 origin。 */
  private fun CallbackSession.origin(): String = callbackUrl.substringBeforeLast('/')

  /** 会话的路径段（带前导斜杠），浏览器要打的就是它。 */
  private fun CallbackSession.path(): String = "/" + callbackUrl.substringAfterLast('/')

  /** 地址形状：只绑回环、端口由系统给、路径段是十六进制会话号，且会话之间只靠路径区分。 */
  @Test(timeout = 60000)
  fun callbackUrlIsALoopbackSessionAddress() {
    val first = createCallbackSession()
    val second = createCallbackSession()
    try {
      val uri = URI(first.callbackUrl)
      assertEquals("http", uri.scheme, first.callbackUrl)
      assertEquals("127.0.0.1", uri.host, "loopback only: nobody else on the network may complete the login")
      assertTrue(uri.port in 1..65535, "the port is assigned by the OS: ${first.callbackUrl}")
      assertTrue(
        uri.path.removePrefix("/").matches(Regex("[0-9a-f]{24}")),
        "the path segment should be a hex session id: ${first.callbackUrl}",
      )
      // 无 query、无尾斜杠
      assertNull(uri.query, first.callbackUrl)
      assertFalse(first.callbackUrl.endsWith("/"), first.callbackUrl)

      assertEquals(
        first.origin(),
        second.origin(),
        "the server is a process-wide singleton: the port is reused and sessions are distinguished by path",
      )
      assertNotEquals(first.path(), second.path())
    } finally {
      first.close()
      second.close()
    }
  }

  /** 路由只看路径：别的路径 404、撤掉的打不通；交付出去的是原样 request-target。 */
  @Test(timeout = 60000)
  fun routesOnlyToTheAddressedSessionAndDeliversRawTarget() =
    runBlocking {
      val first = createCallbackSession()
      val second = createCallbackSession()
      try {
        // 非会话路径：回 404，且不带走任何会话
        assertEquals(404, request(second.origin(), "/favicon.ico").status)

        val target = second.path() + "?code=4808dfb7&state=probe%2B1&x=%E4%B8%AD"
        val response = request(second.origin(), target)
        assertEquals(200, response.status)
        assertTrue(response.body.isNotEmpty(), "the callback page should have a body: ${response.body}")
        assertEquals(
          "${second.origin()}$target",
          second.awaitCallback(),
          "the delivered value must be the verbatim request-target -- decoding is the plugin's job, segment by segment",
        )

        // 命中第二条不影响第一条
        assertFailsWith<TimeoutCancellationException>("the first session must not be fulfilled by the second") {
          withTimeout(500) { first.awaitCallback() }
        }

        // 撤掉的会话再也兑现不了
        val origin = first.origin()
        val afterClose = first.path() + "?code=after-close"
        first.close()
        assertEquals(404, request(origin, afterClose).status, "a closed session must not be fulfillable anymore")
      } finally {
        first.close()
        second.close()
      }
    }

  private data class Response(
    val status: Int,
    val body: String,
  )

  /** 直接打原始 HTTP，request-target 原样写出。 */
  private fun request(
    origin: String,
    target: String,
  ): Response {
    val uri = URI(origin)
    Socket(uri.host, uri.port).use { socket ->
      socket.soTimeout = REQUEST_TIMEOUT_MS
      socket.getOutputStream().apply {
        write("GET $target HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n".toByteArray())
        flush()
      }
      // 读到 EOF 即整个响应
      val raw = socket.getInputStream().readBytes().decodeToString()
      return Response(
        status = statusCodeOf(raw),
        body = raw.substringAfter("\r\n\r\n"),
      )
    }
  }

  /** 响应首行的状态码：`HTTP/1.1 200 OK` → 200。 */
  private fun statusCodeOf(response: String): Int {
    val statusLine = response.substringBefore("\r\n")
    return statusLine.split(' ')[1].toInt()
  }

  private companion object {
    const val REQUEST_TIMEOUT_MS = 10_000
  }
}
