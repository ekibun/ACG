package soko.ekibun.ffmpeg

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.loadLibrary
import kotlin.time.Duration.Companion.milliseconds

/** 一个解码器实例，绑定在 [stream] 上；每实例独占一条归属线程。 */
class AvCodec(
  private val stream: AvStream,
  /**
   * 视频解码优先走硬件（桌面 D3D11VA、Android MediaCodec）。这只是偏好：native 侧
   * 拿不到设备、或这个 codec 没有加速版本时自动回落软解，不报错。默认关 —— 直接构造
   * 解码器的地方（测试）要的是确定性，播放侧由 [FFPlayer] 显式打开。
   */
  private val hardware: Boolean = false,
  /**
   * 上屏目标；null = 没有。给了就走零拷贝：桌面在解码线程里把像素写进它的共享纹理，
   * Android 指一份 `Surface`，MediaCodec 直接渲染进去。见 [AvSurfaceContext.hardwareOutput]。
   *
   * 只在 open 时用一次：native 把它记在 codec 自己身上（桌面存 `AVCodecContext.opaque`）。
   * 帧的形态在 open 时就按它定死，所以之后收发帧都不传它；软解回落时 native 不记。
   */
  private val hardwareOutput: Any? = null,
) : Pointer(dispatcher = ThreadDispatcher("avcodec"), closeDispatcherOnClose = true) {
  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun initNative(
      stream: Long,
      hardware: Boolean,
      output: Any?,
    ): Long

    /** 送包：0 = 收下，1 = EAGAIN（输入缓冲满，先去收帧），<0 = 失败（native 已记日志）。 */
    @JvmStatic
    private external fun sendPacketNative(
      ctx: Long,
      stream: Long,
      packet: Long,
    ): Int

    /** 收帧：null = 解码器已经吐干净；空数组 = 现在没有。 */
    @JvmStatic
    private external fun receiveFramesNative(
      ctx: Long,
      stream: Long,
    ): Array<AvFrame>?

    @JvmStatic
    private external fun flushNative(ctx: Long)

    @JvmStatic
    private external fun closeNative(ctx: Long)
  }

  override fun initPtr(): Long = initNative(stream.ptr, hardware, hardwareOutput)

  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)

  /**
   * 送包顺序锁：同一个 codec 上，同一时刻只允许一段「送到被收下」在跑。
   *
   * 输入缓冲满时 `send` 返回 EAGAIN，要等收帧腾出地方再试。这段等待不能放手：多包并发时
   * 后一个包可能先被接受，解码器就按错的顺序解参考帧 —— 花屏直到下一个 IDR，而且不报错。
   */
  private val sendLock = Mutex()

  /**
   * 送入一个 packet 并收走这次能收的帧，直到这个包被收下才返回。
   *
   * [packet] 传 `null` 表示冲刷（片尾）：送一个空包让重排缓冲里的尾帧吐出来，一轮即收。
   * 返回 `null` = 解码器已吐干净（只有冲刷会这样），否则返回这一轮收到的帧（可能为空）。
   *
   * [isAlive] 返回 false（轮次已作废）就放弃等待。负责关 [packet]。
   * 两个 EAGAIN 的语义见 [sendPacketNative] / [receiveFramesNative]。
   */
  suspend fun sendPacketAndGetFrames(
    packet: AvPacket? = null,
    isAlive: () -> Boolean = { true },
  ): List<AvFrame>? =
    sendLock.withLock {
      val out = ArrayList<AvFrame>()
      try {
        // 整轮都留在归属线程上：中间不重取句柄，也不让别的作业插进来
        val drained =
          withPtr { ptr ->
            while (isAlive()) {
              val accepted = sendPacketNative(ptr, stream.ptr, packet?.ptr ?: 0L) != 1
              // null = 冲刷到底，前面收到的照常带走
              val list = receiveFramesNative(ptr, stream.ptr)?.toList() ?: return@withPtr true
              out += list
              if (accepted) break
              // 送不进去又收不出来（输出端满）：等送显吃掉几帧，再试同一个包
              delay(1.milliseconds)
            }
            false
          }
        if (drained) out.ifEmpty { null } else out
      } finally {
        packet?.close()
      }
    }

  suspend fun flush() =
    withPtr { ptr ->
      flushNative(ptr)
    }
}
