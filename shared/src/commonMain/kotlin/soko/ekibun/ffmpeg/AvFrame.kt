package soko.ekibun.ffmpeg

import soko.ekibun.jniLoadLibrary
import java.lang.ref.Cleaner

class AvFrame(
  val ptr: Long,
  val timeStamp: Long,
  val width: Int,
  val height: Int,
) {
  /** 标记该帧正在被哪个 PTS 播放轮次消费，避免同一帧被重复取用。 */
  var processing: FFPlayer.PTS? = null

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }

    external fun closeNative(ptr: Long)

    /**
     * 与 QuickJS.JSValue 一致：用 Cleaner 而不是 finalize()。
     * finalize() 在 JDK9+ 已废弃，且回收时机不可控；清理动作只捕获 ptr
     * 这一个值，绝不捕获 AvFrame 自身，否则对象永远无法被回收。
     */
    private val cleaner: Cleaner = Cleaner.create()
  }

  private class Releaser(
    @JvmField val ptr: Long,
  ) : Runnable {
    override fun run() {
      if (ptr != 0L) closeNative(ptr)
    }
  }

  private var closed = false
  private val cleanable: Cleaner.Cleanable = cleaner.register(this, Releaser(ptr))

  /** 显式释放底层 AVFrame。幂等。 */
  fun close() {
    if (closed) return
    closed = true
    cleanable.clean()
  }
}
