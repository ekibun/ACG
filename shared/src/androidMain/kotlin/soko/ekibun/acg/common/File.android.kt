package soko.ekibun.acg.common

import java.io.RandomAccessFile

actual fun openFileHandle(path: String): FileHandle? =
  try {
    RandomAccessFileHandle(RandomAccessFile(path, "r"))
  } catch (e: Throwable) {
    // 路径不存在 / 没有权限 / 落在 scoped storage 之外：按契约返回 null，让调用方
    // 看到"打不开"而不是异常。
    e.printStackTrace()
    null
  }

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
