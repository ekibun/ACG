package soko.ekibun.ffmpeg

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
  /**
   * 视频解码优先走硬件：桌面用 D3D11VA，Android 用 MediaCodec。只是偏好，拿不到设备
   * 或这个 codec 没有加速版本时 native 自动回落软解。硬解出来的帧在 native 侧已经
   * 转回内存，所以送显链路不区分两种情况。解码器的输出形态在 open 时定死，
   * 之后改不了 —— 要换得新建播放器。
   */
  val hardware: Boolean = true,
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
     * [from] 是断点前的最后一帧，也是「回退这次跳过」该落到的位置。
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
     * 相邻包的声明时间差超过它，就认为解复用器跳过分片了（正常只差一帧）。
     * 判定在 `hls.c` 里，见 `docs/design/hls-ad-skip.md`。
     */
    private const val AD_GAP_US = 1_000_000L

    /** 读侧多久没有新包就认为卡住。正常播放相邻两包的空窗只有十几毫秒（实测 ≤ 11 ms）。 */
    private const val READ_STALL_MS = 300L
  }

  /** 画面上那一帧的时间戳；还没显示过帧时为 `null`。 */
  private var lastFrameTs: Long? = null

  suspend fun play(
    streams: Map<Int, AvStream>,
    seek: Long? = null,
  ) = withContext(playerDispatcher) {
    val p = seek ?: pts?.now(surfaceContext?.speedRatio ?: 1f) ?: 0
    pts = PTS(streams).also { it.playing = true }
    seekImpl(p, resumeAfter = true)
  }

  /**
   * 解码作业的归属域：挂在播放器上，不挂在轮次上。
   *
   * 轮次收场不能等它 —— 硬解窗口满了要等上层把帧还回来才腾得出位置，而这一轮取帧的作业已经退了，
   * 这口气只有下一路 seek（关帧 + 停 surface + 换 `pts`）能给。依赖留着的话 `resumeImpl` 不返回，
   * `round.join()` 跟着卡死，症状是「退帧卡住，直到下一次退帧才把上一轮的日志打完」。
   */
  private val decodeScope =
    CoroutineScope(
      // 脱出轮次的作业树之后异常不再经由轮次上抛，就地记下来，别掀掉整个进程
      SupervisorJob() + playerDispatcher + CoroutineExceptionHandler { _, e -> e.printStackTrace() },
    )

  suspend fun pause() =
    withContext(playerDispatcher) {
      pts?.playing = false
      surfaceContext?.pause()
      playingJob?.join()
    }

  private var playingJob: Job? = null

  suspend fun seekTo(ts: Long) =
    withContext(playerDispatcher) {
      // 「本来在播吗」要在换 PTS 之前读：seekImpl 一进门就换对象、停旧轮
      seekImpl(ts, resumeAfter = pts?.playing == true)
    }

  /**
   * [seekTo] 的实现体：轮次闸已经开过，这里只做跳转本身。
   *
   * @return 本轮是否作数。`false` = 中途被另一路 seek 顶掉，调用方不能再对这个位置做后续动作
   *   （比如 [reverseAd] 的续播）。
   */
  private suspend fun seekImpl(
    ts: Long,
    resumeAfter: Boolean,
    /** 本轮收敛到的目标；`null` = 用 [ts]。 */
    resyncTo: Long? = null,
    /** 退帧轮（mpv 的 `hrseek_backstep`）：停到 `resyncTo` 之前的最后一帧。 */
    backstep: Boolean = false,
  ): Boolean =
    withContext(playerDispatcher) {
      val oldPts = pts ?: throw Exception("no pts data")
      val newPts = PTS(oldPts.streams, ts)
      pts = newPts
      pause()
      if (pts !== newPts) return@withContext false
      decodeJobs.values.joinAll()
      if (pts !== newPts) return@withContext false
      // pause() 把 playing 置假了，新轮的意图在这里补回来：seek 期间（慢源可达上百毫秒）另一个
      // seek 靠读它判断「本来在播吗」，留成假就会被判成暂停态，只送一帧就收轮
      newPts.playing = resumeAfter
      // 只关得掉不在飞行中的帧；飞行中的由各自的作业收尾时归还
      frames.values.forEach { list ->
        list.forEach { it.close() }
        list.clear()
      }
      frames.clear()
      // 换了位置，「上一帧」不再是参考：差 2 s 就抬时钟的判据只对连续播放成立
      lastFrameTs = null
      // flush 丢弃解码器内部的参考帧历史，与 drain（取残余尾帧）是两件事
      codecs.forEach {
        it.value.flush()
        if (pts != newPts) return@withContext false
      }
      surfaceContext?.stop()
      // 即使传了 stream，mp4/mov 也只落关键帧；落点靠下面的丢帧收敛补齐
      if (pts != newPts) return@withContext false
      val skipAdBefore = isSkipAd && backstep
      if (skipAdBefore) setSkipAd(false)
      try {
        super.seekTo(ts, null, Long.MIN_VALUE, Long.MAX_VALUE, 0)
      } finally {
        if (skipAdBefore) setSkipAd(true)
      }
      if (pts != newPts) return@withContext false
      when {
        // 视频轨在：步进轮靠「下一帧画面」停住，续播轮带 resyncTo 做丢帧收敛
        newPts.streams.containsKey(AVMediaType.VIDEO) ->
          resume(stepMode = !resumeAfter, resyncTo = resyncTo ?: ts, backstep = backstep)
        // 纯音频没有「下一帧画面」，stepMode 会一路解到 EOF
        resumeAfter -> resume(resyncTo = resyncTo ?: ts, backstep = backstep)
      }
      true
    }

  /** 从当前位置起本轮播放作业；[stepMode] 时送到第一帧就收轮。 */
  suspend fun resume(
    stepMode: Boolean = false,
    resyncTo: Long? = null,
    backstep: Boolean = false,
  ): Unit =
    withContext(playerDispatcher) {
      playingJob = async { resumeImpl(stepMode, resyncTo, backstep) }
    }

  /** 前进一帧并停住（[resume] 的 `stepMode`）；并发调用同样是顶掉而非排队。 */
  suspend fun stepForward() =
    withContext(playerDispatcher) {
      val p = pts ?: return@withContext
      // 纯音频没有「下一帧画面」，放进去会一路解到 EOF
      if (!p.streams.containsKey(AVMediaType.VIDEO)) return@withContext
      resume(stepMode = true)
    }

  /**
   * 后退一帧并停住：退到 [lastFrameTs] 之前最近的那一帧。
   * @return 是否真的移动了（没显示过帧、已在第一帧、空探学不到前一帧时为 `false`）。
   */
  suspend fun stepBack(): Boolean =
    withContext(playerDispatcher) {
      val current = lastFrameTs ?: return@withContext false
      // 试着跳1ms；没退动（一帧没送显，或退回原帧）再退 1 s 重试
      if (
        !seekImpl(maxOf(current - AV_TIME_BASE / 1000, 0), resumeAfter = false, resyncTo = current, backstep = true)
      ) {
        return@withContext false
      }
      if (lastFrameTs == null || lastFrameTs == current) {
        // 1ms不够跳分片，重试一次1s
        if (
          !seekImpl(maxOf(current - AV_TIME_BASE, 0), resumeAfter = false, resyncTo = current, backstep = true)
        ) {
          return@withContext false
        }
      }
      lastFrameTs != null && lastFrameTs != current
    }

  suspend fun reverseAd(from: Long) =
    withContext(playerDispatcher) {
      // 输了竞态就别续播：`resume()` 不顶轮，它会把另一路 seek 刚建好的 PTS 直接放起来
      if (!seekImpl(from + AV_TIME_BASE / 10, resumeAfter = false, backstep = true)) return@withContext
      resume()
    }

  private val codecs = HashMap<Int, AvCodec>()
  private val frames = HashMap<Int, ArrayList<AvFrame>>()
  private val decodeJobs = HashMap<Int, Job>()

  /** 跑一轮播放；[stepMode] 时送到第一帧就收轮。 */
  private suspend fun resumeImpl(
    /** 步进 / 退帧轮：送到下一帧就结束该流，不等播放时刻。 */
    stepMode: Boolean,
    resyncTo: Long?,
    backstep: Boolean,
  ) = withContext(playerDispatcher) {
    val pts = pts ?: return@withContext
    var isActive = true
    var isReadTimeout = false
    // 时间轴的上报源：有音频只报音频（音频按时长推进，比视频帧稳），没有才报视频
    val ptsFromAudio = pts.streams.containsKey(AVMediaType.AUDIO)
    // 播放轮次
    val isPlaying = { isActive && pts == this@FFPlayer.pts && pts.playing }
    // 判断AvPacket是否继续传入解码器
    val isPacketValid = { pts == this@FFPlayer.pts }
    val playJobs =
      pts.streams.map { (codecType, stream) ->
        launch {
          updateJob@while (isPlaying()) {
            // 取第一个没处理的帧
            val frames = frames[stream.index]
            val frame =
              frames?.firstOrNull() ?: run {
                delay(1.milliseconds)
                continue
              }
            // 没消费就说明轮次在它显示之前停了，那种帧必须还回队列
            var consumed = false
            try {
              // 丢帧收敛放在 sws 之前：这类帧不必再转一次缓冲
              if (resyncTo != null && frame.timeStamp < resyncTo) {
                if (backstep && codecType == AVMediaType.VIDEO) {
                  // 等下一帧
                  while (true) {
                    if (!isPlaying()) break@updateJob
                    frames.getOrNull(1)?.let { next ->
                      if (next.timeStamp < resyncTo) {
                        // 下一帧不是就丢掉
                        consumed = true
                        continue@updateJob
                      }
                      break
                    }
                    delay(1.milliseconds)
                  }
                } else {
                  consumed = true
                  continue@updateJob
                }
              }
              // seek到了后面的帧，结束流程。这帧不消费、还回队列：1 s 退避的落点就是这里，
              // 用户接着点播放就是从上屏那帧往下走
              if (backstep && resyncTo != null && frame.timeStamp >= resyncTo) {
                if (codecType == AVMediaType.VIDEO) pts.playing = false
                break@updateJob
              }
              // sws 转到输出缓冲
              surfaceContext?.postFrame(codecType, frame)
              // 步进轮里的音频整轮静音：不送显、不报时间戳，这一轮的时间轴只由画面报
              if (stepMode && codecType == AVMediaType.AUDIO) {
                consumed = true
                continue@updateJob
              }
              // 视频等播放时刻（步进轮不等）
              if (codecType == AVMediaType.VIDEO && !stepMode) {
                // 差超过[AD_GAP_US]就resync
                val lastTs = lastFrameTs
                if (lastTs != null && frame.timeStamp - lastTs > AD_GAP_US) {
                  // 按声明轴先后给：from 是断点前最后一帧，也就是「回退跳过」的落点
                  onEvent(Event.AdSkipped(from = lastTs, to = frame.timeStamp))
                  pts.update(frame.timeStamp)
                } else {
                  while (frame.timeStamp > pts.now(surfaceContext?.speedRatio ?: 1f)) {
                    delay(1.milliseconds)
                    if (!isPlaying()) break@updateJob
                  }
                }
              }
              val timeStamp = surfaceContext?.flushFrame(codecType, frame) ?: -1
              consumed = true
              if (!isPlaying()) break@updateJob
              if (timeStamp >= 0) pts.update(timeStamp)
              if (codecType == AVMediaType.VIDEO) {
                lastFrameTs = frame.timeStamp
                if (stepMode) {
                  pts.update(frame.timeStamp)
                  pts.playing = false
                  onEvent(Event.Frame(frame.timeStamp))
                  break@updateJob
                }
              }
              // 有音频时视频帧不报时间戳（时间轴由音频推），纯视频才由画面报
              if (!ptsFromAudio || codecType == AVMediaType.AUDIO) onEvent(Event.Frame(frame.timeStamp))
            } finally {
              if (consumed && frames.remove(frame)) {
                frame.close()
              }
            }
          }
        }
      }
    try {
      pts.playing = true
      // 暂停期间时钟照走（视频帧不回写时钟），恢复时把锚点拉回上一帧，否则整段当"落后"快放
      lastFrameTs?.let { pts.update(it) }
      // 步进轮不碰音频设备：pause() 的 stop() 不丢弃已排队的 PCM
      if (!stepMode) surfaceContext?.resume()
      var sendingPacket = 0
      while (isPlaying()) {
        // 节流
        if (sendingPacket > 3 || (frames.map { it.value.size }.minOrNull() ?: 0) > 3) {
          delay(1.milliseconds)
          continue
        }
        val packet =
          try {
            withTimeout(READ_STALL_MS.milliseconds) { getPacket(pts.streams.values) }
          } catch (_: TimeoutCancellationException) {
            // [READ_STALL_MS] 没有读到包：卡住。continue 让循环回到 isPlaying()，pause 靠它退出
            if (!isReadTimeout) {
              isReadTimeout = true
              onEvent(Event.ReadTimeout)
            }
            continue
          }
        // 如果换pts了就丢掉当前packet
        if (!isPacketValid()) {
          packet?.close()
          break
        }
        // 下载，跳过解码
        if (pts.streams.isEmpty()) {
          packet?.close()
          if (packet == null) break else continue
        }
        if (packet != null) {
          sendingPacket++
          // 卡住之后读到第一个包就是恢复
          if (isReadTimeout) {
            isReadTimeout = false
            onEvent(Event.ReadTimeoutResume)
          }
        }
        // 读到 EOF 时把所有 codec 冲刷一遍，否则只送当前包对应的那个
        val flushCodecs =
          if (packet == null) {
            codecs.values
          } else {
            listOf(
              codecs[packet.streamIndex]
                ?: AvCodec(
                  pts.streams.values.first { it.index == packet.streamIndex },
                  hardware,
                  surfaceContext?.hardwareOutput,
                ).also { codecs[packet.streamIndex] = it },
            )
          }
        flushCodecs.forEach { codec ->
          val lastJob = decodeJobs[codec.streamIndex]
          decodeJobs[codec.streamIndex] =
            decodeScope.launch {
              packet.use { packet ->
                // 先等codec把上一个作业提交完
                lastJob?.join()
                // 循环直到投递成功
                while (isPacketValid()) {
                  if (!isPlaying() && packet == null) break
                  val (accepted, decoded) = codec.sendPacketAndGetFrames(packet)
                  if (!isPacketValid() || decoded == null) {
                    decoded?.forEach { it.close() }
                    break
                  }
                  frames.getOrPut(codec.streamIndex) { arrayListOf() }.addAll(decoded)
                  if (accepted && packet != null) {
                    sendingPacket--
                    break
                  }
                  delay(1.milliseconds)
                }
              }
            }
        }
        // EOF跳出循环
        if (packet == null) {
          // 等取帧结束
          decodeJobs.values.joinAll()
          // 等frame消费完
          while (frames.values.sumOf { it.size } > 0 && isPlaying()) {
            delay(1.milliseconds)
          }
          break
        }
      }
    } finally {
      isActive = false
      pts.playing = false
      if (pts == this@FFPlayer.pts) onEvent(Event.Frame(null))
    }
    playJobs.joinAll()
  }

  suspend fun closeAsync() =
    withContext(playerDispatcher) {
      pts = null
      pause()
      decodeJobs.values.joinAll()
      // 解码作业不在轮次的作业树里，播放器关掉了就顺手收掉这个域，别留下孤儿
      decodeScope.cancel()
      // 必须排在 [pause] 之后：此刻留在队列里的都是没人要的
      frames.values.forEach { list -> list.forEach { it.close() } }
      frames.clear()
      // 必须排在 super.closeDeferred 之前：abort 要在 formatDispatcher 之外发出并 join 到读作业死透
      resetChannel()
      super.closeDeferred().join()
      // 各 codec 有自己的归属 dispatcher，要等归还真的落地
      codecs
        .map { async { it.value.closeDeferred().join() } }
        .awaitAll()
      codecs.clear()
    }
}
