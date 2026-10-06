package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AvIO
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpAbandonedSessionTest {
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
    TestMediaServer(
      body,
      chunkDelayMs = 80,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val handler = HttpIO.Handler()
      val io = handler.open(server.url)
      // 先读一次让会话存在，再把它丢掉：`cachedRsp` 变 null。
      assertTrue(readBounded(io, 4096) > 0, "first read")
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
      while (System.nanoTime() < deadline && server.exited < server.entered) Thread.sleep(50)
      println("ret=$ret entered=${server.entered} exited=${server.exited} leaked=${server.entered - server.exited}")
      assertEquals(4096, ret, "aborted read must report a full-buffer read (buf.size)")
      assertEquals(
        server.entered,
        server.exited,
        "a session was left parked (entered>exited): the response abandoned by discard is never closed",
      )
    }
  }
}
