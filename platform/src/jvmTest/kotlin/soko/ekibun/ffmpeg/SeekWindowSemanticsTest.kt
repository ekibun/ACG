package soko.ekibun.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * seek 之后「丢帧收敛」那半的**判据公式**：解码出来的帧落后主时钟、且差距在阈值内 ⇒ 立刻丢。
 *
 * ## 这条公式为什么值得单独钉
 *
 * mp4/mov 的 demuxer 只实现旧的 `read_seek`（`mov_read_seek`），`read_seek2 == NULL` ⇒
 * `avformat_seek_file` 走回退分支：窗口**不传给** `av_seek_frame`，落点只能是 `<= ts` 的最近
 * 关键帧（细节与行号见 `docs/development/playback-invariants.md` §1.4）。所以「拖到 42 s
 * 却从 40 s 的关键帧开始播」只能靠第二步补：主时钟当场设成目标时间，关键帧到目标之间的帧
 * 解码后立即丢（ffplay 的 `frame_drops_early`）。
 *
 * 两步里**第二步的单位最容易写错**：`AV_NOSYNC_THRESHOLD` 是 10 秒，而本工程的帧时间戳是
 * **微秒** ⇒ 阈值必须是 `10 * 1_000_000`。写成 `10` 时「落后太多就别丢」这条保护会反过来
 * 把所有帧都判成「落后太多」，收敛直接失效，画面又退回从关键帧播起 —— 这个测试就是那次
 * 实测踩坑后留下的钉子。
 *
 * 端到端的验证在 `:shared` 的 `FFPlayerSeekFrameSequenceTest`（真 MP4、真帧序、真像素）；
 * 这里只钉公式本身，不假装能替代那边。
 */
class SeekWindowSemanticsTest {
  /**
   * 复刻 ffplay `video_thread` 里的 `frame_drops_early`（ffplay.c:1842-1854）：
   *
   * ```c
   * double diff = dpts - get_master_clock(is);
   * if (!isnan(diff) && fabs(diff) < AV_NOSYNC_THRESHOLD &&
   *     diff - is->frame_last_filter_delay < 0 && ...) { av_frame_unref(frame); ... }
   * ```
   */
  private fun shouldDropEarly(
    frameTs: Long,
    masterClock: Long,
  ): Boolean {
    val diff = frameTs - masterClock
    return kotlin.math.abs(diff) < AV_NOSYNC_THRESHOLD && diff < 0
  }

  /** 从 [keyframe] 起、按 25fps 走到 [target] 前，数一数会被丢掉的帧。 */
  private fun countDropped(
    keyframe: Long,
    target: Long,
  ): Int {
    var dropped = 0
    var ts = keyframe
    while (ts < target) {
      if (shouldDropEarly(ts, masterClock = target)) dropped++
      ts += FRAME_INTERVAL
    }
    return dropped
  }

  private companion object {
    const val FRAME_INTERVAL = 40_000L

    /**
     * `AV_NOSYNC_THRESHOLD` = 10 秒，但**单位必须和 PTS 一致**（微秒）。
     * 漏掉这个量级会把所有帧都判成「落后太多、不能丢」，丢帧收敛直接失效。
     */
    const val AV_NOSYNC_THRESHOLD = 10.0 * 1_000_000
  }

  /**
   * 回归：seek 后主时钟**立刻**设成目标时间的那个决定。
   *
   * 若还是「等第一帧解码出来才 update 时钟」，主时钟就停在关键帧时间，`diff` 恒为 0，一帧都
   * 不会被丢 —— 画面又会老老实实从关键帧播起。两种做法的差别正好是「丢 0 帧」与「丢 50 帧」。
   */
  @Test
  fun clockMustBeTargetRightAfterSeekOtherwiseNothingIsDropped() {
    val keyframe = 40_000_000L
    val target = 42_000_000L

    // 错误做法：时钟留在关键帧时间 -> 关键帧与主时钟同步 -> 一帧不丢
    var droppedWithStaleClock = 0
    var ts = keyframe
    while (ts < target) {
      if (shouldDropEarly(ts, masterClock = keyframe)) droppedWithStaleClock++
      ts += FRAME_INTERVAL
    }
    assertEquals(0, droppedWithStaleClock)

    // 正确做法：时钟设为目标时间 -> 关键帧到目标之间约 50 帧全丢
    assertEquals(50, countDropped(keyframe, target))
  }

  @Test
  fun frameAtOrAfterTargetIsKept() {
    val target = 42_000_000L
    // 恰好命中目标：diff == 0，不满足 `diff < 0`，保留
    assertTrue(!shouldDropEarly(target, masterClock = target))
    // 略晚于目标：留着（同步逻辑会让它稍等）
    assertTrue(!shouldDropEarly(target + FRAME_INTERVAL, masterClock = target))
  }

  @Test
  fun farBehindFrameIsKeptBecauseSkippingWouldBeTooBig() {
    // fabs(diff) < AV_NOSYNC_THRESHOLD(10s) 不成立时 ffplay 不丢帧，
    // 避免时间戳异常（如流开头损坏）导致整段被吞掉。
    val target = 42_000_000L
    assertTrue(!shouldDropEarly(0L, masterClock = target))
  }
}
