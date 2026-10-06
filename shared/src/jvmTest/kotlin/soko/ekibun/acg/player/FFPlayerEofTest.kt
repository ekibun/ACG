package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvPlayback
import soko.ekibun.ffmpeg.FFPlayer
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 播到**片尾**之后，本轮要自己收干净：播放轮次要结束、`pause()` / `closeAsync()` 都必须能返回。
 *
 * 为什么值得留（2026-10-01 实测踩过）：AvFormat 的预读通道在 EOF 只 `send(null)`、不 `close()`，
 * 而读循环拿到 null 且队列非空时会 `delay(100ms); continue` —— **第二次 `getPacket` 永久挂起**，
 * 于是本轮永不结束、`pause()` 的 `playingJob.join()` 一直等。症状与「暂停收不掉」一模一样，
 * 其他用例都只播到第一帧就暂停，撞不到它。
 *
 * 观测点是 [AvPlayback.onFrame]：`FFPlayer` 播完会以 `null` 回调（见 `FFPlayer.resumeImpl`）——
 * 这既是「真的播到片尾了」的公开信号，也是本用例的计时起点。从片尾前 3s 起播（不是从头），
 * 让用例几秒就能跑到 END，而不是干等整段素材。
 *
 * 素材用随源码入库的 [TestMedia.BBB_640X360_12S_FASTSTART]（12s / 300 帧），换台机器 / CI 也真跑。
 */
class FFPlayerEofTest {
  /**
   * 空播放设备：只收集 [AvPlayback.onFrame] 的时间戳（含末尾的 `null`），不送显不送声。
   *
   * `frames` 当**构造参数**传：super 实参位置上的 lambda 只能引用构造参数 —— 引用类体属性
   * 捕获的是还没初始化的 `this`（`Pointer` KDoc 记过的坑）。播放线程写、测试协程并发读 ⇒ 同步列表。
   */
  private class Sink(
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>()),
  ) : AvPlayback({ v -> frames.add(v) }, { }) {
    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    override suspend fun flushAudioBuffer(buf: ByteBuffer): Int = 0

    override fun flushVideoBuffer(
      buf: ByteBuffer,
      width: Int,
      height: Int,
    ) = Unit

    override suspend fun resume() = Unit

    override suspend fun pause() = Unit

    override suspend fun stop() = Unit
  }

  @Test(timeout = 120_000)
  fun playToEofThenPauseAndCloseReturn() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val sink = Sink()
    runBlocking {
      val player = FFPlayer(media.toString(), FileIO.Handler(), sink)
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // 从片尾前 3 秒起播。`duration` 是 **AV_TIME_BASE 微秒**
        // （native 侧是 `stream->duration * av_q2d(time_base) * AV_TIME_BASE`，见 cxx/ffmpeg/ffmpeg.cpp），
        // 与 play(seek) / resumeImpl 里那些时间戳同单位。
        val start = maxOf(video.duration - 3_000_000, 0)
        // play() 要一直挂到整个播放轮次结束（withContext 会等自己的子协程，而轮次就是那个子协程），
        // 所以不能 await —— UI 里也是 fire-and-forget 地 launch。
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), start) }

        // ① 一路播到片尾：等 onFrame 的 null 回调（= 真播完了）。超时说明本轮走到 END 时卡住了。
        withTimeout(30_000) {
          while (!sink.frames.contains(null)) delay(10)
        }
        // ② 本轮必须**自己**收干净（EOF 那条路挂了的话，这里超时）
        withTimeout(20_000) { playJob.join() }
        // ③ 收干净之后，pause 要能返回
        withTimeout(5_000) { player.pause() }

        // null 之前必须真有帧显示过（否则「null」可能来自开播即失败的假路径）。
        assertTrue(
          sink.frames.any { it != null },
          "片尾收场前应当显示过帧，实际 frames=${sink.frames.take(5)}…",
        )
      } finally {
        // withTimeoutOrNull：本用例失败时（例如又退回挂死那版）不许把整个测试进程拖住
        withTimeoutOrNull(10_000) { player.closeAsync() }
      }
    }
  }
}
