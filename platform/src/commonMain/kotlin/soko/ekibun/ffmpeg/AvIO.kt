package soko.ekibun.ffmpeg

interface AvIO {
  companion object {
    // POSIX lseek 的 whence 取值。
    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2
  }

  interface Handler {
    /** 打开一个源；打不开时返回 read/seek 恒失败的 [AvIO]，不抛异常。 */
    fun open(url: String): AvIO

    /** 叫醒正在等数据的那次读（一次性事件）；没读在等时是 no-op。 */
    fun abort() {}
  }

  /** 往 [buf] 的前 [len] 个字节里读，返回实际读到的字节数（`AVERROR_EOF` / 负数 = 错误）。 */
  fun read(
    buf: ByteArray,
    len: Int,
  ): Int

  fun seek(
    offset: Int,
    whence: Int,
  ): Int

  fun close()
}
