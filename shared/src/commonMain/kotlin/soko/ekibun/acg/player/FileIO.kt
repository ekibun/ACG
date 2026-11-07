package soko.ekibun.acg.player

import soko.ekibun.common.File
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO

/** 本地文件的 [AvIO]：EOF 返回 [AvFormat.AVERROR_EOF]，出错返回 -1，只支持小于 2 GiB 的文件。 */
class FileIO(
  path: String,
) : AvIO {
  /** 底层文件句柄；打开失败时为 null。 */
  private val file = File.open(path, "r")

  /** 逻辑读写位置，与底层文件指针保持同步。 */
  private var offset = 0L

  override suspend fun runSuspend(
    buf: ByteArray,
    len: Int,
  ): Int {
    val file = file ?: return -1
    // 异常返回 -1，EOF 翻成 AVERROR_EOF
    val ret =
      try {
        file.read(buf, len)
      } catch (e: Throwable) {
        e.printStackTrace()
        return -1
      }
    if (ret > 0) offset += ret
    return if (ret < 0) AvFormat.AVERROR_EOF else ret
  }

  override fun seek(
    offset: Int,
    whence: Int,
  ): Int {
    val file = file ?: return -1
    if (whence == AvFormat.AVSEEK_SIZE) {
      // 返回流总大小
      return file.size.toInt()
    }
    return try {
      // 按 POSIX lseek 语义解释 whence
      val position =
        when (whence) {
          AvIO.SEEK_SET -> offset.toLong()
          AvIO.SEEK_CUR -> this.offset + offset
          AvIO.SEEK_END -> file.size + offset
          else -> return -1
        }.coerceAtLeast(0)
      file.seek(position)
      this.offset = position
      this.offset.toInt()
    } catch (e: Throwable) {
      e.printStackTrace()
      -1
    }
  }

  override fun close() {
    try {
      file?.close()
    } catch (_: Throwable) {
      // 关闭失败不向上抛
    }
  }
}
