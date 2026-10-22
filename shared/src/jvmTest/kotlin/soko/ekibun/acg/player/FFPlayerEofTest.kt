package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.FFPlayer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/** 播到片尾后本轮自己收干净：播放轮次结束，`pause()` / `closeAsync()` 都能返回。 */
class FFPlayerEofTest {
  @Test(timeout = 120_000)
  fun playToEofThenPauseAndCloseReturn() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val sink = TestSurface()
    val frames: MutableList<Long?> = Collections.synchronizedList(ArrayList<Long?>())
    runBlocking {
      val player =
        FFPlayer(media.toString(), IOHandler(), sink) {
          if (it is FFPlayer.Event.Frame) frames.add(it.pts)
        }
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // 从片尾前 1 秒起播；duration 是 AV_TIME_BASE 微秒
        val start = maxOf(video.duration - 1_000_000, 0)
        // play() 要一直挂到整轮结束（withContext 会等自己的子协程），所以只 launch 不 await
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), start) }

        // ① 播到片尾：等 Frame 事件的 null PTS
        withTimeout(30_000) {
          while (!frames.contains(null)) delay(10)
        }
        // ② 本轮必须自己收干净
        withTimeout(20_000) { playJob.join() }
        // ③ 收干净之后 pause 要能返回
        withTimeout(5_000) { player.pause() }

        // null 之前必须真有帧显示过
        assertTrue(
          frames.any { it != null },
          "a frame should have been shown before the EOF teardown, got frames=${frames.take(5)}...",
        )
      } finally {
        // withTimeoutOrNull：本用例失败时不许拖住整个测试进程
        withTimeoutOrNull(10_000) { player.closeAsync() }
      }
    }
  }
}
