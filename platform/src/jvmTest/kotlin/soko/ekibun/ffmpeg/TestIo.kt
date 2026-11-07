package soko.ekibun.ffmpeg

/** 内存字节源：EOF 给 `AVERROR_EOF`，越界 seek 给 -1。 */
class TestIO(
  private val bytes: ByteArray,
) : AvIO.Handler() {
  override fun open(url: String): AvIO =
    object : AvIO {
      private var offset = 0L

      override suspend fun runSuspend(
        buf: ByteArray,
        len: Int,
      ): Int {
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
}

/** 恒失败的 `AvIO`：`read` / `seek` 一律 -1，让 `avformat_open_input` 自己失败。 */
internal object FailingAvIO : AvIO {
  override suspend fun runSuspend(
    buf: ByteArray,
    len: Int,
  ): Int = -1

  override fun seek(
    offset: Int,
    whence: Int,
  ): Int = -1

  override fun close() {}
}
