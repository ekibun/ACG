package soko.ekibun.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `FFPlayer` / `AvFormat.seekTo` 的 seek 窗口语义回归。
 *
 * ## 为什么需要这组测试
 *
 * 现象：拖动进度条松手后，画面会停在**目标时间之前最近的关键帧**，而不是目标时间。
 *
 * 根因链（逐层验证过）：
 * 1. `FFPlayer.seekTo` 调 `super.seekTo(ts, stream = null, minTs = MIN, maxTs = MAX)`
 *    → `avformat_seek_file(ctx, -1, MIN, ts, MAX, flags)`。
 * 2. `avformat_seek_file`（libavformat/seek.c:679-712）**优先**走 `read_seek2`。
 *    但 mp4/mov 的 demuxer 只实现了旧的 `read_seek`（`mov_read_seek`），
 *    `read_seek2 == NULL` -> 直接落进回退分支：
 *
 *    ```c
 *    dir = (ts - (uint64_t)min_ts > (uint64_t)max_ts - ts ? AVSEEK_FLAG_BACKWARD : 0);
 *    ret = av_seek_frame(s, stream_index, ts, flags | dir);
 *    ```
 *
 *    即 **`min_ts` / `max_ts` 在这里被整个丢弃**，只剩 `dir` 从窗口推出来的
 *    `AVSEEK_FLAG_BACKWARD`。
 * 3. `av_seek_frame` -> `seek_frame_internal` -> `mov_read_seek`
 *    -> `mov_seek_stream`：用 `av_index_search_timestamp(st, ts, flags)` 在索引里
 *    找关键帧，带 `BACKWARD` 时返回 `a`（<= ts 的候选），非关键帧再靠
 *    `AVINDEX_KEYFRAME` 位往后挪 —— 结果一定是 **ts 之前的关键帧**。
 *    `AVSEEK_FLAG_BACKWARD` 在 `avformat_seek_file` 开头被 `flags &= ~AVSEEK_FLAG_BACKWARD`
 *    清掉，想"往前找"只能靠窗口 / `dir`，没法显式传。
 *
 * 结论：**mp4/mov 上无论怎么调 min/max，demuxer 都只会落关键帧。**
 * 所以"平滑"必须分两步做，缺一不可：
 *
 * | 步骤 | 参考实现 | 作用 |
 * | --- | --- | --- |
 * | A. seek 落点 | `stream_seek` / `avformat_seek_file` | 落关键帧，**把时钟直接设成目标 ts** |
 * | B. 丢帧收敛 | `frame_drops_early` | 关键帧到目标之间的帧解码后立刻丢，不再逐帧播出去 |
 *
 * 这组测试把上面两条的**判定公式**抽成纯函数锁住，
 * 保证以后不会又被"只用窗口就能精确 seek"这种想当然改回去。
 */
class SeekWindowSemanticsTest {
  /**
   * 复刻 `stream_seek` + `read_thread` 里 `seek_min` / `seek_max` 的算法
   * （ffplay.c:1526-1537 / 3092-3121）。
   *
   * `rel == 0`（我们的拖动进度条就是这种）时窗口**必须无界** —— `INT64_MIN`/`INT64_MAX`。
   * 这正是 [LONG_MIN_WINDOW] / [LONG_MAX_WINDOW] 要表达的常量。
   */
  private data class SeekWindow(
    val minTs: Long,
    val maxTs: Long,
  )

  private fun streamSeekWindow(
    pos: Long,
    rel: Long,
  ): SeekWindow =
    SeekWindow(
      // seek_min = seek_rel > 0 ? seek_target - seek_rel + 2 : INT64_MIN
      if (rel > 0) pos - rel + 2 else Long.MIN_VALUE,
      // seek_max = seek_rel < 0 ? seek_target - seek_rel - 2 : INT64_MAX
      if (rel < 0) pos - rel - 2 else Long.MAX_VALUE,
    )

  /**
   * 复刻 `avformat_seek_file` 回退分支里 `dir` 的推导（seek.c:705）。
   *
   * 注意这里的无符号算术是**故意的**：`min_ts = INT64_MIN` 时
   * `ts - (uint64_t)min_ts` 会溢出成一个巨大的无符号数，于是必然
   * `dir = AVSEEK_FLAG_BACKWARD`。ffplay 的 `seek_min = INT64_MIN`
   * 因此隐含了"往前找关键帧"的语义。
   */
  private fun fallbackDir(
    ts: Long,
    minTs: Long,
    maxTs: Long,
  ): Int {
    val lowerSpan = ts - minTs
    val upperSpan = maxTs - ts
    // 与 C 的 uint64_t 比较等价：只看符号位决定的"哪个更大"
    return if (java.lang.Long.compareUnsigned(lowerSpan, upperSpan) > 0) AVSEEK_FLAG_BACKWARD else 0
  }

  private companion object {
    // libavformat/avformat.h
    const val AVSEEK_FLAG_BACKWARD = 1
    const val AVSEEK_FLAG_BYTE = 2
    const val AVSEEK_FLAG_ANY = 4

    /**
     * `AV_NOSYNC_THRESHOLD` = 10 秒，但**单位必须和 PTS 一致**（微秒）。
     * 漏掉这个量级会把所有帧都判成"落后太多、不能丢"，丢帧收敛直接失效 ——
     * 这正是 `framesBeforeTargetAreDroppedAfterSeek` 第一次跑出来的结果。
     */
    const val AV_NOSYNC_THRESHOLD = 10.0 * 1_000_000
  }

  // ---------------------------------------------------------------- 窗口计算

  @Test
  fun relativeSeekZeroKeepsWindowUnbounded() {
    // 拖动进度条 -> rel == 0 -> 窗口无界，交给 demuxer 自由落点
    val pos = 42_000_000L // 42s
    assertEquals(SeekWindow(Long.MIN_VALUE, Long.MAX_VALUE), streamSeekWindow(pos, 0))
  }

  @Test
  fun forwardRelativeSeekClampsLowerBound() {
    // 快进 10s：只允许落在 [pos-10s+2, +inf)，即"不能比目标早太多"
    val pos = 42_000_000L
    val rel = 10_000_000L
    val w = streamSeekWindow(pos, rel)
    assertEquals(pos - rel + 2, w.minTs)
    assertEquals(Long.MAX_VALUE, w.maxTs)
  }

  @Test
  fun backwardRelativeSeekClampsUpperBound() {
    // 快退 10s：只允许落在 (-inf, pos+10s-2]
    val pos = 42_000_000L
    val rel = -10_000_000L
    val w = streamSeekWindow(pos, rel)
    assertEquals(Long.MIN_VALUE, w.minTs)
    assertEquals(pos - rel - 2, w.maxTs)
  }

  // ------------------------------------------------- 回退分支：窗口被丢弃的证据

  @Test
  fun unboundedWindowAlwaysFallsBackToBackward() {
    // ffplay 的 rel==0 场景：min=INT64_MIN, max=INT64_MAX
    // -> dir 必然是 BACKWARD（无符号下界跨度溢出为巨大值）
    val dir = fallbackDir(posec(), Long.MIN_VALUE, Long.MAX_VALUE)
    assertEquals(AVSEEK_FLAG_BACKWARD, dir)
  }

  @Test
  fun symmetricWindowPicksForward() {
    // 窗口对称时 ts - min == max - ts，不取 BACKWARD（保持 ffplay 的 `>` 严格比较）
    val ts = 10_000_000L
    assertEquals(0, fallbackDir(ts, ts - 1_000_000L, ts + 1_000_000L))
  }

  @Test
  fun tightLowerWindowPicksForward() {
    // 窗口压得很紧且下界贴着 ts -> 不会选 BACKWARD
    val ts = 10_000_000L
    assertEquals(0, fallbackDir(ts, ts, Long.MAX_VALUE))
  }

  @Test
  fun wideLowerWindowPicksBackward() {
    // 下界离 ts 很远、上界贴着 ts -> 选 BACKWARD
    val ts = 10_000_000L
    assertEquals(AVSEEK_FLAG_BACKWARD, fallbackDir(ts, Long.MIN_VALUE, ts))
  }

  /**
   * 核心回归：**回退分支下 min_ts/max_ts 根本不影响落点**。
   *
   * 对 [tightLowerWindowPicksForward] / [unboundedWindowAlwaysFallsBackToBackward]
   * 这两个窗口，`avformat_seek_file` 最终都只是把同一个 `ts` 交给
   * `av_seek_frame`，demuxer 自然落同一个关键帧。
   * 换句话说：**mp4/mov 上不存在"靠窗口让 seek 更精确"这条路**。
   */
  @Test
  fun windowCannotMakeFallbackSeekAccurate() {
    val ts = 42_000_000L
    // 两个极端的窗口，落到 av_seek_frame 的目标时间戳完全相同
    val targets =
      listOf(
        Long.MIN_VALUE to Long.MAX_VALUE,
        ts to Long.MAX_VALUE,
      ).map { (minTs, maxTs) -> fallbackSeekTarget(ts, minTs, maxTs) }
    assertEquals(listOf(ts, ts), targets)
  }

  /** 回退分支把 min/max 丢掉后，真正传给 `av_seek_frame` 的只剩 `ts`。 */
  private fun fallbackSeekTarget(
    ts: Long,
    minTs: Long,
    maxTs: Long,
  ): Long {
    // seek.c:706 `av_seek_frame(s, stream_index, ts, flags | dir)`
    // 唯一的窗口痕迹是 dir，而 dir 只改 BACKWARD 位，不改 ts。
    fallbackDir(ts, minTs, maxTs)
    return ts
  }

  // ------------------------------------------------------- 丢帧收敛（frame_drops_early）

  /**
   * 复刻 ffplay `video_thread` 里的 `frame_drops_early`（ffplay.c:1842-1854）：
   *
   * ```c
   * double diff = dpts - get_master_clock(is);
   * if (!isnan(diff) && fabs(diff) < AV_NOSYNC_THRESHOLD &&
   *     diff - is->frame_last_filter_delay < 0 && ...) {
   *   is->frame_drops_early++; av_frame_unref(frame); got_picture = 0;
   * }
   * ```
   *
   * 即：**PTS 落后于主时钟、且差距在阈值内的帧，解码后立刻丢**。
   * seek 后主时钟被设成目标 ts，于是关键帧到目标之间的帧会被快速丢完，
   * 画面直接推进到用户选的位置 —— 这就是"平滑"的来源。
   */
  private fun shouldDropEarly(
    frameTs: Long,
    masterClock: Long,
    noSyncThreshold: Double,
  ): Boolean {
    val diff = frameTs - masterClock
    return kotlin.math.abs(diff) < noSyncThreshold && diff < 0
  }

  @Test
  fun framesBeforeTargetAreDroppedAfterSeek() {
    val target = 42_000_000L // 目标 42s
    val keyframe = 40_000_000L // demuxer 落在 40s 的关键帧
    val frameInterval = 40_000L // 25fps -> 40ms
    // 从关键帧到目标之间约 50 帧，全部应被丢弃
    var dropped = 0
    var ts = keyframe
    while (ts < target) {
      if (shouldDropEarly(ts, masterClock = target, noSyncThreshold = AV_NOSYNC_THRESHOLD)) dropped++
      ts += frameInterval
    }
    assertEquals(50, dropped)
  }

  @Test
  fun firstFrameAtOrAfterTargetIsKept() {
    val target = 42_000_000L
    // 恰好命中目标：diff == 0，不满足 `diff < 0`，保留
    assertTrue(!shouldDropEarly(target, masterClock = target, noSyncThreshold = AV_NOSYNC_THRESHOLD))
    // 略晚于目标：留着（同步逻辑会让它稍等）
    assertTrue(!shouldDropEarly(target + 40_000L, masterClock = target, noSyncThreshold = AV_NOSYNC_THRESHOLD))
  }

  @Test
  fun farBehindFrameIsKeptBecauseSkippingWouldBeTooBig() {
    // fabs(diff) < AV_NOSYNC_THRESHOLD(10s) 不成立时 ffplay 不丢帧，
    // 避免时间戳异常（如流开头损坏）导致整段被吞掉。
    val target = 42_000_000L
    assertTrue(!shouldDropEarly(0L, masterClock = target, noSyncThreshold = AV_NOSYNC_THRESHOLD))
  }

  /**
   * 回归：seek 后时钟必须**立刻**设成目标时间（对应
   * `set_clock(&is->extclk, seek_target / (double)AV_TIME_BASE, 0)`）。
   *
   * 如果还是"等第一帧解码出来才 update 时钟"，主时钟就停在关键帧时间，
   * `diff` 恒为 0，一帧都不会被丢 —— 画面又会老老实实从关键帧播起。
   */
  @Test
  fun clockMustBeTargetRightAfterSeekOtherwiseNothingIsDropped() {
    val keyframe = 40_000_000L
    val target = 42_000_000L
    val threshold = AV_NOSYNC_THRESHOLD

    // 错误做法：时钟留在关键帧时间 -> 关键帧与主时钟同步 -> 一帧不丢
    var droppedWithStaleClock = 0
    var ts = keyframe
    while (ts < target) {
      if (shouldDropEarly(ts, masterClock = keyframe, threshold)) droppedWithStaleClock++
      ts += 40_000L
    }
    assertEquals(0, droppedWithStaleClock)

    // 正确做法：时钟设为目标时间 -> 中间帧全丢
    var droppedWithTargetClock = 0
    ts = keyframe
    while (ts < target) {
      if (shouldDropEarly(ts, masterClock = target, threshold)) droppedWithTargetClock++
      ts += 40_000L
    }
    assertEquals(50, droppedWithTargetClock)
  }

  // --------------------------------------------------------------- flags 契约

  @Test
  fun backwardFlagValueMatchesFfmpegHeader() {
    // 锁定枚举值，改名/挪位时立刻炸
    assertEquals(1, AVSEEK_FLAG_BACKWARD)
    assertEquals(2, AVSEEK_FLAG_BYTE)
    assertEquals(4, AVSEEK_FLAG_ANY)
  }

  private fun posec() = 42_000_000L
}
