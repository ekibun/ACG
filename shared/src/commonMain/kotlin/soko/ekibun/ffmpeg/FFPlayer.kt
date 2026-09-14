package soko.ekibun.ffmpeg

import kotlinx.coroutines.*
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
 * | 落关键帧后**立刻把时钟拨到目标** | `set_clock(&is->extclk, seek_target / (double)AV_TIME_BASE, 0)` | `PTS(base = ts)` + [resume] 里的 `pts.update(ts)` |
 * | **丢掉**关键帧→目标之间的帧 | `frame_drops_early`（`diff = dpts - get_master_clock`） | [resumeImpl] 中的 `resyncTo` 丢帧 |
 *
 * 两者缺一不可：不拨时钟则主时钟停在关键帧时间、`diff` 恒为 0，一帧都丢不掉，
 * 画面又会从关键帧老实播起。
 */
class FFPlayer(
  url: String,
  io: AvIO.Handler,
  val playback: AvPlayback? = null
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

  private val dispatcher by lazy {
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

  /** 用户是否显式暂停过。seek 后据此决定要不要恢复播放。 */
  private var paused = false

  suspend fun play(streams: Map<Int, AvStream>, seek: Long? = null) = withContext(dispatcher) {
    pause()
    val p = seek ?: pts?.now(playback?.speedRatio ?: 1f) ?: 0
    pts = PTS(streams).also { it.playing = true }
    paused = false
    seekTo(p)
  }

  suspend fun pause() = withContext(dispatcher) {
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
  ) = withContext(dispatcher) {
    val oldPts = pts ?: throw Exception("no pts data")
    val pauseAtSeekTo = oldPts.playing
    pause()
    if (pts != oldPts) return@withContext

    val newPts = PTS(oldPts.streams, ts, base = ts)
    pts = newPts
    // remove cache frame
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
    // seek to next frame
    if (pts != newPts) return@withContext
    if (newPts.streams.containsKey(AVMediaType.VIDEO)) {
      resume(!pauseAtSeekTo)
    }
  }

  suspend fun resume(stopOnNextFrame: Boolean = false) = withContext(dispatcher) {
    if (stopOnNextFrame) {
      val newPts = pts
      val hitFrame = suspendCancellableCoroutine<Boolean> {
        if (pts != newPts) return@suspendCancellableCoroutine
        playingJob = async(dispatcher) { resumeImpl(it) }
      }
      if (pts != newPts) return@withContext
      if (hitFrame) pause()
    } else {
      paused = false
      playingJob = async(dispatcher) { resumeImpl(null) }
    }
  }

  private val codecs = HashMap<Int, AvCodec>()
  private val frames = HashMap<Int, ArrayList<AvFrame>>()

  private suspend fun resumeImpl(onNextFrame: CancellableContinuation<Boolean>?) =
    withContext(dispatcher) {
      val pts = pts ?: return@withContext
      val isPlaying = { pts == this@FFPlayer.pts && pts.playing }
      // seek 后要把画面从关键帧"快进"到目标时间：主时钟先停在目标时间，
      // 所有 PTS 落后于它的视频帧解码后立刻丢弃（对应 ffplay 的 frame_drops_early），
      // 直到第一帧追上目标才撤掉这个标记、回到正常同步。
      val resyncTo = pts.base?.also { pts.base = null }
      val playJobs = pts.streams.map { (codecType, stream) ->
        async(dispatcher) {
          var lastUpdateJob: Job? = null
          while (isPlaying()) {
            val frame = frames[stream.index]?.firstOrNull { frame ->
              frame.processing != pts
            }
            if (frame == null) {
              delay(1.milliseconds)
              continue
            }
            frame.processing = pts
            val lastUpdate = lastUpdateJob
            lastUpdateJob = async(dispatcher) updateJob@{
              if (!isPlaying()) return@updateJob
              val muteOnNextFrame = {
                codecType == AVMediaType.AUDIO && onNextFrame?.isActive == true
              }
              // decode frame
              if (!muteOnNextFrame()) playback?.postFrame(codecType, frame)
              lastUpdate?.join()
              if (!isPlaying()) return@updateJob
              // wait video
              if (codecType == AVMediaType.VIDEO && onNextFrame?.isActive != true) {
                while (frame.timeStamp > pts.now(playback?.speedRatio ?: 1f)) {
                  delay(1.milliseconds)
                  if (!isPlaying()) return@updateJob
                }
              }
              if (muteOnNextFrame()) return@updateJob
              val timeStamp = playback?.flushFrame(codecType, frame) ?: -1
              if (!isPlaying()) return@updateJob
              /*
               * seek 后的丢帧收敛（ffplay frame_drops_early）：
               *   diff = dpts - master_clock;  主时钟 == resyncTo
               *   |diff| < AV_NOSYNC_THRESHOLD && diff < 0  ->  解码后立刻丢
               *
               * 必须放在 flushFrame 之后：音频路径的 flushFrame 会把解码器的
               * 重采样延迟（前导静音）折算出来，用它才能拿到真正要上屏的时间戳，
               * 否则每帧都会被误判成超前主时钟而全部播出去。
               *
               * 只对视频做：音频丢帧会在数据流里留下缺口 -> 可听见的杂音；
               * ffplay 的丢帧阈值事实上也只对视频生效。音频照样更新
               * relate（下面 `pts.update(timeStamp)`），时钟因此能先跳到目标，
               * 视频随后跟上来。
               */
              if (codecType == AVMediaType.VIDEO && resyncTo != null &&
                timeStamp >= 0 && timeStamp < resyncTo &&
                resyncTo - timeStamp < maxResyncDropDistance
              ) {
                return@updateJob
              }
              if (timeStamp >= 0) pts.update(timeStamp)
              if (codecType == AVMediaType.VIDEO && onNextFrame?.isActive == true) {
                pts.update(frame.timeStamp)
                onNextFrame.resumeWith(Result.success(true))
              }
              playback?.onFrame?.invoke(pts.now(playback.speedRatio))
            }.also { job ->
              job.invokeOnCompletion {
                frames[stream.index]?.remove(frame)
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
            if (frames.map { it.value.size }.sum() == 0)
              break
            delay(100.milliseconds)
            continue
          }
          if (!isPlaying()) {
            break
          }
          sendingPacket++
          // for downloading
          if (pts.streams.isEmpty()) {
            sendingPacket--
            continue
          }
          val stream = pts.streams.values.first { it.index == packet.streamIndex }
          val codec = codecs.getOrPut(stream.index) {
            AvCodec(stream)
          }
          @Suppress("DeferredResultUnused")
          async(dispatcher) {
            // 一个 packet 可能产出 0 帧（B 帧重排时帧被解码器缓存）或多帧，
            // 必须把整批都收进来，只取第一帧会丢帧。
            val decoded = if (this@FFPlayer.pts == pts) {
              codec.sendPacketAndGetFrames(packet)
            } else emptyList()
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
          val drained = codecs.map { (index, codec) ->
            async(dispatcher) { index to codec.drain() }
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

  override suspend fun close() = withContext(dispatcher) {
    pause()
    super.close()
    codecs.map {
      async(dispatcher) {
        it.value.close()
      }
    }.awaitAll()
    codecs.clear()
  }
}
