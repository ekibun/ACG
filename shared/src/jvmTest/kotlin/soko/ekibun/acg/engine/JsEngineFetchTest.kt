package soko.ekibun.acg.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * JS 的 `fetch` 落到 ktor 上的形状。
 *
 * 打的是**真的**本地 HTTP 服务端（`com.sun.net.httpserver`）：请求方法、`Content-Type`、
 * body 这三样只有服务端那一侧看得见 —— 全在客户端内断言的话，"方法没落到 ktor 上、
 * 一律按 GET 发出去"这种失效正好落在断言之外，测不出来。
 */
class JsEngineFetchTest {
  private class Recorded(
    val method: String,
    val contentType: String?,
    val body: String,
  )

  private val recorded = AtomicReference<Recorded?>(null)

  private val server =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/echo") { exchange ->
        recorded.set(
          Recorded(
            method = exchange.requestMethod,
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            body = exchange.requestBody.readBytes().decodeToString(),
          ),
        )
        val response = """{"title":"ok"}""".encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
      }
      // 冻结/挂住的 handler 会一直占着线程，缓存池不至于被一条用例卡死。
      executor = Executors.newCachedThreadPool()
      start()
    }

  @Test
  fun postKeepsMethodContentTypeAndBody() {
    val status =
      evalAsync(
        """
        const rsp = await fetch("$baseUrl/echo", {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": "acg-test/0.1"
          },
          body: "grant_type=authorization_code&code=probe"
        });
        return rsp.status;
        """,
      )
    assertEquals(200L, status)

    val seen = assertNotNull(recorded.get(), "服务端没收到请求")
    assertEquals("POST", seen.method, "fetch 的 method 必须落到 ktor 的请求方法上")
    assertEquals(
      "application/x-www-form-urlencoded",
      seen.contentType?.substringBefore(';'),
      "显式给的 Content-Type 不能被 body 的默认类型盖掉",
    )
    assertEquals("grant_type=authorization_code&code=probe", seen.body)
  }

  private fun evalAsync(body: String): Any? =
    runBlocking {
      assertIs<Deferred<Any?>>(
        JsEngine.instance.evaluate("(async () => { $body })()", "<fetch>"),
      ).await()
    }

  @AfterTest
  fun shutdown() {
    server.stop(0)
    runBlocking { JsEngine.instance.reset() }
  }

  /** 端口由 socket 定（`port = 0`），只能在服务端起来之后问。 */
  private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
}
