package soko.ekibun.ffmpeg

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

/**
 * 基于 libavformat 的播放器。
 *
 * ## seek 为什么"会回到关键帧"
 *
 * `avformat_seek_file` 只在 demuxer 实现了 `read_seek2` 时才用得上
 * `min_ts`/`max_ts` 窗口。mp4/mov 只实现了旧的 `read_seek`（`mov_read_seek`），
 * 于是 `libavformat/seek.c` 会落进回退分支，**整个丢弃窗口**：
 *
 * ```c
 * dir = (ts - (uint64_t)min_ts > (uint64_t)max_ts - ts ? AVSEEK_FLAG_BACKWARD : 0);
 * ret = av_seek_frame(s, stream_index, ts, flags | dir);
 * ```
 *
 * 结果必然是 `<= ts` 的最近关键帧。这是容器索引的物理限制，
 * **不是靠调窗口能绕开的**（详见 `SeekWindowSemanticsTest`）。
 *
 * ## 所以"平滑"要分两步（照 ffplay 的做法）
 *
 * | 步骤 | ffplay 对应 | 本类对应 |
 * | --- | --- | --- |
 * | 落关键帧后**立刻把时钟拨到目标** | `set_clock(&is->extclk, seek_target / (double)AV_TIME_BASE, 0)` | `PTS(relate = ts, base = ts)`：`relate` 就是主时钟基准 |
 * | **丢掉**关键帧→目标之间的帧 | `frame_drops_early`（`diff = dpts - get_master_clock`） | [resumeImpl] 中的 `resyncTo` 丢帧 |
 *
 * 两者缺一不可：不拨时钟则主时钟停在关键帧时间、`diff` 恒为 0，一帧都丢不掉，
 * 画面又会从关键帧老实播起。
 */
class FFPlayer(
  url: String,
  io: AvIO.Handler,
  val playback: AvPlayback? = null,
) : AvFormat(url, io) {
  /**
   * 播放基准点。
   *
   * 注意 `relate` 是可变的：音频帧会把 `relate` 校正到**解码后实际的重采样时间戳**
   * （见 [resumeImpl] 里 `playback.flushFrame()` 的返回值），所以它不等于
   * seek 时给定的 `ts`。seek 后"画面上应该出现在哪一刻"由 [base] 表达，
   * 与会被音频反复校正的 `relate` 分开维护。
   */
  class PTS(
    val streams: Map<Int, AvStream>,
    private var relate: Long = 0,
    /** seek 时给定的目标时间戳；[resumeImpl] 的丢帧收敛用它作主时钟，之后置空。 */
    var base: Long? = null,
  ) {
    private var absolute: Long = System.currentTimeMillis()
    var playing: Boolean = false

    fun update(relate: Long) {
      this.relate = relate
      absolute = System.currentTimeMillis()
    }

    fun now(speedRatio: Float): Long =
      ((System.currentTimeMillis() - absolute) * speedRatio * AV_TIME_BASE / 1000).toLong() + relate
  }

  private var pts: PTS? = null

  private val playerDispatcher by lazy {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  }

  /**
   * 视频帧相对主时钟的最大可丢弃跨度（对应 `AV_NOSYNC_THRESHOLD`）。
   *
   * seek 后主时钟被拨到目标时间，关键帧到目标之间这段就是"落后主时钟"的部分。
   * 只有在阈值内的落后才丢：否则遇到时间戳异常（如流开头损坏、
   * `best_effort_timestamp` 为 NOPTS）会把整段画面吞掉。
   */
  private val maxResyncDropDistance = 10 * AV_TIME_BASE.toLong()

  /**
   * 单帧步进用的两个时间戳：画面上那一帧（[lastFrameTs]）、它的前一帧（[prevFrameTs]）。
   *
   * 退帧为什么不能"seek 到当前帧再往回挪一点"：容器级 seek 只落关键帧（见类文档），
   * 落点要靠 [resumeImpl] 的丢帧收敛补齐，而收敛的判据是"第一个时间戳 >= 目标的帧"
   * —— 目标设成当前帧，收敛完显示的仍是当前帧，退不动。所以前一帧的时间戳只能自己记：
   *
   * - **顺序播放**（含 [stepForward]）：新帧紧接上一帧，前一帧就是上一次的当前帧；
   * - **跳转之后**（seek / [stepBack]）：关键帧到落点之间被丢掉的帧里，**最后那一帧**
   *   正好是落点的前一帧，收敛期顺手记下来补位；
   * - 落点**恰好落在关键帧上**时一帧都没丢，前一帧无从得知 —— 这时 [prevFrameTs] 置空，
   *   由 [stepBack] 再空探一次把它补回来（否则会永远退不动，见 [stepBack] 的文档）。
   */
  private var lastFrameTs: Long? = null
  private var prevFrameTs: Long? = null

  /** 用户是否显式暂停过。seek 后据此决定要不要恢复播放。 */
  private var paused = false

  suspend fun play(
    streams: Map<Int, AvStream>,
    seek: Long? = null,
  ) = withContext(playerDispatcher) {
    pause()
    val p = seek ?: pts?.now(playback?.speedRatio() ?: 1f) ?: 0
    pts = PTS(streams).also { it.playing = true }
    paused = false
    seekTo(p)
  }

  suspend fun pause() =
    withContext(playerDispatcher) {
      paused = true
      pts?.playing = false
      playback?.pause()
      playingJob?.join()
    }

  private var playingJob: Deferred<Unit>? = null

  override suspend fun seekTo(
    ts: Long,
    stream: AvStream?,
    minTs: Long,
    maxTs: Long,
    flags: Int,
  ) = withContext(playerDispatcher) {
    val oldPts = pts ?: throw Exception("no pts data")
    val pauseAtSeekTo = oldPts.playing
    pause()
    if (pts != oldPts) return@withContext

    val newPts = PTS(oldPts.streams, ts, base = ts)
    pts = newPts
    // 清掉缓存的帧，并把它们归还：只关得掉**不在飞行中**的（`processing` 为空）——
    // 正在飞行的几帧由各自的作业收尾时归还（它们会发现自己已经不在队列里，
    // 见 updateJob 的 invokeOnCompletion），在这里关就是 use-after-free。
    frames.values.forEach { list -> list.filter { it.processing == null }.forEach { it.close() } }
    frames.clear()
    // flush codec：丢弃解码器内部的参考帧历史 / 重排缓冲。
    // 与 drain（sendPacket(NULL) 取残余尾帧）是两件事，不能互相替代。
    codecs.forEach {
      it.value.flush()
    }
    if (pts != newPts) return@withContext
    playback?.stop()
    // seek：即使传了 stream，mp4/mov 也只会落关键帧（见类注释）。
    // 落点靠下面的"丢帧收敛"补齐。
    if (pts != newPts) return@withContext
    super.seekTo(ts, stream, minTs, maxTs, flags)
    // 跳到下一帧
    if (pts != newPts) return@withContext
    if (newPts.streams.containsKey(AVMediaType.VIDEO)) {
      resume(!pauseAtSeekTo)
    }
  }

  suspend fun resume(stopOnNextFrame: Boolean = false) =
    withContext(playerDispatcher) {
      if (stopOnNextFrame) {
        val newPts = pts
        val hitFrame =
          suspendCancellableCoroutine<Boolean> {
            if (pts != newPts) return@suspendCancellableCoroutine
            playingJob = async(playerDispatcher) { resumeImpl(it) }
          }
        if (pts != newPts) return@withContext
        if (hitFrame) pause()
      } else {
        paused = false
        playingJob = async(playerDispatcher) { resumeImpl(null) }
      }
    }

  /**
   * 前进一帧：解出并显示下一帧，然后停住。
   *
   * 机制是现成的 —— [resume] 的 `stopOnNextFrame`（视频不等主时钟、送显后立刻 `pause()`）。
   * 先 [pause] 是为了收干净上一轮：两个播放轮次同时从 [frames] 取帧会各显示一半。
   */
  suspend fun stepForward() =
    withContext(playerDispatcher) {
      val p = pts ?: return@withContext
      // 纯音频没有"下一帧画面"，而且 [resume] 的停止信号只由视频帧给出 ——
      // 不拦这一下会一路解到 EOF 才回来（seekTo 里也是同一道判断）。
      if (!p.streams.containsKey(AVMediaType.VIDEO)) return@withContext
      pause()
      resume(stopOnNextFrame = true)
    }

  /**
   * 后退一帧：跳回画面上那一帧的前一帧，然后停住。
   *
   * 目标是 [prevFrameTs]，即前一帧自己的时间戳 —— 收敛会丢掉所有早于目标的帧，
   * 所以落点正是它，而不是"目标附近的关键帧"。先 [pause] 是为了让 [seekTo] 里的
   * `pauseAtSeekTo` 为假，从而由 `resume(stopOnNextFrame = true)` 落在目标帧上停住；
   * 否则 [seekTo] 会按"本来在播"把它接着播下去。
   *
   * 前一帧无从得知时（上一次跳转正好落在关键帧上、收敛一帧都没丢）先空探一次：目标取
   * 当前帧之前一微秒，收敛丢掉的最后那一帧就是真正的前一帧 —— 而空探本身不动画面
   * （收敛的落点是"第一个 >= 目标"的帧，仍是当前帧）。没有这一步，退帧会停在关键帧上
   * 再也退不动：前一帧只能从丢帧里学，而卡住之后不会再有帧显示，[prevFrameTs] 再也填不上。
   *
   * @return 是否真的移动了。还没显示过任何帧、或已经在文件第一帧上时为 `false`。
   */
  suspend fun stepBack(): Boolean =
    withContext(playerDispatcher) {
      val current = lastFrameTs ?: return@withContext false
      pause()
      if (prevFrameTs == null) {
        // 文件开头取 max：探不出结果，下面统一返回 false
        seekTo(maxOf(current - 1, 0))
      }
      val target = prevFrameTs ?: return@withContext false
      seekTo(target)
      true
    }

  private val codecs = HashMap<Int, AvCodec>()
  private val frames = HashMap<Int, ArrayList<AvFrame>>()

  private suspend fun resumeImpl(onNextFrame: CancellableContinuation<Boolean>?) =
    withContext(playerDispatcher) {
      val pts = pts ?: return@withContext
      val isPlaying = { pts == this@FFPlayer.pts && pts.playing }
      // seek 后要把画面从关键帧"快进"到目标时间：主时钟先停在目标时间，
      // 所有 PTS 落后于它的视频帧解码后立刻丢弃（对应 ffplay 的 frame_drops_early），
      // 直到第一帧追上目标才撤掉这个标记、回到正常同步。
      val resyncTo = pts.base?.also { pts.base = null }
      // 收敛期最后被丢掉的那一帧 —— 跳转后它就是落点的前一帧（见 [prevFrameTs]）。
      // 两个都是**本轮**的局部变量：出了这一轮，"丢掉过谁"就不再说明任何事。
      var lastDropped: Long? = null
      var converging = resyncTo != null
      val playJobs =
        pts.streams.map { (codecType, stream) ->
          async(playerDispatcher) {
            var lastUpdateJob: Job? = null
            while (isPlaying()) {
              val frame =
                frames[stream.index]?.firstOrNull { frame ->
                  frame.processing != pts
                }
              if (frame == null) {
                delay(1.milliseconds)
                continue
              }
              frame.processing = pts
              val lastUpdate = lastUpdateJob
              // 这一帧是否被本轮"消费"掉（送显、或有意丢弃）？没消费就说明轮次在它显示
              // 之前就停了 —— 那种帧必须还回队列，不能出队（见下面的 invokeOnCompletion）。
              var consumed = false
              lastUpdateJob =
                async(playerDispatcher) updateJob@{
                  if (!isPlaying()) return@updateJob
                  val muteOnNextFrame = {
                    codecType == AVMediaType.AUDIO && onNextFrame?.isActive == true
                  }
                  // 解码帧
                  if (!muteOnNextFrame()) playback?.postFrame(codecType, frame)
                  lastUpdate?.join()
                  if (!isPlaying()) return@updateJob
                  // seek 后的丢帧收敛（ffplay frame_drops_early）：
                  //   diff = dpts - master_clock;  主时钟 == resyncTo
                  //   |diff| < AV_NOSYNC_THRESHOLD && diff < 0  ->  解码后立刻丢
                  //
                  // 必须排在送显（[AvPlayback.flushFrame]）**之前**：视频那一路的
                  // flushFrame 没有返回值（恒 -1，VIDEO 分支只是送显这个副作用），
                  // 而送显就发生在那里 —— 排在它后面等于"先上屏、再决定丢不丢"。
                  // 判据用 AVFrame 自己的时间戳：native 侧已折算成 AV_TIME_BASE 微秒
                  // （见 ffmpeg.cpp 的 newAvFrame），与 seek 目标同单位。
                  // 时间戳异常（NOPTS 折算出的垃圾值）不会是"刚落后一点点"，
                  // 会被窗口上限挡住。
                  //
                  // **音频也要丢**：落点是容器级的，音频流同样会退到目标之前
                  // （实测 mp4：seek 到 5.0s，音频首包在 3.90s、视频关键帧在 4.0s）。
                  // 音频不丢就会把主时钟按它真正上屏的时间戳重新锚定
                  // （下面 `pts.update(timeStamp)`）—— 锚点落在目标之前，视频只能干等
                  // 时钟爬上来，表现是「画面要等声音播到目标才出现」。
                  //
                  // 丢掉的是 seek 后开头**连续**的一段，声卡此刻刚被 flush，
                  // 所以不会留下可听见的缺口（跟「丢流中间的孤立帧」是两回事）。
                  if (resyncTo != null &&
                    frame.timeStamp < resyncTo &&
                    resyncTo - frame.timeStamp < maxResyncDropDistance
                  ) {
                    // 退帧要的「前一帧」就在这些被丢掉的帧里 —— 记下最后那一帧。
                    // 本轮第一帧就追上目标时它仍是 null，含义是"前一帧无从得知"。
                    if (codecType == AVMediaType.VIDEO) lastDropped = frame.timeStamp
                    consumed = true
                    return@updateJob
                  }
                  // 等视频追上主时钟
                  if (codecType == AVMediaType.VIDEO && onNextFrame?.isActive != true) {
                    while (frame.timeStamp > pts.now(playback?.speedRatio() ?: 1f)) {
                      delay(1.milliseconds)
                      if (!isPlaying()) return@updateJob
                    }
                  }
                  if (muteOnNextFrame()) {
                    consumed = true
                    return@updateJob
                  }
                  val timeStamp = playback?.flushFrame(codecType, frame) ?: -1
                  consumed = true
                  if (!isPlaying()) return@updateJob
                  if (timeStamp >= 0) pts.update(timeStamp)
                  if (codecType == AVMediaType.VIDEO) {
                    // 单帧步进的位置记录（见 [prevFrameTs]）：本轮收敛过，前一帧就用
                    // 丢帧结果补；没收敛（顺序播放）时，前一帧就是上一次的当前帧。
                    prevFrameTs = if (converging) lastDropped else lastFrameTs
                    converging = false
                    lastFrameTs = frame.timeStamp
                    if (onNextFrame?.isActive == true) {
                      pts.update(frame.timeStamp)
                      onNextFrame.resumeWith(Result.success(true))
                    }
                  }
                  playback?.onFrame?.invoke(pts.now(playback.speedRatio()))
                }.also { job ->
                  job.invokeOnCompletion {
                    // 轮次在"已经取到帧、还没显示"时停住是常态 —— 单帧步进的每一轮都是
                    // 这样：播放协程把下一帧取进本轮了，而送显那一帧一发信号就把轮次停掉。
                    // 那种帧**不能出队**，否则它再也不会被显示，症状是每按一次「前进一帧」
                    // 跳过一帧（实测：只有一半的帧能靠步进走到）。
                    //
                    // 帧的归还也在这里收口（native 侧把所有权交给了 Java，见 AvFrame）：
                    // 出队就归还。"队列里已经没了"同样算出队 —— seek / closeAsync 清队时
                    // 只关得掉**不在飞行中**的帧，正在飞行的这几帧归它自己收尾。
                    // 只有"没消费、还留在队列里"的那种不关：撤掉标记留给下一轮，
                    // 它仍然归队列所有，最终由送显或清队那两处归还。
                    //
                    // ⚠️ 判在不在队里只能用 contains，**别拿 remove() 的返回值当判据** ——
                    // remove 是出队动作：它返回 true 时帧已经被摘下来了，此时只把
                    // `processing` 置空并不会把它放回去，于是那一帧既不在队里也不归还
                    // （不显示 + 泄漏）。2026-09-21 实测踩过：每轮孤儿掉一帧，
                    // 表现正是「前进一帧跨两帧」。
                    val queue = frames[stream.index]
                    if (consumed || queue?.contains(frame) != true) {
                      queue?.remove(frame)
                      frame.close()
                    } else {
                      frame.processing = null
                    }
                  }
                }
              lastUpdate?.join()
            }
          }
        }
      try {
        pts.playing = true
        playback?.resume()
        var sendingPacket = 0
        while (isPlaying()) {
          if (sendingPacket > 10 || frames.map { it.value.size }.sum() > 100) {
            delay(1.milliseconds)
            continue
          }
          val packet = getPacket(pts.streams.values)
          if (packet == null) {
            if (frames.map { it.value.size }.sum() == 0) {
              break
            }
            delay(100.milliseconds)
            continue
          }
          if (!isPlaying()) {
            // 提前退出：这个 packet 不会送到解码器了，就地归还
            packet.close()
            break
          }
          sendingPacket++
          // 等下载（流还没就绪）
          if (pts.streams.isEmpty()) {
            packet.close()
            sendingPacket--
            continue
          }
          val stream = pts.streams.values.first { it.index == packet.streamIndex }
          val codec =
            codecs.getOrPut(stream.index) {
              AvCodec(stream)
            }
          @Suppress("DeferredResultUnused")
          async(playerDispatcher) {
            // 一个 packet 可能产出 0 帧（B 帧重排时帧被解码器缓存）或多帧，
            // 必须把整批都收进来，只取第一帧会丢帧。
            val decoded =
              if (this@FFPlayer.pts == pts) {
                codec.sendPacketAndGetFrames(packet)
              } else {
                // 轮次已切换：不会送去解码，但 packet 已经分配出来了
                packet.close()
                emptyList()
              }
            sendingPacket--
            if (this@FFPlayer.pts != pts) {
              decoded.forEach { it.close() }
              return@async
            }
            if (decoded.isEmpty()) return@async
            frames.getOrPut(stream.index) { arrayListOf() }.addAll(decoded)
          }
        }
        // EOF 前把解码器内部缓存的尾帧 drain 出来，否则末尾若干帧永远播不出。
        if (isPlaying()) {
          val drained =
            codecs
              .map { (index, codec) ->
                async(playerDispatcher) { index to codec.drain() }
              }.awaitAll()
          if (pts == this@FFPlayer.pts) {
            val pending = drained.sumOf { it.second.size }
            if (pending > 0) {
              drained.forEach { (index, list) ->
                if (list.isNotEmpty()) frames.getOrPut(index) { arrayListOf() }.addAll(list)
              }
              // 等播放协程把这些帧消费掉，再走 finally
              while (isPlaying() && frames.any { it.value.isNotEmpty() }) {
                delay(10.milliseconds)
              }
            } else {
              drained.forEach { (_, list) -> list.forEach { it.close() } }
            }
          } else {
            drained.forEach { (_, list) -> list.forEach { it.close() } }
          }
        }
      } finally {
        if (onNextFrame?.isActive == true) onNextFrame.resumeWith(Result.success(false))
        pts.playing = false
        if (pts == this@FFPlayer.pts) playback?.onFrame?.invoke(null)
      }
      playJobs.joinAll()
    }

  suspend fun closeAsync() =
    withContext(playerDispatcher) {
      pause()
      // 队里剩下的帧也归还。必须排在 [pause] 之后：那一轮已经收干净，飞行中的帧都由各自的
      // 作业还过了，此刻留在队列里的都是没人要的（轮次停下时就地留下的那些）。
      frames.values.forEach { list -> list.forEach { it.close() } }
      frames.clear()
      super.closeDeferred().await()
      // 每个 codec 有自己的归属 dispatcher：这里要等**归还真的落地**，
      // 而不是投出去就返回 —— 所以 await 各自的 closeDeferred()。
      codecs
        .map { async(playerDispatcher) { it.value.closeDeferred().await() } }
        .awaitAll()
      codecs.clear()
    }
}
