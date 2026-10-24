package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.FFPlayer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 素材 `hls-ad` 在分片边界藏了两次时间轴断裂：主片 0–12 s、广告 12–17.1 s（自己的 PTS 从 1.4 s
 * 重新起算）、主片接着 17.1 s 往下。demuxer 已把包平移到声明轴（见 `hls.sed` 第 4 条与
 * `playback-invariants` 1.7），所以送显时间戳必须跨过这两处继续连续。
 *
 * 改写失效时的症状是 5 秒的空洞：广告帧退回 1.4 s，被 `resyncTo` 当成「还没收敛」整段丢掉。
 */
class HlsAdTimelineTest {
  @Test(timeout = 120_000)
  fun framesStayContinuousAcrossAdBreaks() {
    // 广告起点（Σ EXTINF 主片 6×2.0）与广告结束（+ 3×1.7）
    for (seam in listOf(12_000_000L, 17_100_000L)) {
      val frames = window(seam - 600_000, seam + 700_000)
      assertTrue(frames.size > 10, "seam=$seam 只收到 ${frames.size} 帧: $frames")
      assertTrue(
        frames.any { it < seam } && frames.any { it > seam },
        "seam=$seam 两侧都要有帧，实际 $frames",
      )
      assertTrue(frames.sorted() == frames, "seam=$seam 时间戳必须单调: $frames")
      val step = frames.zipWithNext { a, b -> b - a }.max()
      assertTrue(step < 200_000, "seam=$seam 出现 ${step}us 的空洞: $frames")
    }
  }

  /** 从 [from] 起播，收帧到越过 [until] 为止。 */
  private fun window(
    from: Long,
    until: Long,
  ): List<Long> =
    runBlocking {
      val frames: MutableList<Long> = Collections.synchronizedList(ArrayList<Long>())
      val player =
        FFPlayer(TestMedia.path("media/hls-ad/playlist.m3u8").toString(), IOHandler(), TestSurface()) {
          if (it is FFPlayer.Event.Frame) it.pts?.let(frames::add)
        }
      try {
        val streams = player.getStreams().associateBy { it.codecType }
        val job = launch { player.play(streams, from) }
        withTimeout(30_000) {
          while (frames.toList().none { it >= until }) delay(10)
        }
        player.pause()
        job.join()
        frames.toList()
      } finally {
        player.closeAsync()
      }
    }
}
