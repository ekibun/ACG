package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.FFPlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 播到**片尾**之后，本轮要自己收干净：`pause()` / `closeAsync()` 都必须能返回。
 *
 * 为什么值得留（2026-10-01 实测踩过）：AvFormat 的预读通道在 EOF 只 `send(null)`、不 `close()`，
 * 而读循环拿到 null 且队列非空时会 `delay(100ms); continue` —— **第二次 `getPacket` 永久挂起**，
 * 于是本轮永不结束、`pause()` 的 `playingJob.join()` 一直等。症状与「暂停收不掉」一模一样，
 * 而当时全部用例都撞不到（都只播到第一帧就暂停）。
 *
 * 素材是仓库根下的 `.workbuddy/test.mp4`（那个目录整体 gitignore），找不到就跳过 ——
 * 与 [FFPlayerStepTest] 同一种做法。位置只能靠反射读 `lastFrameTs`（不为测试在生产代码开钩子）。
 */
class FFPlayerEofTest {
  private fun FFPlayer.lastFrameTs(): Long? {
    val field = FFPlayer::class.java.getDeclaredField("lastFrameTs")
    field.isAccessible = true
    return field.get(this) as Long?
  }

  /** 从测试的工作目录往上找仓库根下的素材；找不到就返回 null（用例跳过）。 */
  private fun findVideo(): Path? {
    var dir: Path? = Path.of("").toAbsolutePath()
    while (dir != null) {
      val candidate = dir.resolve(".workbuddy/test.mp4")
      if (Files.exists(candidate)) return candidate
      dir = dir.parent
    }
    return null
  }

  @Test
  fun playToEofThenPauseAndCloseReturn() {
    val url = findVideo()
    if (url == null) {
      println("跳过：找不到 .workbuddy/test.mp4（素材在 gitignore 里，不随仓库分发）")
      return
    }
    runBlocking {
      val player = FFPlayer(url.toString(), FileIO.Handler(), null)
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // 从片尾前 3 秒起播。`duration` 是 **AV_TIME_BASE 微秒**
        // （native 侧是 `stream->duration * av_q2d(time_base) * AV_TIME_BASE`，见 cxx/ffmpeg/ffmpeg.cpp），
        // 与 play(seek) / resumeImpl 里那些时间戳同单位。
        val start = maxOf(video.duration - 3_000_000, 0)
        // play() 要一直挂到整个播放轮次结束（withContext 会等自己的子协程，而轮次就是那个子协程），
        // 所以不能 await —— UI 里也是 fire-and-forget 地 launch。
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), start) }

        withTimeout(10_000) { while (player.lastFrameTs() == null) delay(5) }
        assertTrue(
          player.lastFrameTs()!! >= start,
          "应从 seek 目标之后开始：lastFrameTs=${player.lastFrameTs()} start=$start",
        )

        // ① 本轮必须**自己**在片尾收干净（EOF 那条路挂了的话，这里超时）
        withTimeout(20_000) { playJob.join() }

        // ② 收干净之后，pause / close 都要能返回
        withTimeout(5_000) { player.pause() }
      } finally {
        // withTimeoutOrNull：本用例失败时（例如又退回挂死那版）不许把整个测试进程拖住
        withTimeoutOrNull(10_000) { player.closeAsync() }
      }
    }
  }
}
