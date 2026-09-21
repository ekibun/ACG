package soko.ekibun.acg.common

/**
 * 本地文件访问入口，与 [Http] 对称：调用方只用这里的方法，平台差异交给
 * [openFileHandle] 这个 expect 原语。
 *
 * 交出的是**随机访问句柄**而不是"读一段字节"的函数 —— 播放器要在同一次打开里
 * 反复 read/seek，每次调用都重开文件做不到。
 */
object File {
  /** 打开 [path] 处的文件；失败（不存在、没有权限）返回 null。 */
  fun open(path: String): FileHandle? = openFileHandle(path)
}

/**
 * 本地文件的随机访问句柄。
 *
 * `commonMain` 不许出现平台符号（根 AGENTS.md §4），文件能力只能这样 expect 出来；
 * 两端 actual 都只是包一层 `RandomAccessFile`。
 */
interface FileHandle {
  /** 文件总字节数。 */
  val size: Long

  /** 从当前位置读入 `buf`，返回读到的字节数；**已到末尾返回 -1**（`RandomAccessFile` 的约定）。 */
  fun read(buf: ByteArray): Int

  /** 把读写位置移到绝对偏移 `position`。 */
  fun seek(position: Long)

  fun close()
}

/**
 * 打开 [path] 处的文件；失败（不存在、没有权限）返回 null。
 *
 * 不抛异常是刻意的：本函数由 native 的 `io_open` 回调间接调起，异常穿过 JNI 边界后
 * native 仍会继续用返回值，症状是更难查的崩溃。失败统一表现为读取方返回 -1，
 * 最终由 `avformat_open_input` 报"打不开"。
 */
expect fun openFileHandle(path: String): FileHandle?
