package soko.ekibun.ffmpeg

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内存字节源假 IO：read / seek 契约同 FileIO、HttpIO，`abort()` 之后读与 seek 立刻失效。
 */
class TestIo(
  private val bytes: ByteArray,
) : AvIO.Handler {
  private val aborted = AtomicBoolean(false)

  override fun open(url: String): AvIO =
    object : AvIO {
      private var offset = 0L

      override fun read(
        buf: ByteArray,
        len: Int,
      ): Int {
        if (aborted.get()) return AvFormat.AVERROR_EOF
        if (offset >= bytes.size) return AvFormat.AVERROR_EOF
        val n = minOf(len.toLong(), bytes.size - offset).toInt()
        bytes.copyInto(buf, 0, offset.toInt(), offset.toInt() + n)
        offset += n
        return n
      }

      override fun seek(
        offset: Int,
        whence: Int,
      ): Int {
        if (aborted.get()) return -1
        if (whence == AvFormat.AVSEEK_SIZE) return bytes.size
        val position =
          when (whence) {
            AvIO.SEEK_SET -> offset.toLong()
            AvIO.SEEK_CUR -> this.offset + offset
            AvIO.SEEK_END -> bytes.size.toLong() + offset
            else -> return -1
          }.coerceAtLeast(0)
        this.offset = position
        return position.toInt()
      }

      override fun close() {}
    }

  override fun abort() {
    aborted.set(true)
  }
}
