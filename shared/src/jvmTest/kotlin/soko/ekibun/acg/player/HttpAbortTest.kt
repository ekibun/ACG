package soko.ekibun.acg.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `AvIO.Handler.abort()` 的契约：**把正在等数据的那次读叫醒，而且下一次读能恢复**。为什么需要它：
 * `HttpIO.read` 等数据时卡在 native 的 `av_read_frame` 里面 —— **协程取消够不到它**（取消是协作式的，阻塞的
 * JNI 调用里没有挂起点）。于是「这一轮不要了」（seek / close，见 [AvFormat.resetChannel]）只能由另一条线程
 * 叫醒它（机制见 `playback-invariants.md` 第五节「私有作废信号」）。
 *
 * 这条用例压两个行为：① **叫醒要快** —— 信号一投，读当场以「读满 `buf.size`」收场；② **恢复要
 * 走重发** —— 叫醒的手段是掀掉那条连接，所以那条会话当场作废（[Http.Response.isClosed]），
 * 下一次读重新发一条、在落点上接着读。
 *
 * **测试自己不许被吊死**：这里测的就是「永久阻塞」，两层兜底都得有 —— 可能永久阻塞的读都走
 * [readBounded]（本文件私有：守护线程 + join 超时），用例再带 `@Test(timeout = …)`。
 */
class HttpAbortTest {
  /**
   * 一次**可能永久阻塞**的 [AvIO.read]，丢到**守护线程**上跑并给 `join` 带上超时。
   *
   * 测试不许被它吊死 —— 一次没回来的读会让整个构建挂住。超时就把线程栈打出来再失败，
   * 站在报告里能直接看出卡在哪。
   */
  private fun readBounded(
    io: AvIO,
    size: Int,
    timeoutMs: Long = 10_000,
  ): Int {
    val result = AtomicInteger(Int.MIN_VALUE)
    val reader =
      Thread { result.set(io.read(ByteArray(size))) }.apply {
        isDaemon = true
        start()
      }
    reader.join(timeoutMs)
    if (reader.isAlive) {
      println("[diag] read($size) 在 ${timeoutMs}ms 内没返回，栈：\n" + reader.stackTrace.joinToString("\n"))
      throw AssertionError("read($size) 在 ${timeoutMs}ms 内没有返回（见 stdout 的栈）")
    }
    return result.get()
  }

  /**
   * 只回响应头、**响应体一个字节都不给**，并且**停住不关**（关掉就等于告诉客户端 body
   * 结束了，那叫「截断」不叫「卡住」）。之后的请求正常把 body 发完。
   *
   * 声明长度用 `0` = 分块传输、长度未知 —— 声明固定长度却只发一部分会变成「长度对不上」
   * （客户端那边是 `ClosedByteChannelException: fixed content-length: …`），也不是「卡住」。
   */
  private class StallFirstServer(
    private val body: ByteArray,
  ) : AutoCloseable {
    private val pool = Executors.newFixedThreadPool(4)
    private val requests = AtomicInteger(0)
    private val stopped = CountDownLatch(1)

    /** 第一次请求的响应头已经出去 —— 客户端这会儿该进到等待里了。 */
    val firstHeadersSent = CountDownLatch(1)

    /** 停住期间攥着的响应体 —— 关闭时统一收掉，别让 handler 线程吊死。 */
    private val parked = Collections.synchronizedList(mutableListOf<OutputStream>())

    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/media.bin") { handle(it) }
        start()
      }

    val url: String get() = "http://127.0.0.1:${server.address.port}/media.bin"

    private fun handle(exchange: HttpExchange) {
      val index = requests.getAndIncrement()
      exchange.sendResponseHeaders(200, 0)
      val out = exchange.responseBody
      if (index == 0) {
        parked += out
        firstHeadersSent.countDown()
        // 可中断的停放：close() 里 countDown / 关掉这些响应体时能退出来。
        try {
          while (stopped.count != 0L) Thread.sleep(20)
        } catch (_: InterruptedException) {
        }
        return
      }
      out.write(body)
      out.flush()
      out.close()
    }

    override fun close() {
      stopped.countDown()
      parked.forEach { runCatching { it.close() } }
      server.stop(0)
      pool.shutdownNow()
    }
  }

  /**
   * 卡在请求里（响应体还没开始）→ `abort()` 必须**立刻**以「读满 `buf.size`」收场 →
   * 撤销之后**下一次读要能重新发请求并读到数据**（光叫醒不够：`cachedRsp` 也得丢掉，
   * 否则下一次会拿着一条死响应继续用）。
   */
  @Test(timeout = 60_000)
  fun abortWakesAStalledRequestAndTheNextReadReconnects() {
    val body = ByteArray(256 * 1024) { (it % 251).toByte() }
    StallFirstServer(body).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader =
          Thread { result.set(io.read(ByteArray(4096))) }.apply {
            isDaemon = true
            start()
          }
        assertTrue(server.firstHeadersSent.await(10, TimeUnit.SECONDS), "第一次请求没打过来")
        reader.join(500)
        assertTrue(reader.isAlive, "服务端停住时读不该返回")

        handler.abort()
        reader.join(10_000)
        if (reader.isAlive) {
          println("[diag] abort 之后 reader 还停在：\n" + reader.stackTrace.joinToString("\n"))
        }
        assertFalse(reader.isAlive, "abort 必须把等待中的读叫醒")
        assertEquals(
          4096,
          result.get(),
          "被作废的读应当报「读满 buf.size」的正数：让 demuxer 当场收手、不再重读（见 HttpIO.read）",
        )

        // 作废信号只活在那一次 read 里，而它掀掉的那条会话已经在 `HttpIO.read` 里丢掉了 ——
        // 这次读直接重发一条，不必等闲置超时（8 s）到点。
        val after = readBounded(io, 4096, timeoutMs = 30_000)
        assertTrue(after > 0, "作废之后的下一次读应当自行重发并读到数据，实际 $after")
      } finally {
        io.close()
      }
    }
  }
}
