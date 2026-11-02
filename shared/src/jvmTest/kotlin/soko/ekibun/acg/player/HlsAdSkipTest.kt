package soko.ekibun.acg.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.FFPlayer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 素材 `hls-ad`：主片 0–24 s，插播 5.1 s 广告（自己的媒体时间从 1.48 s 重新起算），之后主片接着
 * 自己的媒体时间走到 25.5 s。广告在声明 12 s 处，回跳 11.9 s —— 够过 5 s 门槛，也够验
 * 「声明 32 s 之前的广告测不到」那个旧限制确实被压掉了。
 *
 * 判据在 `hls.c` 里（见 `docs/design/hls-ad-skip.md`）：标记片 + 上一包（同一条流）媒体时间回跳
 * 超过 5 s 就往后找「接得回基准」的标记片，找到才跳。广告分片的包因此不交给上层 ——
 * 提示「已跳过广告」靠的正是声明轴上的那个空档。
 */
class HlsAdSkipTest {
  @Test(timeout = 120_000)
  fun adSegmentsNeverReachTheCaller() =
    runBlocking {
      // 广告的声明区间：Σ EXTINF(主片 6×2.0) 到 +（1.7 × 3）
      val adFrom = 12_000_000L
      val adTo = 17_100_000L

      val pts = ArrayList<Long>()
      // 时间戳 → 标志位。跳过广告后接上的那一包必须还是关键帧，否则解码器没有参考帧。
      val packetFlags = HashMap<Long, Int>()
      val format = AvFormat(TestMedia.path("media/hls-ad/playlist.m3u8").toString(), IOHandler())
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        while (true) {
          val packet = format.getPacket(listOf(video)) ?: break
          if (packet.ptsUs >= 0) {
            pts.add(packet.ptsUs)
            packetFlags[packet.ptsUs] = packet.flags
          }
          packet.close()
        }
      } finally {
        format.resetChannel()
        format.closeDeferred().join()
      }

      assertTrue(pts.size > 200, "只收到 ${pts.size} 个视频包")

      // 包序是解码序（B 帧会让 pts 前后跳一帧），找空档要先排序
      val ordered = pts.sorted()
      val gaps = ordered.zipWithNext().filter { (a, b) -> b - a > 1_000_000 }
      assertEquals(1, gaps.size, "应当只有一个广告空档，实际 $gaps")
      val (lastBefore, firstAfter) = gaps.single()
      // 分片首包是音频，视频帧因此在声明轴上整体偏后一帧多一点（实测 +0.167 s），边界吃这个余量
      assertTrue(
        lastBefore in (adFrom - 1_000_000)..(adFrom + 500_000),
        "空档应当起于广告之前，实际 $lastBefore",
      )
      assertTrue(
        firstAfter in adTo..(adTo + 1_000_000),
        "空档应当止于广告结束之后，实际 $firstAfter",
      )

      // 分片边界会带出相邻段的余帧（±一帧），所以只查广告中段
      val leaked = ordered.filter { it > adFrom + 500_000 && it < adTo - 500_000 }
      assertTrue(leaked.isEmpty(), "广告分片的包不该交给上层，实际收到 ${leaked.size} 个: ${leaked.take(10)}")

      assertTrue(ordered.last() > 24_500_000, "广告之后正片必须播到底，最后一个是 ${ordered.last()}")

      // 跳段之后接上的第一包必须是关键帧。丢首帧时从第二包开始读，整片都没有参考帧（花屏）。
      val resumed = ordered.first { it >= adTo - 200_000 }
      assertTrue(
        (packetFlags[resumed] ?: 0) and AvFormat.AV_PKT_FLAG_KEY != 0,
        "跳过广告后接上的第一包应当是关键帧，实际 pts=$resumed flags=${packetFlags[resumed]}",
      )
    }

  /**
   * 纯视频素材没有音频帧推进时钟（`flushFrame` 只对音频返回时间戳）。跳过广告后
   * `frame.timeStamp` 比 `pts.now()` 大了整整一个广告时长，不抬时钟就会被门限挡住 5.1 s。
   *
   * 从 10 s 播到 18 s：真正要放的内容只有 2 s + 0.9 s；卡住的话会变成七秒多。
   */
  @Test(timeout = 120_000)
  fun skipDoesNotStallPlayback() =
    runBlocking {
      val reached = CompletableDeferred<Unit>()
      val events: MutableList<FFPlayer.Event> = Collections.synchronizedList(ArrayList())
      val player =
        FFPlayer(TestMedia.path("media/hls-ad/playlist.m3u8").toString(), IOHandler(), TestSurface()) { event ->
          events += event
          if (event is FFPlayer.Event.Frame && (event.pts ?: 0) >= 18_000_000 && !reached.isCompleted) {
            reached.complete(Unit)
          }
        }
      val startedAt = System.currentTimeMillis()
      try {
        val streams = player.getStreams().associateBy { it.codecType }
        val job = launch { player.play(streams, 10_000_000) }
        withTimeout(30_000) { reached.await() }
        val elapsed = System.currentTimeMillis() - startedAt
        player.pause()
        job.join()
        val skipped = events.filterIsInstance<FFPlayer.Event.AdSkipped>()
        assertTrue(
          skipped.isNotEmpty(),
          "10 s → 18 s 之间应当报过一次 AdSkipped，实际 ${events.filter { it !is FFPlayer.Event.Frame }}",
        )
        // 断点两侧：from 是跳过前最后一帧（回退的落点），to 是接上的第一帧，必须前后有序
        skipped.forEach {
          assertTrue(
            it.from in 11_000_000L..13_000_000L,
            "from 应当是广告前的位置，实际 ${it.from}",
          )
          assertTrue(
            it.to in 17_000_000L..18_000_000L,
            "to 应当是广告结束后的位置，实际 ${it.to}",
          )
        }
        assertTrue(elapsed < 6_000, "跳过广告不该停顿：10 s → 18 s 用了 ${elapsed}ms")
      } finally {
        player.closeAsync()
      }
    }

  /**
   * 回退跳过走 `reverseAd`：seek 之后那一轮本身是带 `backstep` 的步进轮，`skipAdBefore` 才成立、
   * 跳过才临时关掉，预读因此能把广告边界读过去；随后 `resume()` 从队头接着放。
   *
   * 判据三条：从广告首帧开始播（不是预读位置）、不再发 `AdSkipped`（否则提示会反复弹）、
   * 轮结束已把开关恢复（这一集后面的广告照跳）。
   */
  @Test(timeout = 120_000)
  fun revertingSeekPlaysTheSkippedAd() =
    runBlocking {
      val events: MutableList<FFPlayer.Event> = ArrayList()

      fun snapshot(): List<FFPlayer.Event> = synchronized(events) { events.toList() }
      val player =
        FFPlayer(TestMedia.path("media/hls-ad/playlist.m3u8").toString(), IOHandler(), TestSurface()) { event ->
          synchronized(events) { events += event }
        }
      try {
        val streams = player.getStreams().associateBy { it.codecType }
        val job = launch { player.play(streams, 10_000_000) }
        // 先让它跳过一次，并播到广告之后
        withTimeout(30_000) {
          while (
            snapshot().filterIsInstance<FFPlayer.Event.AdSkipped>().isEmpty() ||
            snapshot().filterIsInstance<FFPlayer.Event.Frame>().none { (it.pts ?: 0) >= 13_000_000 }
          ) {
            delay(20)
          }
        }
        val from = snapshot().filterIsInstance<FFPlayer.Event.AdSkipped>().first().from
        val mark = synchronized(events) { events.size }

        player.reverseAd(from)
        delay(4_000)
        player.pause()
        job.join()

        val after = snapshot().drop(mark)
        val frames = after.filterIsInstance<FFPlayer.Event.Frame>().mapNotNull { it.pts }
        val inAd = frames.filter { it in from..16_900_000 }
        assertTrue(inAd.size > 60, "回退之后应当播出广告段的帧，实际 ${inAd.size} 帧，前几帧=${frames.take(8)}")
        // 从广告首帧起播：不能落在预读停下的地方（那里可能已经在广告中段）
        val firstAdFrame = inAd.firstOrNull() ?: error("回退之后没有广告段的帧")
        assertTrue(
          firstAdFrame - from < 200_000,
          "回退应当从断点后一帧起播，实际首帧 $firstAdFrame，断点 $from",
        )
        assertEquals(
          0,
          after.count { it is FFPlayer.Event.AdSkipped },
          "回退之后不该再判一次广告，实际 ${after.filter { it !is FFPlayer.Event.Frame }}",
        )
        assertTrue(player.isSkipAd, "回退不改跳过开关，之后的广告还要继续跳")
      } finally {
        player.closeAsync()
      }
    }
}
