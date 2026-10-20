package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AvFormat
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
 * 服务端用 [TestMediaServer] 的 `hangFirstResponse` 档：**只有第一条**响应只回响应头就停住
 * （body 一个字节不给、也不 close —— close 等于告诉客户端 body 结束了，那叫「截断」不叫「卡住」），
 * 之后的请求正常发完。
 *
 * **测试自己不许被吊死**：这里测的就是「永久阻塞」，两层兜底都得有 —— 可能永久阻塞的读都走
 * `readBounded`（守护线程 + join 超时，定义见 `TestSurface.kt`），用例再带 `@Test(timeout = …)`。
 */
class HttpAbortTest {
  /**
   * 卡在请求里（响应体还没开始）→ `abort()` 必须**立刻**以「读满 `buf.size`」收场 →
   * 撤销之后**下一次读要能重新发请求并读到数据**（光叫醒不够：`cachedRsp` 也得丢掉，
   * 否则下一次会拿着一条死响应继续用）。
   */
  @Test(timeout = 60_000)
  fun abortWakesAStalledRequestAndTheNextReadReconnects() {
    val body = ByteArray(256 * 1024) { (it % 251).toByte() }
    // 只有第一条响应停住、后续正常发完：abort 之后下一次读要能重连。
    TestMediaServer(body, chunkSize = 16 * 1024, hangFirstResponse = true).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      try {
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader =
          Thread { result.set(io.read(ByteArray(4096))) }.apply {
            isDaemon = true
            start()
          }
        assertTrue(server.firstResponseHanging.await(10, TimeUnit.SECONDS), "the first request never arrived")
        reader.join(500)
        assertTrue(reader.isAlive, "the read must not return while the server is holding")

        handler.abort()
        reader.join(10_000)
        if (reader.isAlive) {
          println("[diag] reader still stuck after abort:\n" + reader.stackTrace.joinToString("\n"))
        }
        assertFalse(reader.isAlive, "abort must wake up the waiting read")
        assertEquals(
          4096,
          result.get(),
          "an aborted read must report the positive 'read a full buf.size' value so the demuxer stops at once " +
            "instead of reading again (see HttpIO.read)",
        )

        // 作废信号只活在那一次 read 里，而它掀掉的那条会话已经在 `HttpIO.read` 里丢掉了 ——
        // 这次读直接重发一条，不必等闲置超时（8 s）到点。
        val after = io.readBounded(4096, timeoutMs = 30_000)
        assertTrue(after > 0, "the read after an abort should resend by itself and get data, got $after")
      } finally {
        io.close()
      }
    }
  }
}
