package soko.ekibun.acg.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import soko.ekibun.TestIo
import soko.ekibun.acg.common.Http
import soko.ekibun.ffmpeg.AvIO
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「向前 seek 该白读还是该重开会话」这条**判据**的回归 —— 盯的是「服务端被打了几次请求」。
 *
 * [HttpIO.getResponseBlocking] 第三条分支的判据：白读的**网络代价**超过 [HttpIO.skipThreshold]
 * 才换会话。那个代价是 `offset - rsp.offset - rsp.availableForRead`：**扣掉通道里已有的部分**，
 * 因为那部分白读是纯内存 memcpy、**零网络成本**。
 *
 * 扣掉的理由（判据为什么该这样，而不是「把阈值调大到 1 MiB」）：seek 要跳的距离里，通常有相当一段
 * **已经在通道里**。把整段都算成网络代价，会在「其实一个字节都不用下」的时候重开一条会话 ——
 * 而重开要付 TCP 往返 + TLS + 响应头的固定成本。
 *
 * **实测前提**（[HttpBufferedAmountProbeTest] 钉的）：缓冲量**极度依赖服务端发得多快** ——
 * 慢发（≈ 400 KiB/s）时通道**恒空**、扣减量恒为 0，与不扣减没有差别；只有**快发**时通道才铺开
 * 到 ≈ 1 MiB。所以判据级测试**必须用快发服务端**（块间不 sleep），用慢发去测会得到「扣减没生效」
 * 的假绿。
 *
 * 三条用例对应三种情形：第 1 条是扣减的收益，另两条是「不该变的没变」的护栏。
 */
class HttpSkipThresholdTest {
  /**
   * 快发媒体服务：认 Range（206）、块间不 sleep（让写侧一路挂起在 1 MiB 附近把通道灌满），
   * 记录打过来几次请求 —— 请求数就是「换了几次会话」的直接证据。
   */
  private class MediaServer(
    private val body: ByteArray,
    private val chunkSize: Int = 64 * 1024,
  ) : AutoCloseable {
    private val pool = Executors.newFixedThreadPool(4)
    private val requests = AtomicInteger(0)
    val requestCount: Int get() = requests.get()

    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/media.bin") { handle(it) }
        start()
      }

    val url: String get() = "http://127.0.0.1:${server.address.port}/media.bin"

    private fun handle(exchange: HttpExchange) {
      requests.incrementAndGet()
      val rangeStart =
        exchange.requestHeaders
          .getFirst("Range")
          ?.removePrefix("bytes=")
          ?.substringBefore('-')
          ?.toIntOrNull()
          ?: 0
      val status = if (rangeStart > 0) 206 else 200
      val length = body.size - rangeStart
      if (status == 206) {
        exchange.responseHeaders.add("Content-Range", "bytes $rangeStart-${body.size - 1}/${body.size}")
      }
      exchange.sendResponseHeaders(status, length.toLong())
      exchange.responseBody.use { out ->
        var position = rangeStart
        while (position < body.size) {
          val count = minOf(chunkSize, body.size - position)
          out.write(body, position, count)
          out.flush()
          position += count
          // 块间**不睡** —— 让服务端以本机速度灌满 TCP 缓冲，ktor 写侧放开跑（见类文档）。
        }
      }
    }

    override fun close() {
      server.stop(0)
      pool.shutdownNow()
    }
  }

  /**
   * 反射读出 `HttpIO.cachedRsp` 那条 `Http.Response` 的 [Http.Response.availableForRead]。
   *
   * 为什么得反射：`HttpIO` **不**把 rsp 暴露给测试（`cachedRsp` 是 private），而通道存量是本文件
   * 用例的**前置条件** —— 没有它就无法确认「要跳的那段确实已经在通道里」。
   *
   * 拿不到就返回 -1：调用方据此让用例**明确失败**（前置条件不成立），而不是拿一个假数字往下走。
   */
  private fun bufferedOf(io: HttpIO): Int =
    try {
      val rspField = HttpIO::class.java.getDeclaredField("cachedRsp").apply { isAccessible = true }
      val rsp = rspField.get(io) as? Http.Response ?: return -1
      rsp.availableForRead
    } catch (_: Throwable) {
      -1
    }

  /** 反射读出那条响应的网络游标（`Http.Response.offset`）—— 与 [bufferedOf] 配对看。 */
  private fun netCursorOf(io: HttpIO): Int =
    try {
      val rspField = HttpIO::class.java.getDeclaredField("cachedRsp").apply { isAccessible = true }
      (rspField.get(io) as? Http.Response)?.offset ?: -1
    } catch (_: Throwable) {
      -1
    }

  /**
   * 等通道存量涨到 [want] 以上，**期间不消费**。
   *
   * **实测：等是等不来的。** 存量会**卡在第一次读之后的那个值**（实测 3 轮都稳定在 40879 B，
   * 等 10 s 也不涨）—— 因为 ktor 的写入先进 `flushBuffer`，而 `flushBuffer` → `readBuffer` 的搬运
   * **只发生在读侧**（`moveFlushToReadBuffer`，由 `awaitContent` / `readAvailable` 触发）。
   * 不读 ⇒ 不搬 ⇒ [Http.Response.availableForRead] 量到的 `readBuffer` 永远不涨。
   *
   * ⇒ 所以「让缓冲堆起来」只能靠**读**，而读会推进网络游标 —— 这正是本文件收益用例必须把落点
   * 取在（网络游标, 网络游标+存量] 之间的原因。
   *
   * 条件**可观测**（`availableForRead` 本身），不是「睡够猜」：超时就把真实存量打出来失败。
   */
  private fun awaitBuffered(
    io: HttpIO,
    want: Int,
    timeoutMs: Long = 10_000,
  ): Int {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = -1
    while (System.currentTimeMillis() < deadline) {
      last = bufferedOf(io)
      if (last >= want) return last
      Thread.sleep(20)
    }
    assertTrue(
      false,
      "等了 ${timeoutMs}ms 通道存量仍只有 $last B，没到需要的 $want B —— " +
        "「上一步读之后有余量」这个前提不成立，本用例没检验到判据。",
    )
    return last
  }

  /**
   * **收益用例**：seek 的落点落在「网络游标之上、通道存量之内」⇒ 要白读的那段**已经在通道里**、
   * 零网络成本 ⇒ **不该**重开会话。
   *
   * **落点是按实测值算出来的，不是写死的常量** —— 「把通道喂到 `jumped` 存量」这个做法里，
   * **喂的动作本身就在推进网络游标**（每读 16 KiB，
   * `HttpIO.offset` 与 `Http.Response.offset` 一起往前）。喂到 512 KiB 存量时网络游标早已越过
   * 512 KiB ⇒ `seek(512 KiB)` 实际是**往回跳** ⇒ 命中的是「缓冲已越过落点」那条分支（必换会话），
   * 第二个请求与判据无关，断言就会变成随机红绿。
   *
   * ⇒ 正确做法：先读一次拿到实测的 `网络游标 / 通道存量`，再把落点取在两者之间
   * （`cursor + buffered / 2`），保证「要跳的距离 > 0」且「≤ 通道存量」—— 那才是第三条分支的
   * 作用范围。
   */
  @Test(timeout = 120_000)
  fun forwardSeekWithinBufferedRangeReusesSession() {
    val body = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    MediaServer(body).use { server ->
      val handler = HttpIO.Handler()
      // 直接构造 HttpIO（而不是 `handler.open()`）：后者声明返回 `AvIO`，
      // 而 [bufferedOf] / [netCursorOf] 需要 `HttpIO` 这个具体类型去反射。
      val io = HttpIO(mapOf("url" to server.url), handler)
      try {
        // 只读一次：让会话存在（`cachedRsp` 非 null、走复用分支）并让通道铺开。
        // **只读一次**是刻意的 —— 读多次会把网络游标一起推上去（见本函数 KDoc）。
        assertTrue(TestIo.readBounded(io, 16 * 1024) > 0, "第一读应当拿到数据")
        assertEquals(1, server.requestCount, "第一读应当只建一条会话")

        val cursor = netCursorOf(io)
        // 上一次读之后剩下的量（实测 ≈ 40 KiB；再多也不会自己涨，见 awaitBuffered 的 KDoc）。
        val buffered = awaitBuffered(io, 32 * 1024 + 1)
        println("[probe-skip]seek 之前：网络游标=$cursor B 通道存量=$buffered B")

        // 落点取在「网络游标之上、通道存量之内」，且距离**刚好越过阈值**（`skipThreshold` = 32768）
        // —— 这是判据的分界线：要跳的量 > 阈值时，不扣减会换会话，扣减后不会。
        val jumped = cursor + 32 * 1024 + 1
        assertTrue(
          jumped > cursor && jumped - cursor <= buffered,
          "落点必须落在（网络游标, 网络游标+存量] 之内，实际 jumped=$jumped " +
            "cursor=$cursor buffered=$buffered",
        )
        assertTrue(io.seek(jumped, AvIO.SEEK_SET) == jumped, "seek 应当成功并返回落点")

        // 关键一步：让 `getResponseBlocking` 按判据决定换不换会话。
        assertTrue(TestIo.readBounded(io, 16 * 1024) > 0, "seek 之后那一读应当拿到数据")
        val afterSeek = server.requestCount
        println("[probe-skip]跳 ${jumped - cursor} B 之后：服务端共被打了 $afterSeek 次")

        assertEquals(
          1,
          afterSeek,
          "要白读的 ${jumped - cursor} B 全在通道里（存量 $buffered B）、零网络成本，" +
            "不该重开会话。被打成 $afterSeek 次说明判据把「通道里已有的部分」也算成了网络代价。",
        )
      } finally {
        io.close()
      }
    }
  }

  /**
   * **护栏用例**：跳得比通道存量**更远**（远到通道里那些不够抵）⇒ 仍要真从网络上取 ⇒ 换会话。
   *
   * 这条钉住「扣减不是无条件的」—— 判据不能因为通道里有点东西就永远不换。
   */
  @Test(timeout = 120_000)
  fun forwardSeekBeyondBufferedRangeStillReopensSession() {
    val body = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
    MediaServer(body).use { server ->
      val handler = HttpIO.Handler()
      // 直接构造 HttpIO（而不是 `handler.open()`）：后者声明返回 `AvIO`，
      // 而 [bufferedOf] / [awaitBuffered] 需要 `HttpIO` 这个具体类型去反射。
      val io = HttpIO(mapOf("url" to server.url), handler)
      try {
        assertTrue(TestIo.readBounded(io, 16 * 1024) > 0, "第一读应当拿到数据")
        assertEquals(1, server.requestCount, "第一读应当只建一条会话")

        // 跳 3 MiB：远大于通道上限 1 MiB，扣完还是远超阈值 ⇒ 换会话（一条真落在新落点上的）。
        val jumped = 3 * 1024 * 1024
        assertTrue(io.seek(jumped, AvIO.SEEK_SET) == jumped, "seek 应当成功")
        assertTrue(TestIo.readBounded(io, 16 * 1024) > 0, "seek 之后那一读应当拿到数据")

        val afterSeek = server.requestCount
        println("[probe-skip-far] 跳 $jumped B（> 1 MiB 上限）之后：共 $afterSeek 次")
        assertEquals(
          2,
          afterSeek,
          "跳得比通道存量远得多时该换一条真落在落点上的会话（本次是第 2 条）。",
        )
      } finally {
        io.close()
      }
    }
  }

  /**
   * **护栏用例**：seek 往**回**跳（缓冲已越过目标）—— 那条响应给不出 offset 处的内容，**必须**换，
   * 与判据无关（走的是第二分支 `rsp.offset > offset`）。
   *
   * 钉住「扣减没有把这条分支也一起放行」：扣减后 `offset - rsp.offset` 会变成**负数**，
   * 那个负数不该被误判成「代价很小」而跳过换会话。
   */
  @Test(timeout = 120_000)
  fun backwardSeekAlwaysReopensSession() {
    val body = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    MediaServer(body).use { server ->
      val handler = HttpIO.Handler()
      // 直接构造 HttpIO（而不是 `handler.open()`）：后者声明返回 `AvIO`，
      // 而 [bufferedOf] / [awaitBuffered] 需要 `HttpIO` 这个具体类型去反射。
      val io = HttpIO(mapOf("url" to server.url), handler)
      try {
        // 顺序读掉 256 KiB，让网络游标明确越过后面要 seek 回去的落点。
        var read = 0
        while (read < 256 * 1024) {
          val n = TestIo.readBounded(io, 32 * 1024)
          assertTrue(n > 0, "顺序读应当持续拿到数据")
          read += n
        }
        assertEquals(1, server.requestCount, "顺序读期间不该重开会话")

        // 往回跳到远小于网络游标的位置。
        val jumped = 16 * 1024
        assertTrue(io.seek(jumped, AvIO.SEEK_SET) == jumped, "seek 应当成功")
        assertTrue(TestIo.readBounded(io, 16 * 1024) > 0, "seek 之后那一读应当拿到数据")

        val afterSeek = server.requestCount
        println("[probe-skip-back] 回跳到 $jumped B 之后：共 $afterSeek 次")
        assertEquals(
          2,
          afterSeek,
          "往回跳时原响应给不出落点处的内容，必须换一条（与阈值判据无关）。",
        )
      } finally {
        io.close()
      }
    }
  }
}
