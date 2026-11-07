package soko.ekibun.ffmpeg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * `io_open` 交给 native 的句柄：读的挂起主体写 [runSuspend]，同步的 [read] 由本接口折平。
 *
 * 折平与作废的约束见 `docs/development/shared-layer.md` 第一、二节。
 */
interface AvIO {
  companion object {
    // POSIX lseek 的 whence 取值。
    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2
  }

  /** `io_open` 的入口，也是作废一轮播放时叫醒当前读的投递口。 */
  abstract class Handler {
    /** 当前在等数据的那次读的投递口，由 [read] 挂上 / 摘掉。 */
    @Volatile private var raise: (() -> Unit)? = null

    /** 打开一个源；打不开时返回 read/seek 恒失败的 [AvIO]，不抛异常。 */
    abstract fun open(url: String): AvIO

    /** 叫醒正在等数据的那次读（一次性事件）；没读在等时是 no-op。 */
    fun abort() {
      raise?.invoke()
    }

    /** 登记当前读的叫醒手段；null 表示摘掉投递口。 */
    internal fun onSignal(block: (() -> Unit)?) {
      raise = block
    }
  }

  /** 建出本 IO 的 [Handler]；[read] 通过它登记叫醒手段，null 表示这次读等不到外部叫醒。 */
  val handler: Handler? get() = null

  /** 一次读的挂起主体，返回值语义同 [read]。 */
  suspend fun runSuspend(
    buf: ByteArray,
    len: Int,
  ): Int

  /** [runSuspend] 抛错后的收场，默认无事可做。 */
  fun onReadFailed() {
    // 没有需要丢弃的持有物
  }

  /**
   * 往 [buf] 的前 [len] 个字节里读，返回实际读到的字节数（`AVERROR_EOF` / 负数 = 错误）。
   *
   * 被 [Handler.abort] 叫醒时报读满：负数会让 demuxer 再读一轮，0 是原地死循环。
   */
  fun read(
    buf: ByteArray,
    len: Int,
  ): Int =
    try {
      runBlocking {
        val signal = AbortSignal()
        val job = async { runSuspend(buf, len) }
        // 只能 cancel 不能 throw：抛出会成为 scope 的未捕获异常，整次 read 直接拆掉
        val abort =
          async {
            signal.await()
            job.cancel()
          }
        handler?.onSignal { signal.raise() }
        try {
          job.await()
        } catch (e: CancellationException) {
          len
        } catch (e: Throwable) {
          e.printStackTrace()
          onReadFailed()
          -1
        } finally {
          // 两句都不能省：哨兵不取消则 runBlocking 永远等它，投递口不摘则下一条读被误伤
          abort.cancel()
          handler?.onSignal(null)
        }
      }
    } catch (e: Throwable) {
      e.printStackTrace()
      -1
    }

  fun seek(
    offset: Int,
    whence: Int,
  ): Int

  fun close()
}

/** 「作废」信号：一次性事件，只在 [AvIO.read] 内创建与消费。 */
private class AbortSignal {
  private val raised = CompletableDeferred<Unit>()

  /** 任意线程可投，重复投等价一次。 */
  fun raise() {
    raised.complete(Unit)
  }

  suspend fun await() {
    raised.await()
  }
}
