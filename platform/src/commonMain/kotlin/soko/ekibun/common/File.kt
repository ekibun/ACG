package soko.ekibun.common

/** 本地文件访问入口，平台差异由 [openFileHandle] 承接。 */
object File {
  /** 打开 [path] 处的文件，[mode] 同 `RandomAccessFile`；失败返回 null。 */
  fun open(
    path: String,
    mode: String,
  ): FileHandle? = openFileHandle(path, mode)
}

/** 本地文件的随机访问句柄。 */
interface FileHandle {
  /** 文件总字节数。 */
  val size: Long

  /** 从当前位置读入 `buf` 的前 `len` 个字节，返回读到的字节数；已到末尾返回 -1。 */
  fun read(
    buf: ByteArray,
    len: Int,
  ): Int

  /** 把读写位置移到绝对偏移 [position]。 */
  fun seek(position: Long)

  fun close()
}

/** 打开 [path] 处的文件，[mode] 同 `RandomAccessFile`；失败返回 null，不抛异常。 */
internal expect fun openFileHandle(
  path: String,
  mode: String,
): FileHandle?
