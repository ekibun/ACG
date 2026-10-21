package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `HttpIO.seek` 的 whence 语义：打真的 [HttpIO]，判据落在它自己的返回值上。 */
class HttpSeekSemanticsTest {
  private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

  /** 转回真实现：这个 Handler 只会造 [HttpIO]。 */
  private fun open(server: TestMediaServer): HttpIO = HttpIO.Handler().open(server.url) as HttpIO

  /** [HttpIO.read] 是挂起函数，测试里丢进协程跑。 */
  private fun HttpIO.readBlocking(size: Int): Int = runBlocking { read(ByteArray(size), size) }

  private fun HttpIO.seekBlocking(
    offset: Int,
    whence: Int,
  ): Int =
    runBlocking {
      // whence 与 offset 同名，显式命名实参避免歧义
      seek(offset = offset, whence = whence)
    }

  @Test
  fun seekSetIsAbsolute() {
    val body = bytes(8192)
    TestMediaServer(body, honorRange = false).use { server ->
      val io = open(server)
      try {
        assertEquals(42, io.seekBlocking(42, AvIO.SEEK_SET), "SEEK_SET returns the new absolute position")
        // 位置真的是 42：读一个字节比对
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
        // 回退试探：相对当前往回退
        assertEquals(900, io.seekBlocking(-100, AvIO.SEEK_CUR), "SEEK_CUR is relative to the cursor")
        assertEquals(1400, io.seekBlocking(500, AvIO.SEEK_CUR))
        // 退过头钳到 0，只要不是负数
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
        // 先读一下让会话建立（SEEK_END 要的是 Content-Length）
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
        // 会话还没建时拿不到长度：契约要求返回 -1
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
