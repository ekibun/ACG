package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import soko.ekibun.common.SOCKET_TIMEOUT_MS
import soko.ekibun.ffmpeg.AvIO
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO` 必须是**流式**的：第一个 `read` 在响应体发完**之前**就该拿到数据。
 *
 * 判据用「第一次读的耗时 vs 整包下发时长」比：服务端把 body 分若干块下发、块间固定间隔，整包要约
 * 「块数 × 每块间隔」ms；第一次读只要**远早于**它（< 一半）就说明没在等整包。ktor 的便捷入口
 * 内部有一句 `call.save()` 会先整包缓冲，`Http.request` 没有那句（实测见 `silent-failures.md`
 * 的「整包缓冲」一节）。
 */
class HttpStreamingTest {
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

  @Test(timeout = 60_000)
  fun firstReadReturnsLongBeforeTheWholeBodyArrives() {
    val chunks = 20
    val chunkSize = 4096
    val delayMs = 50L
    val totalMs = chunks * delayMs
    TestMediaServer(
      body = ByteArray(chunks * chunkSize) { (it % 251).toByte() },
      chunkDelayMs = delayMs,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val io = HttpIO.Handler().open(server.url)
      try {
        val t0 = System.nanoTime()
        val first = readBounded(io, chunkSize)
        val firstMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(first > 0, "第一次读应当拿到已到达的那一块，而不是等整包")
        assertTrue(
          firstMs < totalMs / 2,
          "第一次读用了 ${firstMs}ms —— 整包要约 ${totalMs}ms，这就不是流式了",
        )

        // 把剩下的读完，字节数要对得上（流式不等于丢数据）。
        var got = first
        while (got < chunks * chunkSize) got += readBounded(io, chunkSize)
        assertEquals(chunks * chunkSize, got, "读完应当正好拿到全部字节")
      } finally {
        io.close()
      }
    }
  }

  /**
   * **没人在等的时候，「本轮作废」（`abort`）不该把会话关掉。**
   *
   * `FFPlayer` 每次 seek / 单帧步进（`-1f` / `+1f`）都要 `takeOverPlayback()` → `abort()`，
   * 而健康的流上那一刻**没有读在等网络**。如果 `abort()` 无条件关响应，每次极小步长操作都会白白
   * 重开一条会话 —— 服务端不认 Range 时更糟：新响应从头给，还得把 `offset` 之前的字节白读白丢一遍。
   *
   * 判据就是请求数：一次读 + 一次空作废 + 再读，服务端只该被打到 **1** 次。
   */
  @Test(timeout = 60_000)
  fun abortWithoutAnyWaitingReadKeepsTheSameSession() {
    TestMediaServer(
      body = ByteArray(40 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        assertTrue(readBounded(io, 4096) > 0, "第一次读应当拿到数据")
        assertEquals(1, server.requests, "第一次读应当只开一条会话")

        // 「本轮作废」—— 但上一次读早就返回了（没有读在等）。
        // 信号只活在一次 read 里，此刻没读在跑 ⇒ abort 是 no-op。
        handler.abort()

        assertTrue(readBounded(io, 4096) > 0, "作废之后仍应当能继续读到数据")
        assertEquals(1, server.requests, "没人在等时 abort 不该把会话关掉、重发请求")
      } finally {
        io.close()
      }
    }
  }

  /**
   * **正在进行、但是健康的那次读**：abort 会把它叫醒（报「读满 buf.size」的正数），
   * 但**会话必须保住** —— 会话的去留只看「还出不出字节」（闲置超过 [SOCKET_TIMEOUT_MS]
   * 才换），不是「有读正在进行」。健康的流 300ms 后就把第一块发出来了，闲置远不到阈值。
   *
   * 判据：读被叫醒成 EXIT；请求数仍是 1；撤销作废后下一次读在**同一条会话**上拿到数据。
   */
  @Test(timeout = 60_000)
  fun abortWakesAHealthyReadButKeepsTheSession() {
    val firstChunkDelayMs = 300L
    TestMediaServer(
      body = ByteArray(40 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      firstChunkDelayMs = firstChunkDelayMs,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader =
          Thread { result.set(io.read(ByteArray(4096))) }.apply {
            isDaemon = true
            start()
          }
        // 服务端还没发第一块，所以此刻它确实在「进行中」。
        Thread.sleep(100)
        assertTrue(reader.isAlive, "此刻应当在读里")

        handler.abort()
        reader.join(5_000)
        assertTrue(!reader.isAlive, "abort 必须把等待中的读叫醒")
        assertEquals(
          4096,
          result.get(),
          "被作废的读应当报「读满 buf.size」的正数：让 demuxer 当场收手、不再重读（见 HttpIO.read）",
        )

        // 下一次 read 自带全新信号，不会再被上次的 abort 影响。
        assertTrue(readBounded(io, 4096) > 0, "作废之后应当能继续读到数据")
        assertEquals(1, server.requests, "健康会话不该被 abort 拆掉、重发请求")
      } finally {
        io.close()
      }
    }
  }
}
