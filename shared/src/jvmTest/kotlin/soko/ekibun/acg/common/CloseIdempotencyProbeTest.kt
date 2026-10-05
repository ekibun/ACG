package soko.ekibun.acg.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住 `Response.close()` **不需要**额外的幂等标志。
 *
 * 曾给它加过一个 `AtomicBoolean`（两条 close 路径：调用方显式 close、JS 侧 GC 时的
 * `JavaObject` finalizer）。这个探针证明那个标志是多余的 —— `close()` 里两句话各自已幂等：
 *
 * - 通道 `cancel`：ktor `ByteChannelImpl.cancel` 开头就是 `if (_closedCause.value != null) return`；
 * - [CompletableDeferred] `complete`：第二次调用**返回 false**，不抛、不重复触发；
 *   并发下也只有一个赢家。
 *
 * ⇒ 有人想再加一层标志时，先看这里：`[done].isCompleted` 就是现成的状态源
 * （`Response.isClosed` 现在直接读它）。
 */
class CloseIdempotencyProbeTest {
  @Test(timeout = 30_000)
  fun deferredCompleteIsIdempotentAndReportsWhetherItWon() {
    val d = CompletableDeferred<Unit>()
    assertTrue(d.complete(Unit), "第一次 complete 应返回 true")
    assertFalse(d.complete(Unit), "第二次 complete 应返回 false（不抛、也不重复触发）")
    assertTrue(d.isCompleted)
  }

  @Test(timeout = 30_000)
  fun deferredCompleteUnderConcurrencyOnlyCompletesOnce() {
    repeat(50) { round ->
      val d = CompletableDeferred<Unit>()
      val winners = AtomicInteger(0)
      val pool = Executors.newFixedThreadPool(8)
      val latch = CountDownLatch(1)
      val jobs =
        (1..8).map {
          pool.submit {
            latch.await()
            if (d.complete(Unit)) winners.incrementAndGet()
          }
        }
      latch.countDown()
      jobs.forEach { it.get(5, TimeUnit.SECONDS) }
      pool.shutdownNow()
      assertEquals(1, winners.get(), "并发 complete 只应有一个赢家（round=$round）")
    }
  }

  @Test(timeout = 30_000)
  fun closingTwiceIsSafeAndIsClosedTracksIt() {
    val pool = Executors.newFixedThreadPool(1)
    try {
      val server =
        com.sun.net.httpserver.HttpServer
          .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
          .apply {
            executor = pool
            createContext("/x") { ex ->
              ex.sendResponseHeaders(200, 0)
              val out = ex.responseBody
              try {
                val buf = ByteArray(4096)
                while (true) {
                  out.write(buf)
                  out.flush()
                  Thread.sleep(50)
                }
              } catch (_: Exception) {
                // 客户端断开会走这里
              } finally {
                runCatching { out.close() }
              }
            }
            start()
          }
      val url = "http://127.0.0.1:${server.address.port}/x"
      val rsp =
        runBlocking {
          val r = Http.request(mapOf("url" to url))
          r.read(ByteArray(64)) // 建通道
          r
        }
      assertFalse(rsp.isClosed)
      // 连关三次：不抛异常就是幂等（第一次之后两次都被上游各自的守卫挡掉）。
      rsp.close()
      rsp.close()
      rsp.close()
      assertTrue(rsp.isClosed, "isClosed 应直接读 done 的状态")
      // 已关闭的响应上再读必须当场拒绝，而不是伪装成「读空了」。
      val thrown =
        runCatching { runBlocking { rsp.read(ByteArray(64)) } }.exceptionOrNull()
      assertTrue(thrown is IllegalStateException, "已关闭后 read 应抛 IllegalStateException，实际 $thrown")
      server.stop(0)
    } finally {
      pool.shutdownNow()
    }
  }
}
