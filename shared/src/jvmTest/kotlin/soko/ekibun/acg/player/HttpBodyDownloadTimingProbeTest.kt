package soko.ekibun.acg.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.common.Http
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **探针**：`bodyAsChannel()` **没被调用**时，网络上到底下载了多少字节？
 *
 * 这决定 [Http.Response.availableForRead] 那个「通道没建时返回 0」的语义到底是
 * 「网络还没开始下」还是「**已经在下、只是本对象还没拿到通道**」——
 * 两者对 seek 判据的含义完全相反。
 *
 * 服务端只管把 body 写完（**不等**客户端读），并统计**服务端实际写出去多少字节**。
 * 若 `bytesSent` 在客户端一次 `read` 之前就已经很大 ⇒ **下载与 `bodyAsChannel()`
 * 无关**，ktor 在拿到响应时就已经在拉了。
 */
class HttpBodyDownloadTimingProbeTest {
  private class CountingServer(
    private val body: ByteArray,
    private val chunkSize: Int = 64 * 1024,
  ) : AutoCloseable {
    private val pool = Executors.newFixedThreadPool(4)
    private val sent = AtomicLong(0)
    val bytesSent: Long get() = sent.get()

    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/media.bin") { handle(it) }
        start()
      }

    val url: String get() = "http://127.0.0.1:${server.address.port}/media.bin"

    private fun handle(exchange: HttpExchange) {
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { out ->
        var pos = 0
        while (pos < body.size) {
          val n = minOf(chunkSize, body.size - pos)
          out.write(body, pos, n)
          out.flush()
          sent.addAndGet(n.toLong())
          pos += n
        }
      }
    }

    override fun close() {
      server.stop(0)
      pool.shutdownNow()
    }
  }

  @Test(timeout = 60_000)
  fun downloadsBeforeBodyAsChannelIsEverCalled() {
    val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    CountingServer(body).use { server ->
      // 建连。这一步内部会调 `delegate.bodyAsChannel()` 吗？—— 那正是要测的。
      val rsp =
        runBlocking {
          Http.request(
            mapOf<Any, Any?>(
              "url" to server.url,
              "headers" to mapOf("range" to "bytes=0-"),
            ),
          )
        }
      try {
        // 关键观测点：**一次 read 都没调**（`availableForRead` 此时必为 0，因为通道没建）。
        assertEquals(0, rsp.availableForRead, "通道没建时 availableForRead 应为 0")
        val sentBeforeRead = server.bytesSent
        println("[probe-timing] 第一次 read 之前，服务端已写出 ${sentBeforeRead}B / 共 ${body.size}B")

        // 不调 read，只等一会儿，看服务端能推多少。
        Thread.sleep(500)
        val sentAfterWait = server.bytesSent
        println("[probe-timing] 空等 500ms 后，服务端已写出 ${sentAfterWait}B / 共 ${body.size}B")

        // 断言一：还没 read 过，服务端**已经**在往外推数据 ⇒ 下载不由 `bodyAsChannel()` 触发。
        assertTrue(
          sentAfterWait > 0,
          "一次 read 都没调，服务端一个字节都没写 —— 那 `availableForRead == 0` 就等于「还没开始下」",
        )

        // 断言二：空等不足以拉完（否则这条测的是「一次性缓冲」而不是时序）。
        // 这个上界宽松，只为排除「一次性全量缓冲」那种形态。
        assertTrue(
          sentAfterWait <= body.size,
          "服务端写出 ${sentAfterWait}B 超过了 body 大小 ${body.size}B —— 有问题",
        )

        // 断言三（本用例的**核心**）：服务端已经写出 1.3 MiB 时，`availableForRead` **仍是 0**。
        // ⇒ 这个 0 **不是**「网络上没东西」，而是「本对象还没拿到通道引用」——
        //    两者对 seek 判据的含义完全相反，前者能省、后者不能。
        //    若哪天 ktor 改成惰性开流（真的一字不下），这条会失败 —— 那正是要重新评估判据的信号。
        assertEquals(
          0,
          rsp.availableForRead,
          "服务端已写出 ${sentAfterWait}B 而 availableForRead 仍为 0 —— 语义前提变了，判据要重估",
        )

        // 对照：一旦读过、通道建起来，存量就**不再为 0**（与上面那条成对，证明差异只来自通道引用）。
        val firstRead = runBlocking { rsp.read(ByteArray(16 * 1024)) }
        val afterRead = rsp.availableForRead
        println(
          "[probe-timing] 第一次 read 拿到 ${firstRead}B、之后 availableForRead=$afterRead B" +
            "（此刻服务端共写出 ${server.bytesSent}B）",
        )
        assertTrue(
          afterRead > 0,
          "第一次 read 之后通道已建，存量理应 > 0（实测 $afterRead）",
        )
      } finally {
        rsp.close()
      }
    }
  }
}
