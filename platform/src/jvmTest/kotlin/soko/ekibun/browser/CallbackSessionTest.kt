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

/**
 * 回环回调服务器的行为：地址形状、按路径区分会话、原样交付 request-target、撤掉即 404。
 *
 * 这些是 :shared 的 `openBrowserAsync` 依赖的地基 —— 那边另有一条用例验「占位符 + 编排」，
 * 但**验不到信道本身**（它只看见返回的那串）。
 */
class CallbackSessionTest {
  /** 会话地址去掉路径的那截，即本服务器的 origin。 */
  private fun CallbackSession.origin(): String = callbackUrl.substringBeforeLast('/')

  /** 会话的路径段（带前导斜杠），浏览器要打的就是它。 */
  private fun CallbackSession.path(): String = "/" + callbackUrl.substringAfterLast('/')

  @Test
  fun callbackUrlIsLoopbackSessionAddress() {
    val session = createCallbackSession()
    try {
      val uri = URI(session.callbackUrl)
      assertEquals("http", uri.scheme, session.callbackUrl)
      assertEquals("127.0.0.1", uri.host, "只绑回环：同网段的别人不该能替用户完成登录")
      assertTrue(uri.port in 1..65535, "端口由 OS 分配：${session.callbackUrl}")
      assertTrue(
        uri.path.removePrefix("/").matches(Regex("[0-9a-f]{24}")),
        "路径段应是十六进制的会话 id：${session.callbackUrl}",
      )
      // 这两条是给换 token 用的：redirect_uri 要逐字回传，多一个 `?code=…` 或尾斜杠就不是它了。
      assertNull(uri.query, session.callbackUrl)
      assertFalse(session.callbackUrl.endsWith("/"), session.callbackUrl)
    } finally {
      session.close()
    }
  }

  @Test
  fun sessionsShareThePortAndDifferByPath() {
    val first = createCallbackSession()
    val second = createCallbackSession()
    try {
      assertEquals(
        first.origin(),
        second.origin(),
        "服务器是进程级单例：端口复用，会话靠路径区分",
      )
      assertNotEquals(first.path(), second.path())
    } finally {
      first.close()
      second.close()
    }
  }

  @Test
  fun deliversRawRequestTargetAndIgnoresOtherPaths() =
    runBlocking {
      val session = createCallbackSession()
      try {
        // 浏览器会顺手来取 favicon：不是会话路径，回 404，且不把会话带走。
        assertEquals(404, request(session.origin(), "/favicon.ico").status)

        val target = session.path() + "?code=4808dfb7&state=probe%2B1&x=%E4%B8%AD"
        val response = request(session.origin(), target)
        assertEquals(200, response.status)
        assertTrue(response.body.contains("登录完成"), response.body)

        assertEquals(
          "${session.origin()}$target",
          session.awaitCallback(),
          "交付的必须是原样的 request-target —— 解码归插件按段做",
        )
      } finally {
        session.close()
      }
    }

  @Test
  fun hittingOneSessionLeavesTheOtherPending(): Unit =
    runBlocking {
      val first = createCallbackSession()
      val second = createCallbackSession()
      try {
        assertEquals(200, request(second.origin(), second.path() + "?code=second").status)
        assertEquals("${second.origin()}${second.path()}?code=second", second.awaitCallback())
        assertFailsWith<TimeoutCancellationException>("第一个会话不该被第二个的回调兑现") {
          withTimeout(500) { first.awaitCallback() }
        }
      } finally {
        first.close()
        second.close()
      }
    }

  @Test
  fun closedSessionIsNotFound() {
    val session = createCallbackSession()
    val origin = session.origin()
    val target = session.path() + "?code=after-close"
    session.close()
    assertEquals(404, request(origin, target).status, "撤掉的会话不该还能被兑现")
  }

  private data class Response(
    val status: Int,
    val body: String,
  )

  /** 直接打原始 HTTP：要验的正是「request-target 原样到手」，套 HTTP 客户端会把编码抹平。 */
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
      // 服务器写完就关（`Connection: close`），读到 EOF 即整个响应。
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
