package soko.ekibun.ffmpeg

import java.io.File
import java.io.RandomAccessFile

/** 单文件版 `AvIO.Handler`：保留真实路径让 ffmpeg 猜容器；打不开在构造时抛出（`open` 跑在 JNI 里）。 */
class TestFileIO(
  private val file: File,
) : AvIO.Handler() {
  init {
    require(file.isFile) { "test media missing: $file" }
  }

  /** 只服务这一个文件，忽略 ffmpeg 给的 url。 */
  override fun open(url: String): AvIO = RandomAccessIO(RandomAccessFile(file, "r"))
}

/** 按 ffmpeg 给的 url 开文件（HLS 分片是相对路径）；打不开返回恒失败的 `AvIO`，`open` 里不能抛。 */
class TestLocalIO : AvIO.Handler() {
  override fun open(url: String): AvIO {
    val file = File(urlToPath(url))
    if (!file.isFile) return FailingAvIO
    return RandomAccessIO(RandomAccessFile(file, "r"))
  }

  /** `file://` 去掉前缀；Windows 盘符前那个多余的斜杠也要去掉。 */
  private fun urlToPath(url: String): String {
    val path = url.removePrefix("file://")
    return if (path.length > 3 && path[0] == '/' && path[2] == ':') path.substring(1) else path
  }
}

/** 本地文件上的 [AvIO]：读与 seek 都直接落到 [RandomAccessFile]。 */
internal class RandomAccessIO(
  private val handle: RandomAccessFile,
) : AvIO {
  override suspend fun runSuspend(
    buf: ByteArray,
    len: Int,
  ): Int {
    val ret = runCatching { handle.read(buf, 0, len) }.getOrDefault(-1)
    return if (ret < 0) AvFormat.AVERROR_EOF else ret
  }

  override fun seek(
    offset: Int,
    whence: Int,
  ): Int {
    if (whence == AvFormat.AVSEEK_SIZE) return handle.length().toInt()
    val base =
      when (whence) {
        AvIO.SEEK_SET -> 0L
        AvIO.SEEK_CUR -> handle.filePointer
        AvIO.SEEK_END -> handle.length()
        else -> return -1
      }
    return runCatching {
      handle.seek((base + offset).coerceAtLeast(0))
      handle.filePointer.toInt()
    }.getOrDefault(-1)
  }

  override fun close() {
    runCatching { handle.close() }
  }
}
