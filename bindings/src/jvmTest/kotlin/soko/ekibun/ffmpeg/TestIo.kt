package soko.ekibun.ffmpeg

/**
 * 内存字节源假 IO：数据来源是构造传入的 [bytes]，read/seek 契约照抄两个真实现（FileIO /
 * HttpIO）—— 读到尾返回 [AvFormat.AVERROR_EOF]（**不能是 0**，wav 这类 demuxer 会把 0 当
 * 「读空」在文件尾无限空转）、[AvFormat.AVSEEK_SIZE] 返回总长、三种 whence 按 POSIX lseek
 * 语义。每次 [open] 交出一个从 0 开始的新游标。
 */
class TestIo(
  private val bytes: ByteArray,
) : AvIO.Handler {
  override fun open(url: String): AvIO =
    object : AvIO {
      private var offset = 0L

      override fun read(buf: ByteArray): Int {
        if (offset >= bytes.size) return AvFormat.AVERROR_EOF
        val n = minOf(buf.size.toLong(), bytes.size - offset).toInt()
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
