package soko.ekibun.acg.player

import kotlin.test.Test
import kotlin.test.assertEquals
import soko.ekibun.ffmpeg.AvFormat

/**
 * `HttpIO.seek` 的 whence 语义回归。
 *
 * HttpIO 内部自己发网络请求，没法在单测里构造真实响应，因此这里用一个
 * 与之同构的纯函数 `Cursor` 锁定公式本身 —— 保证 SEEK_CUR / SEEK_END
 * 不会被"优化"回"都当绝对偏移"。
 */
class HttpSeekSemanticsTest {

  /** 与 HttpIO.seek 中 `when(whence)` 完全同构的纯函数，用于锁定语义。 */
  private class Cursor(var offset: Int, var size: Long = -1L) {
    fun seek(offset: Int, whence: Int): Long = when (whence) {
      AvFormat.AVSEEK_SIZE -> size
      SEEK_SET -> {
        this.offset = offset
        this.offset.toLong()
      }

      SEEK_CUR -> {
        this.offset = (this.offset + offset).coerceAtLeast(0)
        this.offset.toLong()
      }

      SEEK_END -> {
        if (size < 0) -1L
        else {
          this.offset = (size + offset).coerceAtLeast(0).toInt()
          this.offset.toLong()
        }
      }

      else -> -1L
    }
  }

  private companion object {
    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2
  }

  @Test
  fun seekSetIsAbsolute() {
    val c = Cursor(offset = 1000)
    assertEquals(42L, c.seek(42, SEEK_SET))
    assertEquals(42, c.offset)
  }

  @Test
  fun seekCurIsRelativeToCurrent() {
    val c = Cursor(offset = 1000)
    // demuxer 的"回退若干字节"试探：SEEK_CUR + 负偏移
    assertEquals(900L, c.seek(-100, SEEK_CUR))
    assertEquals(900, c.offset)
    assertEquals(950L, c.seek(50, SEEK_CUR))
    assertEquals(950, c.offset)
  }

  @Test
  fun seekCurNeverGoesNegative() {
    val c = Cursor(offset = 10)
    assertEquals(0L, c.seek(-100, SEEK_CUR))
    assertEquals(0, c.offset)
  }

  @Test
  fun seekEndIsRelativeToSize() {
    val c = Cursor(offset = 0, size = 5000)
    assertEquals(5000L, c.seek(0, SEEK_END))
    assertEquals(4990L, c.seek(-10, SEEK_END))
    assertEquals(4990, c.offset)
  }

  @Test
  fun seekEndUnknownSizeFails() {
    // 长度未知时必须返回 -1（AVERROR），而不是算出一个错误位置
    val c = Cursor(offset = 7, size = -1L)
    assertEquals(-1L, c.seek(-10, SEEK_END))
    assertEquals(7, c.offset)
  }

  @Test
  fun avseekSizeReturnsMinusOneWhenUnknown() {
    // 核心回归：无 Content-Length 时返回 -1，而不是 0。
    // 返回 0 会被 ffmpeg 当成"长度为 0 的流"，seek 全部判为越界。
    assertEquals(-1L, Cursor(offset = 0, size = -1L).seek(0, AvFormat.AVSEEK_SIZE))
    assertEquals(5000L, Cursor(offset = 0, size = 5000).seek(0, AvFormat.AVSEEK_SIZE))
  }

  @Test
  fun unknownWhenceFails() {
    assertEquals(-1L, Cursor(offset = 0).seek(0, 0x999))
  }
}
