package soko.ekibun.common

import java.io.RandomAccessFile

internal actual fun openFileHandle(
  path: String,
  mode: String,
): FileHandle? = runCatching { RandomAccessFileHandle(RandomAccessFile(path, mode)) }.getOrNull()

private class RandomAccessFileHandle(
  private val file: RandomAccessFile,
) : FileHandle {
  override val size: Long get() = file.length()

  override fun read(buf: ByteArray): Int = file.read(buf)

  override fun seek(position: Long) {
    file.seek(position)
  }

  override fun close() {
    file.close()
  }
}
