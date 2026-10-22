package soko.ekibun.acg.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import soko.ekibun.ffmpeg.AvIO

/**
 * 播放链上的 [AvIO] 基类：把挂起的 [runSuspend] 折成同步的 [read]，并登记本次读的作废投递口。
 *
 * [read] 是本层唯一的 `runBlocking`；折平与作废的约束见 `docs/development/shared-layer.md` 第一、二节。
 */
abstract class BaseIO(
  /** 叫醒当前这次读的投递口；null 表示这次读没有外部叫醒源。 */
  private val handler: IOHandler? = null,
) : AvIO {
  /** 一次读的挂起主体，返回值语义同 [AvIO.read]。 */
  protected abstract suspend fun runSuspend(
    buf: ByteArray,
    len: Int,
  ): Int

  /** [runSuspend] 抛错后的收场，默认无事可做。 */
  protected open fun onReadFailed() {
    // 没有需要丢弃的持有物
  }

  final override fun read(
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
          // 谎报读满：负数会让 demuxer 再读一轮，0 是原地死循环
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

  /** 「作废」信号：一次性事件，只在 [read] 内创建与消费。 */
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
}
