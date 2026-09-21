package soko.ekibun.ffmpeg

interface AvIO {
  companion object {
    // POSIX lseek 的 whence 取值，各平台一致（Linux/macOS 0/1/2，Windows CRT 也是 0/1/2）。
    // 两个实现（HttpIO / FileIO）共用这一份，别各抄一遍 —— 抄漏一个分支就是静默走错位置。
    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2
  }

  fun getBufferSize(): Long

  interface Handler {
    fun open(url: String): AvIO
  }

  fun read(buf: ByteArray): Int

  fun seek(
    offset: Int,
    whence: Int,
  ): Int

  fun close()
}
