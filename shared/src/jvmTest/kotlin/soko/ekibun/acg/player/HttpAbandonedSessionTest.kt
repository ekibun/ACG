package soko.ekibun.acg.player

import com.sun.net.httpserver.HttpServer
import soko.ekibun.TestIo
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpAbandonedSessionTest {
  /**
   * 服务端**完全忽略 Range**、一路 200 从 0 重发。`getRange` 建好响应之后如果在前推途中抛出
   * （abort 正好落在前推中间），那条响应不能没人关 —— 它已经挂到 `cachedRsp` 上了，读自己的
   * `catch` 必须摘掉并关掉，否则 ktor 会话永远停在 `done.await()` 上、连接一直吊着。
   *
   * 确定性来自服务端：每 50ms 心跳一个字节，客户端一关 socket 它就**看得见**。静默期后「进过但
   * 没退出」== 漏了一条会话，不靠「睡够猜」判定。
   */
  @Test(timeout = 120_000)
  fun abandonedSessionIsClosedWhenAdvanceToThrows() {
    val body = ByteArray(200_000) { (it % 251).toByte() }
    val entered = AtomicInteger(0)
    val exited = AtomicInteger(0)
    val pool = Executors.newFixedThreadPool(8)
    val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/media.bin") { exchange ->
          entered.incrementAndGet()
          // 完全忽略 Range：一律 200 + 从 0 给整个 body。
          exchange.sendResponseHeaders(200, 0)
          val out = exchange.responseBody
          try {
            var position = 0
            while (position < body.size) {
              out.write(body, position, minOf(4096, body.size - position))
              out.flush()
              position += 4096
              Thread.sleep(80)
            }
            // 心跳：持续探socket，客户端一关就能尽快察觉。
            while (true) {
              out.write(1)
              out.flush()
              Thread.sleep(50)
            }
          } catch (_: Exception) {
            // 管道断 / 复位 == 客户端松手了 —— 这正是要数的那种退出。
          } finally {
            exited.incrementAndGet()
            runCatching { out.close() }
          }
        }
        start()
      }
    val url = "http://127.0.0.1:${server.address.port}/media.bin"
    try {
      val handler = HttpIO.Handler()
      val io = handler.open(url)
      // 先读一次让会话存在，再把它丢掉：`cachedRsp` 变 null。
      assertTrue(TestIo.readBounded(io, 4096) > 0, "first read")
      io.close()

      // 逻辑 offset 拉得很远；`cachedRsp` 是 null，所以下一次读必须在网络游标 0 上新建一条
      // 会话、靠 `advance` 一路往前磨。
      io.seek(150_000, AvIO.SEEK_SET)
      val result = AtomicInteger(Int.MIN_VALUE)
      val reader =
        Thread { result.set(io.read(ByteArray(4096))) }.apply {
          isDaemon = true
          start()
        }
      Thread.sleep(250)
      assertTrue(reader.isAlive, "read must be grinding through advance")
      handler.abort()
      reader.join(5_000)
      assertTrue(!reader.isAlive, "abort must end the read")
      val ret = result.get()
      io.close()

      // 静默期：等每个 handler 察觉并退出。
      val deadline = System.nanoTime() + 8_000_000_000L
      while (System.nanoTime() < deadline && exited.get() < entered.get()) Thread.sleep(50)
      println("ret=$ret entered=${entered.get()} exited=${exited.get()} leaked=${entered.get() - exited.get()}")
      assertEquals(AvFormat.AVERROR_EXIT, ret, "aborted read must report AVERROR_EXIT")
      assertEquals(
        entered.get(),
        exited.get(),
        "a session was left parked (entered>exited): the response abandoned by discard is never closed",
      )
    } finally {
      server.stop(0)
      pool.shutdownNow()
    }
  }
}
