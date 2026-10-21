package soko.ekibun.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * seek 之后「丢帧收敛」的判据公式：帧落后主时钟、且差距在阈值内 ⇒ 立刻丢。见 `docs/development/playback-invariants.md` §1.4。
 */
class SeekWindowSemanticsTest {
  /** 复刻 ffplay `video_thread` 里的 `frame_drops_early`。 */
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

    /** `AV_NOSYNC_THRESHOLD`：10 秒，换算成微秒。 */
    const val AV_NOSYNC_THRESHOLD = 10.0 * 1_000_000
  }

  /** seek 后主时钟立刻设成目标时间：落点之前约 50 帧全丢；停在关键帧则一帧不丢。 */
  @Test
  fun clockMustBeTargetRightAfterSeekOtherwiseNothingIsDropped() {
    val keyframe = 40_000_000L
    val target = 42_000_000L

    // 时钟留在关键帧时间：一帧不丢
    var droppedWithStaleClock = 0
    var ts = keyframe
    while (ts < target) {
      if (shouldDropEarly(ts, masterClock = keyframe)) droppedWithStaleClock++
      ts += FRAME_INTERVAL
    }
    assertEquals(0, droppedWithStaleClock)

    // 时钟设为目标时间：约 50 帧全丢
    assertEquals(50, countDropped(keyframe, target))
  }

  @Test
  fun frameAtOrAfterTargetIsKept() {
    val target = 42_000_000L
    // 恰好命中目标：diff == 0，保留
    assertTrue(!shouldDropEarly(target, masterClock = target))
    // 略晚于目标：留着（同步逻辑会让它稍等）
    assertTrue(!shouldDropEarly(target + FRAME_INTERVAL, masterClock = target))
  }

  @Test
  fun farBehindFrameIsKeptBecauseSkippingWouldBeTooBig() {
    // 落后超过 10 秒：不丢，保留
    val target = 42_000_000L
    assertTrue(!shouldDropEarly(0L, masterClock = target))
  }
}
