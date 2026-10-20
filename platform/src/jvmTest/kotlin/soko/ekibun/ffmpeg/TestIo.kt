package soko.ekibun.ffmpeg

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内存字节源假 IO：数据来源是构造传入的 [bytes]，read/seek 契约照抄两个真实现（FileIO /
 * HttpIO）—— 读到尾返回 [AvFormat.AVERROR_EOF]（**不能是 0**，wav 这类 demuxer 会把 0 当
 * 「读空」在文件尾无限空转）、[AvFormat.AVSEEK_SIZE] 返回总长、三种 whence 按 POSIX lseek
 * 语义。每次 [open] 交出一个从 0 开始的新游标。
 *
 * **`abort()` 也必须像真 IO 一样有反应**：[AvFormat.resetChannel] 的作废靠它叫醒卡在
 * `read` 上的线程；默认空实现下，`use {}` 退出时只能等通道自己满，那会让「作废」这件事在
 * 内存源上完全测不到（也就看不出它哪天坏）。[abort] 之后当前与后续的读都按
 * [AvFormat.AVERROR_EOF] 收场，`seek` 返回 -1（与 `FileIO` 关掉之后同一形态）。
 */
class TestIo(
  private val bytes: ByteArray,
) : AvIO.Handler {
  private val aborted = AtomicBoolean(false)

  override fun open(url: String): AvIO =
    object : AvIO {
      private var offset = 0L

      override fun read(buf: ByteArray): Int {
        if (aborted.get()) return AvFormat.AVERROR_EOF
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
