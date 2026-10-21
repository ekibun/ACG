package soko.ekibun.common

import java.io.RandomAccessFile

/** 用 [RandomAccessFile] 打开文件；失败返回 null。 */
internal actual fun openFileHandle(
  path: String,
  mode: String,
): FileHandle? = runCatching { RandomAccessFileHandle(RandomAccessFile(path, mode)) }.getOrNull()

private class RandomAccessFileHandle(
  private val file: RandomAccessFile,
) : FileHandle {
  override val size: Long get() = file.length()

  override fun read(
    buf: ByteArray,
    len: Int,
  ): Int = file.read(buf, 0, len)

  override fun seek(position: Long) {
    file.seek(position)
  }

  override fun close() {
    file.close()
  }
}
