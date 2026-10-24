package soko.ekibun.ffmpeg

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import soko.ekibun.ThreadDispatcher
import soko.ekibun.common.monoTimeMs
import kotlin.time.Duration.Companion.milliseconds

/**
 * 播放器：`play` / `seekTo` / `pause` / 单帧步进，入口都只在 [playerDispatcher] 上调。
 * 时序判据见 `docs/development/playback-invariants.md`。
 */
class FFPlayer(
  url: String,
  io: AvIO.Handler,
  val surfaceContext: AvSurfaceContext? = null,
  val onEvent: (Event) -> Unit,
) : AvFormat(url, io) {
  /** 播放侧状态上报：每送显一帧回调 [Frame]（结束以 `pts = null`），读超时回调 [ReadTimeout]。 */
  sealed interface Event {
    data class Frame(
      val pts: Long?,
    ) : Event

    data object ReadTimeout : Event

    data object ReadTimeoutResume : Event

    /**
     * 解复用器跳过了一段插播广告。[from]/[to] 是声明轴上的位置（微秒），
     * 在画面真正走到 [to] 之后才发出（读包是预读的，读到时发会比画面早好几秒）。
     */
    data class AdSkipped(
      val from: Long,
      val to: Long,
    ) : Event
  }

  /** 播放基准点；`relate` 由音频帧校正为实际重采样时间戳。 */
  class PTS(
    val streams: Map<Int, AvStream>,
    private var relate: Long = 0,
  ) {
    // 单调时钟：墙钟会被校时拨动，画面就快进或干等一截
    private var absolute: Long = monoTimeMs()
    var playing: Boolean = false

    fun update(relate: Long) {
      this.relate = relate
      absolute = monoTimeMs()
    }

    fun now(speedRatio: Float): Long = ((monoTimeMs() - absolute) * speedRatio * AV_TIME_BASE / 1000).toLong() + relate
  }

  private var pts: PTS? = null

  companion object {
    /** 播放侧的归属线程：播放入口只在它上面调。全进程共用，从不关闭。 */
    private val playerDispatcher by lazy { ThreadDispatcher("ffplayer") }

    /**
     * 退帧的最小退距（微秒）：落在分片首帧时，退距小于「首帧 pts 与片头 dts 的差」就跨不过
     * 分片边界，会退回当前帧。取 1 s 覆盖常见 B 帧延迟，代价是多解一小段帧。
     */
    private const val STEP_BACK_MARGIN_US = 1_000_000L

    /**
     * 相邻包的声明时间差超过它，就认为解复用器跳过分片了（正常只差一帧）。
     * 判定在 `hls.c` 里，见 `docs/design/hls-ad-skip.md`。
     */
    private const val AD_GAP_US = 1_000_000L

    /** 学习帧间隔的上限：跳过广告后相邻送显帧会差十几秒，学进去会让退帧一次退十几秒。 */
    private const val MAX_FRAME_INTERVAL_US = 200_000L

    /** 空档末端之前这么多也算「已过空档」：B 帧重排让接上的首帧比它早一两帧（实测 42ms）。 */
    private const val AD_SKIP_EDGE_US = 500_000L
  }

  /** 开一轮独占播放：作废上一轮并等它收干净；本轮身份由 [PTS] 对象标识。 */
  private suspend fun takeOverPlayback(): Boolean {
    val before = pts
    pause()
    return pts === before
  }

  /** 单帧步进用的两个时间戳：画面上那一帧与其前一帧（后者可为空）。 */
  private var lastFrameTs: Long? = null
  private var prevFrameTs: Long? = null

  suspend fun play(
    streams: Map<Int, AvStream>,
    seek: Long? = null,
  ) = withContext(playerDispatcher) {
    if (!takeOverPlayback()) return@withContext
    val p = seek ?: pts?.now(surfaceContext?.speedRatio ?: 1f) ?: 0
    pts = PTS(streams).also { it.playing = true }
    seekImpl(p, resumeAfter = true)
  }

  suspend fun pause() =
    withContext(playerDispatcher) {
      pts?.playing = false
      surfaceContext?.pause()
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
    // 「本来在播吗」必须在 [takeOverPlayback] 之前读：它会把上一轮作废
    val resumeAfter = pts?.playing == true
    if (!takeOverPlayback()) return@withContext
    seekImpl(ts, stream, minTs, maxTs, flags, resumeAfter)
  }

  /** [seekTo] 的实现体：轮次闸已经开过，这里只做跳转本身。 */
  private suspend fun seekImpl(
    ts: Long,
    stream: AvStream? = null,
    minTs: Long = Long.MIN_VALUE,
    maxTs: Long = Long.MAX_VALUE,
    flags: Int = 0,
    resumeAfter: Boolean,
    /** 本轮收敛到的目标；`null` = 用 [ts]。 */
    resyncTo: Long? = null,
    /** 退帧轮（mpv 的 `hrseek_backstep`）：停到 `resyncTo` 之前的最后一帧。 */
    backstep: Boolean = false,
  ) = withContext(playerDispatcher) {
    val oldPts = pts ?: throw Exception("no pts data")
    val newPts = PTS(oldPts.streams, ts)
    pts = newPts
    // 只关得掉不在飞行中的帧；飞行中的由各自的作业收尾时归还
    frames.values.forEach { list -> list.filter { it.processing == null }.forEach { it.close() } }
    frames.clear()
    // flush 丢弃解码器内部的参考帧历史，与 drain（取残余尾帧）是两件事
    codecs.forEach {
      it.value.flush()
    }
    if (pts != newPts) return@withContext
    surfaceContext?.stop()
    // 即使传了 stream，mp4/mov 也只落关键帧；落点靠下面的丢帧收敛补齐
    if (pts != newPts) return@withContext
    super.seekTo(ts, stream, minTs, maxTs, flags)
    if (pts != newPts) return@withContext
    when {
      // 视频轨在：步进轮靠「下一帧画面」停住，续播轮带 resyncTo 做丢帧收敛
      newPts.streams.containsKey(AVMediaType.VIDEO) ->
        resume(stopOnNextFrame = !resumeAfter, resyncTo = resyncTo ?: ts, backstep = backstep)
      // 纯音频没有「下一帧画面」，stopOnNextFrame 会一路解到 EOF
      resumeAfter -> resume(resyncTo = resyncTo ?: ts, backstep = backstep)
    }
  }

  /** 从当前位置起本轮播放作业（不开闸；[resyncTo] 是本轮要收敛到的目标）。 */
  suspend fun resume(
    stopOnNextFrame: Boolean = false,
    resyncTo: Long? = null,
    backstep: Boolean = false,
  ) = withContext(playerDispatcher) {
    if (stopOnNextFrame) {
      val hitFrame =
        suspendCancellableCoroutine<Boolean> {
          playingJob = async(playerDispatcher) { resumeImpl(it, resyncTo, backstep) }
        }
      if (hitFrame) pause()
    } else {
      playingJob = async(playerDispatcher) { resumeImpl(null, resyncTo, backstep) }
    }
  }

  /** 前进一帧并停住（[resume] 的 `stopOnNextFrame`）；并发调用同样是顶掉而非排队。 */
  suspend fun stepForward() =
    withContext(playerDispatcher) {
      val p = pts ?: return@withContext
      // 纯音频没有「下一帧画面」，放进去会一路解到 EOF
      if (!p.streams.containsKey(AVMediaType.VIDEO)) return@withContext
      if (!takeOverPlayback()) return@withContext
      resume(stopOnNextFrame = true)
    }

  /**
   * 后退一帧并停住，目标是 [prevFrameTs]。
   * @return 是否真的移动了（没显示过帧、已在第一帧、空探学不到前一帧时为 `false`）。
   */
  suspend fun stepBack(): Boolean =
    withContext(playerDispatcher) {
      val current = lastFrameTs ?: return@withContext false
      if (!takeOverPlayback()) return@withContext false

      // mpv 的 backstep：只跳一次，剩下的由送显端补成「前一帧」。
      // 退距不能只取 3/4 帧：当前帧正好是分片首帧时，容器的落点不会早于片头，
      // 3/4 帧退回同一片就白跳；退足 [STEP_BACK_MARGIN_US] 才能跨过分片边界
      val interval = pts?.streams?.get(AVMediaType.VIDEO)?.frameIntervalUs ?: 0
      val eps = maxOf(interval * 3 / 4, STEP_BACK_MARGIN_US, 1)
      seekImpl(maxOf(current - eps, 0), resumeAfter = false, resyncTo = current, backstep = true)
      lastFrameTs != current
    }

  private val codecs = HashMap<Int, AvCodec>()
  private val frames = HashMap<Int, ArrayList<AvFrame>>()

  private suspend fun resumeImpl(
    onNextFrame: CancellableContinuation<Boolean>?,
    /** 本轮起跑要收敛到的目标时间戳（seek 后才有）。见 [resume]。 */
    resyncTo: Long?,
    /** 退帧轮：`resyncTo` 就是要退回去的原 pts。 */
    backstep: Boolean,
  ) = withContext(playerDispatcher) {
    val pts = pts ?: return@withContext
    // 必须带上「对象没换」：换位置的路径会换 [PTS]，这就是「本轮还作不作数」
    val isPlaying = { pts == this@FFPlayer.pts && pts.playing }
    // 判据只能取轮次级：onNextFrame 在帧送显时就翻，而轮次要等 pause() 落地才停
    val stepMode = onNextFrame != null
    // seek 后把画面从关键帧快进到目标：落后的视频帧解码后立刻丢（ffplay frame_drops_early）
    var lastDropped: Long? = null
    var converging = resyncTo != null
    var backstepping = backstep && resyncTo != null
    // 广告跳段留下的声明轴空档：按视频流的相邻包识别，等画面走到空档末端才上报。
    // 只跟一条流：音视频各自都会出现同一个空档，跟两条会重复上报。
    val skipTrackIndex = (pts.streams[AVMediaType.VIDEO] ?: pts.streams.values.firstOrNull())?.index
    // 时钟由音频帧推进（`flushFrame` 只对音频返回时间戳）；纯视频流没有它，跳过广告后要自己抬时钟。
    val clockDrivenByAudio = pts.streams.containsKey(AVMediaType.AUDIO)
    var skipLastPts = -1L
    val pendingSkips = ArrayDeque<Pair<Long, Long>>()
    val playJobs =
      pts.streams.map { (codecType, stream) ->
        async(playerDispatcher) {
          var lastUpdateJob: Job? = null
          while (isPlaying()) {
            val frame =
              // 只看有没有人在用，不比对是谁：上一轮的 updateJob 可能还没收尾
              frames[stream.index]?.firstOrNull { frame ->
                frame.processing == null
              }
            if (frame == null) {
              delay(1.milliseconds)
              continue
            }
            frame.processing = pts
            val lastUpdate = lastUpdateJob
            // 没消费就说明轮次在它显示之前停了，那种帧必须还回队列
            var consumed = false
            lastUpdateJob =
              async(playerDispatcher) updateJob@{
                if (!isPlaying()) return@updateJob
                // 步进轮里的音频整轮静音
                val muted = stepMode && codecType == AVMediaType.AUDIO
                // 先等上一帧送显完再写这一帧：每种流只有一块输出缓冲，反了就是上一帧显示成本帧的像素
                lastUpdate?.join()
                if (!isPlaying()) return@updateJob
                // 退帧：只显示原 pts 之前的最后一帧。判据看队列里的下一帧 —— 本帧之后还有帧、
                // 且仍早于原 pts，就说明它不是「前一帧」；判中的那帧要跳过下面的收敛丢弃
                var backstepFrame = false
                val from = resyncTo
                if (backstepping &&
                  from != null &&
                  codecType == AVMediaType.VIDEO &&
                  frame.timeStamp < from
                ) {
                  // 下一帧可能还在解码：等它进队列再判，否则会把倒数第二帧误当成前一帧
                  var waited = 0
                  while (
                    isPlaying() &&
                    waited++ < 1000 &&
                    frames[stream.index]?.any { f -> f.timeStamp > frame.timeStamp } != true
                  ) {
                    delay(1.milliseconds)
                  }
                  if (!isPlaying()) return@updateJob
                  val hasLater =
                    frames[stream.index]?.any { f ->
                      f.timeStamp > frame.timeStamp && f.timeStamp < from
                    } == true
                  if (hasLater) {
                    consumed = true
                    return@updateJob
                  }
                  backstepFrame = true
                  backstepping = false
                }
                if (!muted) surfaceContext?.postFrame(codecType, frame)
                // 丢帧收敛必须排在送显之前，音频也要丢（落点是容器级的）
                if (!backstepFrame && resyncTo != null && frame.timeStamp < resyncTo) {
                  if (codecType == AVMediaType.VIDEO) lastDropped = frame.timeStamp
                  consumed = true
                  return@updateJob
                }
                if (codecType == AVMediaType.VIDEO && onNextFrame?.isActive != true) {
                  // 跳过广告后声明轴直接跳过一整段。有音频时它推着时钟走；纯视频流没人推，
                  // 这一帧会被下面的门限挡住整整一个广告时长，所以这里把时钟抬到空档之后。
                  val skip = pendingSkips.firstOrNull()
                  if (!clockDrivenByAudio &&
                    skip != null &&
                    frame.timeStamp >= skip.second - AD_SKIP_EDGE_US
                  ) {
                    pts.update(frame.timeStamp)
                  }
                  // 等到本帧到达播放时刻；中途停播就退出本轮
                  while (frame.timeStamp > pts.now(surfaceContext?.speedRatio ?: 1f)) {
                    delay(1.milliseconds)
                    if (!isPlaying()) return@updateJob
                  }
                  // 队列里已有更晚、且仍落在播放窗口内的帧 ⇒ 本帧已过期
                  val stale =
                    frames[stream.index]?.any { f ->
                      f.timeStamp > frame.timeStamp &&
                        f.timeStamp < pts.now(surfaceContext?.speedRatio ?: 1f)
                    } == true
                  if (stale) {
                    consumed = true
                    return@updateJob
                  }
                }
                if (muted) {
                  consumed = true
                  return@updateJob
                }
                val timeStamp = surfaceContext?.flushFrame(codecType, frame) ?: -1
                consumed = true
                if (!isPlaying()) return@updateJob
                if (timeStamp >= 0) pts.update(timeStamp)
                if (codecType == AVMediaType.VIDEO) {
                  // 画面走到空档之后才提示；读包是预读的，读到时发会比画面早好几秒
                  while (pendingSkips.isNotEmpty() && pendingSkips.first().second <= frame.timeStamp) {
                    val (skippedFrom, skippedTo) = pendingSkips.removeFirst()
                    onEvent(Event.AdSkipped(skippedFrom, skippedTo))
                  }
                  // 本轮收敛过就用丢帧结果补前一帧，未收敛时沿用上一次的当前帧
                  prevFrameTs = if (converging) lastDropped else lastFrameTs
                  converging = false
                  lastFrameTs = frame.timeStamp
                  // 只有真看到连续两帧时，差值才是可信的帧间隔；跳过广告那一段差十几秒，不能学
                  val prev = prevFrameTs
                  if (prev != null) {
                    val delta = frame.timeStamp - prev
                    if (delta in 1..MAX_FRAME_INTERVAL_US) stream.frameIntervalUs = delta
                  }
                  if (onNextFrame?.isActive == true) {
                    pts.update(frame.timeStamp)
                    onNextFrame.resumeWith(Result.success(true))
                  }
                }
                onEvent(Event.Frame(frame.timeStamp))
              }.also { job ->
                job.invokeOnCompletion {
                  // 轮次在「已取到帧、还没显示」时停住是常态：这种帧不能出队。
                  // 判据只能用 contains，别拿 remove() 的返回值当判据。
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
      // 步进轮不碰音频设备：pause() 的 stop() 不丢弃已排队的 PCM
      if (!stepMode) surfaceContext?.resume()
      var sendingPacket = 0
      var isReadTimeout = false
      // 尾帧还在解码器的重排缓冲里，只有 drain 能取出来。读到 EOF 就地 drain 一次：
      // 退帧轮里队列不会空（有一帧正等着判「是不是前一帧」），而原来的 drain 排在循环之后、
      // 又以「队列空」为出循环条件 ⇒ 尾部两帧永远不出队，门控等满超时后把倒数第三帧当成了前一帧。
      var drainedTail = false
      while (isPlaying()) {
        if (sendingPacket > 3 || (frames.map { it.value.size }.minOrNull() ?: 0) > 3) {
          delay(1.milliseconds)
          continue
        }
        val packet =
          try {
            withTimeout(100.milliseconds) { getPacket(pts.streams.values) }
          } catch (e: TimeoutCancellationException) {
            if (!isReadTimeout) onEvent(Event.ReadTimeout)
            isReadTimeout = true
            // 不额外退避：withTimeout 本身就是节拍，超时不重进 native
            continue
          }
        if (isReadTimeout) onEvent(Event.ReadTimeoutResume)
        isReadTimeout = false

        if (packet == null) {
          if (!drainedTail) {
            drainedTail = true
            val drained =
              codecs
                .map { (index, codec) ->
                  async(playerDispatcher) { index to codec.drain() }
                }.awaitAll()
            if (pts == this@FFPlayer.pts) {
              drained.forEach { (index, list) ->
                if (list.isNotEmpty()) frames.getOrPut(index) { arrayListOf() }.addAll(list)
              }
            } else {
              drained.forEach { (_, list) -> list.forEach { it.close() } }
            }
            continue
          }
          if (frames.map { it.value.size }.sum() == 0) {
            break
          }
          delay(100.milliseconds)
          continue
        }
        if (this@FFPlayer.pts != pts) {
          // 轮次换了：包属于旧位置，就地归还
          packet.close()
          break
        }
        // 只是暂停（对象没换）：照常送进解码器，这就是暂停期间的预读缓存
        if (pts.streams.isEmpty()) {
          packet.close()
          continue
        }
        // 记投出去还没回来的解码作业数
        sendingPacket++
        val stream = pts.streams.values.first { it.index == packet.streamIndex }
        if (stream.index == skipTrackIndex) {
          val declared = packet.ptsUs
          if (declared >= 0) {
            if (skipLastPts >= 0 && declared - skipLastPts > AD_GAP_US) {
              pendingSkips.addLast(skipLastPts to declared)
            }
            skipLastPts = declared
          }
        }
        val codec =
          codecs.getOrPut(stream.index) {
            AvCodec(stream)
          }
        @Suppress("DeferredResultUnused")
        async(playerDispatcher) {
          // 一个 packet 可能产出 0 帧（B 帧重排）或多帧，整批都要收
          val decoded =
            if (this@FFPlayer.pts == pts) {
              codec.sendPacketAndGetFrames(packet)
            } else {
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
    } finally {
      if (onNextFrame?.isActive == true) onNextFrame.resumeWith(Result.success(false))
      pts.playing = false
      if (pts == this@FFPlayer.pts) onEvent(Event.Frame(null))
    }
    playJobs.joinAll()
  }

  suspend fun closeAsync() =
    withContext(playerDispatcher) {
      pause()
      // 必须排在 [pause] 之后：此刻留在队列里的都是没人要的
      frames.values.forEach { list -> list.forEach { it.close() } }
      frames.clear()
      // 必须排在 super.closeDeferred 之前：abort 要在 formatDispatcher 之外发出并 join 到读作业死透
      resetChannel()
      super.closeDeferred().join()
      // 各 codec 有自己的归属 dispatcher，要等归还真的落地
      codecs
        .map { async(playerDispatcher) { it.value.closeDeferred().join() } }
        .awaitAll()
      codecs.clear()
    }
}
