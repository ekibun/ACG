package soko.ekibun.acg.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.FFPlayer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 连点 seek：第二次在一次 seek 的轮子还没起来时到达。
 *
 * 「本来在播吗」读的是新 `PTS` 的 `playing`，而它在那时必须还是 `false` —— 于是第二次把自己判成
 * 「暂停态 seek」，只送一帧就收轮，画面冻在落点上（症状：seek 完视频不动了）。判据取
 * `FFPlayer.intentPlaying` 之后，两路 seek 都按「接着放」处理。
 */
class FFPlayerConcurrentSeekTest {
  /** seek 之后 [windowMs] 内收到的帧时间戳。 */
  private suspend fun framesAfterSeek(
    scope: CoroutineScope,
    player: FFPlayer,
    events: MutableList<FFPlayer.Event>,
    target: Long,
    secondGapMs: Long,
    windowMs: Long = 2_500,
  ): List<Long> {
    val mark = synchronized(events) { events.size }
    // UI 的写法：不等它返回（`seekTo` 要等自己那一轮结束）
    scope.launch { player.seekTo(target) }
    if (secondGapMs > 0) {
      delay(secondGapMs)
      scope.launch { player.seekTo(target + 1_000_000) }
    }
    delay(windowMs)
    return synchronized(events) {
      events.drop(mark).filterIsInstance<FFPlayer.Event.Frame>().mapNotNull { it.pts }
    }
  }

  @Test(timeout = 180_000)
  fun backToBackSeeksKeepPlaying() =
    runBlocking {
      val events: MutableList<FFPlayer.Event> = ArrayList()
      val player =
        FFPlayer(TestMedia.path("media/hls-ad/playlist.m3u8").toString(), IOHandler(), TestSurface()) { event ->
          synchronized(events) { events += event }
        }
      try {
        val streams = player.getStreams().associateBy { it.codecType }
        val playJob = launch { player.play(streams, 0) }
        delay(1_500)

        // 素材 25 fps：2.5 s 该有 60 帧上下，落到「一帧就停」时只有 1~2 帧
        listOf(0L to 9_000_000L, 5L to 22_000_000L, 60L to 12_000_000L).forEach { (gap, target) ->
          val frames = framesAfterSeek(this, player, events, target, gap)
          assertTrue(
            frames.size > 40,
            "连点 seek（间隔 ${gap}ms）之后应当继续出帧，实际 ${frames.size} 帧：${frames.take(6)}",
          )
        }

        player.pause()
        playJob.join()
      } finally {
        player.closeAsync()
      }
    }
}
