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
 * **探针**：`Http.Response.availableForRead` 一开始为什么是 **0**？
 *
 * 本用例把那个 0 **拆成两个互斥的候选**（这才是「为什么」的可判定形式）：
 * - (A) 通道**真的**是空的 —— 网络上一个字节都没下。
 * - (B) 通道**已经装满**（ktor 侧在拉），只是 `Http.Response` 手里**还没有通道引用**。
 *
 * 判据用**服务端实际写出的字节数**当公证人：候选 (A) 蕴含「服务端写出量 ≈ 0」，
 * 候选 (B) 蕴含「服务端早已写出大量字节而本对象仍报 0」。
 *
 * 另外用**反射**把 `Http.Response` 的私有 `channelOrNull` 字段挖出来看一眼 ——
 * 直接证明那个 0 是 `?: 0` 兜出来的、而非「量到了 0」。
 * 反射读**私有字段**在 Kotlin 上要求 `isAccessible`，且字段名可能被混淆器改。
 * 这里只用于**诊断**（读一个 Boolean 引用、拿完就抛），不参与任何生产路径的断言逻辑；
 * 拿不到就 skip（不失败），因为它只是佐证，主判据是服务端字节数。
 */
class HttpAvailableForReadZeroProbeTest {
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

  /** 反射读 `Http.Response` 的 `channelOrNull`；拿不到返回 null（不当失败）。 */
  private fun channelRefOf(rsp: Http.Response): Any? =
    try {
      val f = Http.Response::class.java.getDeclaredField("channelOrNull")
      f.isAccessible = true
      f.get(rsp)
    } catch (_: Throwable) {
      null
    }

  @Test(timeout = 60_000)
  fun theInitialZeroIsAMissingReferenceNotAnEmptyChannel() {
    val body = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    CountingServer(body).use { server ->
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
        // 让写侧放开跑，堆满 1 MiB 上限。
        Thread.sleep(500)

        val reported = rsp.availableForRead
        val refBefore = channelRefOf(rsp)
        val sentBefore = server.bytesSent
        println(
          "[probe-zero] 读之前：availableForRead=$reported、" +
            "channelOrNull=${if (refBefore == null) "null" else refBefore.javaClass.simpleName}、" +
            "服务端已写出 ${sentBefore}B",
        )

        // 候选 (A) 被否：服务端早已推出大量字节，「网络上什么都没下」不成立。
        assertTrue(
          sentBefore > 512 * 1024,
          "服务端只写出 ${sentBefore}B —— 那个 0 可能真的是「通道空」，本用例的前提不成立",
        )

        // 那个 0 的**直接来源**：`?: 0` 兜在 channelOrNull 为 null 上。
        // （反射拿不到时不硬断言，只打印 —— 字段名/可见性属于实现细节，不该让用例红。）
        if (refBefore == null) {
          println("[probe-zero]反射未取到 channelOrNull（跳过佐证），主判据已由服务端字节数给出")
        }

        // 决定性对照：一旦 read 过（通道引用建立），同一个值立刻变成「已装满」。
        val n = runBlocking { rsp.read(ByteArray(16 * 1024)) }
        val afterRead = rsp.availableForRead
        val refAfter = channelRefOf(rsp)
        println(
          "[probe-zero] 读之后：第一次 read=$n B、availableForRead=$afterRead B、" +
            "channelOrNull=${if (refAfter == null) "null" else refAfter.javaClass.simpleName}",
        )

        // 同一个对象、同一时刻的网络状态，只因「引用建立与否」就从 0 变成 ~1 MiB。
        // ⇒ 那个 0 量的是**引用**，不是**网络**。这正是 seek 判据不能用它的原因。
        assertEquals(0, reported, "读之前应为 0（通道引用未建立）")
        assertTrue(
          refAfter != null,
          "read 之后 channelOrNull 理应已建立（否则 read 拿不到数据）",
        )
        assertTrue(
          afterRead > reported,
          "建立引用后存量应大于读之前的报值（$afterRead vs $reported）",
        )
      } finally {
        rsp.close()
      }
    }
  }
}
