package soko.ekibun

import soko.ekibun.ffmpeg.AvIO
import java.util.concurrent.atomic.AtomicInteger

/**
 * 测试用的「一次**可能永久阻塞**的 IO 调用」包装。**测试不许被它吊死** —— 一次没回来的读会让整个
 * 构建挂住（本项目踩过两回，都是靠手动杀进程收场）。所以丢到**守护线程**上跑，且 `join` 带超时：
 * 超时就把那个线程的栈打出来再失败，站在报告里能直接看出它卡在哪。
 */
internal object TestIo {
  fun readBounded(
    io: AvIO,
    size: Int,
    timeoutMs: Long = 10_000,
  ): Int {
    val result = AtomicInteger(Int.MIN_VALUE)
    val reader =
      Thread { result.set(io.read(ByteArray(size))) }.apply {
        isDaemon = true
        start()
      }
    reader.join(timeoutMs)
    if (reader.isAlive) {
      println("[diag] read($size) 在 ${timeoutMs}ms 内没返回，栈：\n" + reader.stackTrace.joinToString("\n"))
      throw AssertionError("read($size) 在 ${timeoutMs}ms 内没有返回（见 stdout 的栈）")
    }
    return result.get()
  }
}
