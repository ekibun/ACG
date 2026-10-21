package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO.seek` 的 whence 语义 —— **打真的 [HttpIO]**，不另抄一份公式。
 *
 * 守的是一条**产线上踩过的**回归：原先 `SEEK_CUR` / `SEEK_END` 都被当成绝对偏移，
 * 而 demuxer 的回退试探发的正是 `SEEK_CUR` + 负偏移，于是它跳到错误位置。判据只能落在
 * [HttpIO.seek] 自己的返回值上 —— 抄一份同构的纯函数去断言，`HttpIO` 改错时那条用例照样绿。
 *
 * 服务端用 [TestMediaServer]（本地环回），与 `HttpReadContractTest` 共用同一份实现。
 */
class HttpSeekSemanticsTest {
  private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

  /**
   * [HttpIO.Handler.open] 的声明类型是 [AvIO]（契约面），这里转回真实现 —— 直接用 `as HttpIO`
   * 是安全的：这个 Handler 只会造 [HttpIO]。
   */
  private fun open(server: TestMediaServer): HttpIO = HttpIO.Handler().open(server.url) as HttpIO

  /** [HttpIO.read] 与 [HttpIO.seek] 都是挂起函数：测试里一律丢进协程跑。 */
  private fun HttpIO.readBlocking(size: Int): Int = runBlocking { read(ByteArray(size), size) }

  private fun HttpIO.seekBlocking(
    offset: Int,
    whence: Int,
  ): Int =
    runBlocking {
      // `whence` 与 `offset` 同名，显式命名实参避免歧义。
      seek(offset = offset, whence = whence)
    }

  @Test
  fun seekSetIsAbsolute() {
    val body = bytes(8192)
    TestMediaServer(body, honorRange = false).use { server ->
      val io = open(server)
      try {
        assertEquals(42, io.seekBlocking(42, AvIO.SEEK_SET), "SEEK_SET returns the new absolute position")
        // 位置真的是 42：读一个字节，读到的必须是第 42 个字节。
        val buf = ByteArray(1)
        assertEquals(1, runBlocking { io.read(buf, buf.size) })
        assertEquals(body[42], buf[0], "a read after SEEK_SET must start at that position")
      } finally {
        io.close()
      }
    }
  }

  @Test
  fun seekCurIsRelativeToTheLogicalCursor() {
    TestMediaServer(bytes(8192), honorRange = false).use { server ->
      val io = open(server)
      try {
        assertEquals(1000, io.seekBlocking(1000, AvIO.SEEK_SET))
        // demuxer 的回退试探就是「相对当前往回退」—— 这条曾是产线缺陷。
        assertEquals(900, io.seekBlocking(-100, AvIO.SEEK_CUR), "SEEK_CUR is relative to the cursor")
        assertEquals(1400, io.seekBlocking(500, AvIO.SEEK_CUR))
        // 退过头在哪里收口不作契约，只要不是负数即可（实现钳到 0）。
        assertTrue(io.seekBlocking(-10_000, AvIO.SEEK_CUR) >= 0, "SEEK_CUR past the start must not go negative")
      } finally {
        io.close()
      }
    }
  }

  @Test
  fun seekEndIsRelativeToTheStreamSize() {
    val body = bytes(8192)
    TestMediaServer(body, honorRange = false).use { server ->
      val io = open(server)
      try {
        // 先读一下让会话建立起来（`SEEK_END` 要的是 `Content-Length`）。
        assertTrue(io.readBlocking(16) > 0)
        assertEquals(body.size, io.seekBlocking(0, AvIO.SEEK_END), "SEEK_END + 0 is the stream length")
        assertEquals(body.size - 8, io.seekBlocking(-8, AvIO.SEEK_END), "SEEK_END offset relative to stream end")
      } finally {
        io.close()
      }
    }
  }

  @Test
  fun seekSizeAndUnknownWhenceFollowTheContract() {
    val body = bytes(8192)
    TestMediaServer(body, honorRange = false).use { server ->
      val io = open(server)
      try {
        // 会话还没建之前拿不到长度：契约要求返回 -1，返回 0 会被当成「长度为零的流」。
        assertEquals(-1, io.seekBlocking(0, AvFormat.AVSEEK_SIZE), "AVSEEK_SIZE must return -1 before a session exists")
        assertTrue(io.readBlocking(16) > 0)
        assertEquals(body.size, io.seekBlocking(0, AvFormat.AVSEEK_SIZE), "AVSEEK_SIZE returns the total stream length")
        assertEquals(-1, io.seekBlocking(0, 0x7f7f), "an unknown whence must return -1")
      } finally {
        io.close()
      }
    }
  }
}
