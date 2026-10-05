package soko.ekibun.acg.common

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 探针：[Http.Response.readAll] 读完即 `close()` 的两条性质。
 *
 * `readAll` 是插件 JS 的 `Response.arrayBuffer()` 那条路，**读完就关**是它的固有语义
 * （消费者拿到字节数组就不再需要这条连接）。这里钉住两件事：
 *
 * 1. **正常路径确实关了** —— 读完之后 `isClosed` 为真。
 * 2. **异常路径也关** —— 服务端在读完之前掐断连接时 `toByteArray()` 抛异常，
 *    这时连接与响应同样必须放掉（靠 `use`；写成「读完再手动 close」会漏掉这一条）。
 *
 * 第 2 条另有一个可观测的副作用：`close()` 放掉会话的 `block` 之后 ktor 会 cleanup，
 * 而 `block` 返回与否决定了连接池里那条连接**是否被放回**。
 * 所以「异常路径上有没有漏」可以只看 `isClosed`，不必去数连接。
 */
class ReadAllClosesProbeTest {
  /** 掐断式服务端：先给若干字节，然后在 [cutAfter] 字节处直接断链。 */
  private class CuttingServer(
    private val body: ByteArray,
    private val cutAfter: Int,
  ) : AutoCloseable {
    private val pool = Executors.newFixedThreadPool(2)
    val closedPrematurely = AtomicInteger(0)

    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/x") { exchange ->
          serve(exchange)
        }
        start()
      }

    val url: String get() = "http://127.0.0.1:${server.address.port}/x"

    private fun serve(exchange: HttpExchange) {
      try {
        exchange.sendResponseHeaders(200, body.size.toLong())
        val out = exchange.responseBody
        out.write(body, 0, minOf(cutAfter, body.size))
        out.flush()
        // 不足量就掐断：客户端 `toByteArray()` 会撞上「读不够」而抛。
        if (cutAfter < body.size) {
          closedPrematurely.incrementAndGet()
          exchange.close()
        } else {
          out.close()
        }
      } catch (_: Exception) {
        // 客户端提前断开，正常。
      }
    }

    override fun close() {
      server.stop(0)
      pool.shutdownNow()
    }
  }

  @Test(timeout = 60_000)
  fun readAllClosesOnSuccess() {
    val body = ByteArray(4096) { (it % 251).toByte() }
    CuttingServer(body, cutAfter = body.size).use { server ->
      val rsp = runBlocking { Http.request(mapOf("url" to server.url)) }
      val read = runBlocking { rsp.readAll() }
      assertEquals(body.size, read.size)
      assertTrue(rsp.isClosed, "readAll 读完就该 close，否则连接一直悬着")
    }
  }

  @Test(timeout = 60_000)
  fun readAllClosesWhenBodyIsTruncated() {
    // 服务端只发一半就断链 ⇒ toByteArray() 抛 ⇒ 异常路径上仍必须 close。
    val body = ByteArray(64 * 1024) { (it % 251).toByte() }
    CuttingServer(body, cutAfter = 1024).use { server ->
      val rsp = runBlocking { Http.request(mapOf("url" to server.url)) }
      var threw = false
      try {
        runBlocking { rsp.readAll() }
      } catch (_: Exception) {
        threw = true
      }
      assertTrue(threw, "服务端掐断时 readAll 应当抛")
      assertTrue(
        rsp.isClosed,
        "**异常路径上也要 close** —— 这正是不写成「读完再手动 close」的理由",
      )
    }
  }
}
