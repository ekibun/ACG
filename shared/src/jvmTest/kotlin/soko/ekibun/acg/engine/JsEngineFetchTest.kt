package soko.ekibun.acg.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.TestPaths
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** JS 的 `fetch` 落到网络上的形状：方法、`Content-Type`、body，都由本地服务端记录。 */
class JsEngineFetchTest {
  @BeforeTest
  fun initPaths() {
    TestPaths.ensureInitialized()
  }

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
      // 缓存池：挂住的 handler 不卡死别的用例
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

    val seen = assertNotNull(recorded.get(), "the server never received a request")
    assertEquals("POST", seen.method, "the fetch method must propagate to the outgoing request method")
    assertEquals(
      "application/x-www-form-urlencoded",
      seen.contentType?.substringBefore(';'),
      "an explicit Content-Type must not be overridden by the body's default type",
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
