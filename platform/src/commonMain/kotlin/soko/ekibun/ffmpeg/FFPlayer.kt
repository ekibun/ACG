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
import kotlin.time.Duration.Companion.milliseconds

/**
 * **seek 一定会回到关键帧** —— 容器索引的物理限制，不是靠调 `min_ts`/`max_ts` 窗口能绕开的
 * （`SeekWindowSemanticsTest` 钉着）。所以「平滑」要分两步（照 ffplay 的做法），**两者缺一不可**：
 * 落关键帧后立刻把主时钟拨到目标（`PTS(relate = ts)`），**再丢掉**关键帧→目标之间的帧
 * （[resumeImpl] 的 `resyncTo`）。逐项对照见 `silent-failures.md`。
 *
 * 播放入口只能在 [playerDispatcher] 上调：读 [pts] 与随后的检查必须落在同一段不被打断的序列里
 * （见那条字段的文档）。
 */
class FFPlayer(
  url: String,
  io: AvIO.Handler,
  val surfaceContext: AvSurfaceContext? = null,
  val onEvent: (Event) -> Unit,
) : AvFormat(url, io) {
  /**
   * 播放侧状态上报：[Frame] 每送显一帧回调它的 PTS（**播放结束以 `pts = null` 回调**）；
   * [ReadTimeout] / [ReadTimeoutResume] 是取包超时的开始与恢复 —— 网络停住与「播完了」
   * 靠这对事件才分得开（见 [resumeImpl] 的取包循环）。
   */
  sealed interface Event {
    data class Frame(
      val pts: Long?,
    ) : Event

    data object ReadTimeout : Event

    data object ReadTimeoutResume : Event
  }

  /**
   * 播放基准点。
   *
   * 注意 `relate` 是可变的：音频帧会把它校正到**解码后实际的重采样时间戳**，所以它不等于 seek 时
   * 给定的 `ts`。seek 后「画面上应该出现在哪一刻」由 [resumeImpl] 的 `resyncTo` 入参表达 ——
   * 那是**一轮一次**的起跑参数，不是本对象的持续状态。
   */
  class PTS(
    val streams: Map<Int, AvStream>,
    private var relate: Long = 0,
  ) {
    // 单调时钟（[monoTimeMs]）：主时钟只用来测「从基准点过了多久」，墙钟会被
    // NTP 校时 / 手动改时间拨动 —— 突跳一格，画面就是快进一截或干等一截。
    private var absolute: Long = monoTimeMs()
    var playing: Boolean = false

    fun update(relate: Long) {
      this.relate = relate
      absolute = monoTimeMs()
    }

    fun now(speedRatio: Float): Long = ((monoTimeMs() - absolute) * speedRatio * AV_TIME_BASE / 1000).toLong() + relate
  }

  private var pts: PTS? = null

  /**
   * 播放侧的归属线程 —— 播放入口（[play] / [seekTo] / [stepForward] / [stepBack] / [pause] /
   * [closeAsync]）**只在它上面调**：读 [pts] 与随后的检查必须落在同一段不被打断的序列里。
   *
   * 本类**从不 close 它**（全进程共用），所以 [ThreadDispatcher] 那道「关掉后当场抛」的拦截
   * 平时不触发 —— 要的就是它顺带提供的归属线程语义。
   */
  private val playerDispatcher by lazy { ThreadDispatcher("ffplayer") }

  /**
   * 视频帧相对主时钟的最大可丢弃跨度（对应 `AV_NOSYNC_THRESHOLD`）。
   *
   * seek 后主时钟被拨到目标时间，关键帧到目标之间这段就是"落后主时钟"的部分。
   * 只有在阈值内的落后才丢：否则遇到时间戳异常（如流开头损坏、
   * `best_effort_timestamp` 为 NOPTS）会把整段画面吞掉。
   */
  private val maxResyncDropDistance = 10 * AV_TIME_BASE.toLong()

  /**
   * 开一轮独占播放：先把上一轮作废、等它收干净，再让自己这一轮开跑。
   *
   * 硬约束是 native 侧**每种流只有一块输出缓冲**（`SWContext::videoBuffer`）⇒ 同一时刻只允许
   * 一轮在飞（取舍与踩坑见 `silent-failures.md` 的「两轮播放同时在飞会串帧」段）。
   *
   * 语义是**后到者顶掉先到者**：[pause] 把上一轮标记作废并 join 到它真的收干净；这段时间里若有更晚的
   * 调用进来会换上自己的 [PTS] 对象，本次醒来发现对象变了就放弃 —— 「本轮还作不作数」**只看对象身份**。
   */
  private suspend fun takeOverPlayback(): Boolean {
    val before = pts
    pause()
    return pts === before
  }

  /**
   * 单帧步进用的两个时间戳：画面上那一帧（[lastFrameTs]）、它的前一帧（[prevFrameTs]）。
   *
   * 退帧不能「seek 到当前帧再往回挪一点」：收敛的判据是「第一个时间戳 >= 目标的帧」，目标设成当前帧
   * 的话收敛完显示的仍是当前帧。所以前一帧只能自己记：顺序播放（含 [stepForward]）时是上一次的当前帧；
   * 跳转之后是收敛期被丢掉那些帧里的**最后一帧**；落点恰好在关键帧上时一帧都没丢 ⇒ [prevFrameTs]
   * 置空，由 [stepBack] 再空探一次补回来。
   */
  private var lastFrameTs: Long? = null
  private var prevFrameTs: Long? = null

  /**
   * 最近一次真的看到「连续两帧」时量到的**帧间隔**（微秒）。[stepBack] 空探那一跳靠它定步长，
   * 只在拿得准的时候更新（见 [resumeImpl]）。还不知道时空探只能退回 1µs —— 老行为，会被粗时基的
   * 半格吞掉（见 [stepBack]）。
   */
  private var frameIntervalUs: Long? = null

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
    // 「本来在播吗」必须在 [takeOverPlayback] 之前读：它会把上一轮作废（`pts.playing = false`），
    // 之后再读就永远是假，跳转完就不会接着播了。
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
  ) = withContext(playerDispatcher) {
    val oldPts = pts ?: throw Exception("no pts data")
    val newPts = PTS(oldPts.streams, ts)
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
    surfaceContext?.stop()
    // seek：即使传了 stream，mp4/mov 也只会落关键帧（见类注释）。
    // 落点靠下面的丢帧收敛补齐。
    if (pts != newPts) return@withContext
    super.seekTo(ts, stream, minTs, maxTs, flags)
    if (pts != newPts) return@withContext
    when {
      // 视频轨在：单帧步进轮（暂停中 seek）靠「下一帧画面」停住，正常续播轮
      // 带上 resyncTo 做丢帧收敛 —— 两条都是视频路径的原语义。
      newPts.streams.containsKey(AVMediaType.VIDEO) ->
        resume(stopOnNextFrame = !resumeAfter, resyncTo = ts)
      // 纯音频没有「下一帧画面」可言（onNextFrame 的信号只由视频帧给出，
      // stopOnNextFrame 会一路解到 EOF 才停）：本来在播就带 resyncTo 继续
      // 播（丢帧收敛对音频同样成立，见 resumeImpl），暂停中则停在原地。
      resumeAfter -> resume(resyncTo = ts)
    }
  }

  /**
   * 从当前位置开始播：起本轮播放作业。**本函数不开闸** —— 开闸（[takeOverPlayback]）由调用它的入口
   * 负责（「同一时刻只允许一段播放」是不变量，理由见 [takeOverPlayback] 的文档）。
   *
   * **不换 [PTS] 对象**：本函数不换位置，在飞的 `decodeJob` 与它手里的包都还在当前位置上。换对象会把它们
   * 判成「过时」丢掉，而包已经离开 `av_read_frame`、补不回来 —— 丢一个就少一个参考帧，其后一路错到下一个
   * IDR（症状是花屏加跳帧）。换位置的路径才换对象。
   *
   * [resyncTo] 是**本轮起跑要收敛到的目标时间戳**，一代一轮的一次性参数，所以走参数通道、不存在 [PTS]
   * 上 —— 存进去就得配「取走即清」，而 [seekImpl] 里任何一处守卫提前返回都会把它留在**当前活跃**的
   * [PTS] 上，下一次 [resume] 醒来就会凭空从旧位置收敛一次。顺序播放 / 步进传 null。
   */
  suspend fun resume(
    stopOnNextFrame: Boolean = false,
    resyncTo: Long? = null,
  ) = withContext(playerDispatcher) {
    if (stopOnNextFrame) {
      val hitFrame =
        suspendCancellableCoroutine<Boolean> {
          playingJob = async(playerDispatcher) { resumeImpl(it, resyncTo) }
        }
      if (hitFrame) pause()
    } else {
      playingJob = async(playerDispatcher) { resumeImpl(null, resyncTo) }
    }
  }

  /**
   * 前进一帧：解出并显示下一帧，然后停住。
   *
   * 机制是现成的 —— [resume] 的 `stopOnNextFrame`（视频不等主时钟、送显后立刻 `pause()`）。
   * 整段走 [takeOverPlayback]：它先把上一轮作废并 join 干净，再开这一轮。**并发进来的第二次调用是
   * 顶掉而不是排队**（理由见 [takeOverPlayback] 的文档）。
   */
  suspend fun stepForward() =
    withContext(playerDispatcher) {
      val p = pts ?: return@withContext
      // 纯音频没有「下一帧画面」，而且 [resume] 的停止信号只由视频帧给出 ——
      // 不拦这一下会一路解到 EOF 才回来（seekTo 里也是同一道判断）。
      if (!p.streams.containsKey(AVMediaType.VIDEO)) return@withContext
      if (!takeOverPlayback()) return@withContext
      resume(stopOnNextFrame = true)
    }

  /**
   * 后退一帧：跳回画面上那一帧的前一帧，然后停住。目标是 [prevFrameTs]，收敛会丢掉所有早于目标的帧，
   * 所以落点正是它，而不是「目标附近的关键帧」。前一帧无从得知时先空探一次，步长怎么取见下面那行注释。
   *
   * @return 是否真的移动了 —— 还没显示过任何帧、已在文件第一帧上、或空探学不到前一帧时都为 `false`。
   */
  suspend fun stepBack(): Boolean =
    withContext(playerDispatcher) {
      val current = lastFrameTs ?: return@withContext false
      if (!takeOverPlayback()) return@withContext false
      if (prevFrameTs == null) {
        // 文件开头取 max：探不出结果，下面统一返回 false。
        // 步长取**帧间隔的 3/4**（[frameIntervalUs]）：半格 ≤ 间隔/2 < 3/4 间隔 < 间隔，天然落在这段
        // 区间里，用不着知道流的 time_base。不知道帧间隔时只能退回 1µs（老行为），那种容器上照样探不出来。
        val eps = frameIntervalUs?.let { it * 3 / 4 } ?: 1
        seekImpl(maxOf(current - eps, 0), resumeAfter = false)
      }
      val target = prevFrameTs ?: return@withContext false
      seekImpl(target, resumeAfter = false)
      true
    }

  private val codecs = HashMap<Int, AvCodec>()
  private val frames = HashMap<Int, ArrayList<AvFrame>>()

  private suspend fun resumeImpl(
    onNextFrame: CancellableContinuation<Boolean>?,
    /** 本轮起跑要收敛到的目标时间戳（seek 后才有）。见 [resume]。 */
    resyncTo: Long?,
  ) = withContext(playerDispatcher) {
    val pts = pts ?: return@withContext
    // [isPlaying] **必须**带上「对象没换」这一条：换位置的路径（[play] / [seekImpl]）会换上自己的 [PTS]
    // 对象，于是 `pts === 当前 pts` 就是「本轮还作数吗」。不带它，被 seek 作废的那一轮会在下一轮复活 ——
    // 新一轮把 `pts.playing` 置 true 时，上一轮那个作业会补一次 flushFrame，而缓冲里已是这一轮的内容。
    val isPlaying = { pts == this@FFPlayer.pts && pts.playing }
    // 本轮是不是「单帧步进」轮。判据只能取**轮次级**的：看 `onNextFrame?.isActive`
    // 不行 —— 那个信号在视频帧刚送显的那一刻就翻了，而轮次要等 `pause()` 落地才停，
    // 中间这段窗口里音频帧会漏出去（实测每步恰好漏 1 帧进声卡）。
    val stepMode = onNextFrame != null
    // seek 后要把画面从关键帧「快进」到目标时间：主时钟先停在目标时间，所有 PTS 落后于它的视频帧
    // 解码后立刻丢弃（对应 ffplay 的 frame_drops_early），直到第一帧追上目标才撤掉这个标记、回到
    // 正常同步。目标来自入参（见 [resume]）—— 一代一轮，不存 [PTS] 上，免得守卫提前返回时留残留。
    var lastDropped: Long? = null
    var converging = resyncTo != null
    val playJobs =
      pts.streams.map { (codecType, stream) ->
        async(playerDispatcher) {
          var lastUpdateJob: Job? = null
          while (isPlaying()) {
            val frame =
              // 只看「有没有人在用」，不比对是谁（见 [AvFrame.processing]）：判据问的是占用状态，不是
              // 「哪个 PTS 标的」。上一轮 `updateJob` 可能还没收尾 —— 它不在 [playJobs] 里，join 等不到它，
              // 此时标记仍是上一轮的对象，判 `!= pts` 就会把这种帧取走、与本轮作业撞同一帧。
              frames[stream.index]?.firstOrNull { frame ->
                frame.processing == null
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
                // 步进轮里的音频**整轮**静音（见 [stepMode]）：既不解码后送显，也不送声卡。
                val muted = stepMode && codecType == AVMediaType.AUDIO
                // **先等上一帧送显完，再写这一帧** —— 顺序不能反。native 每种流只有**一块**输出缓冲
                // （`SWContext::videoBuffer`），`postFrame` 整体改写它、`flushFrame` 才是抄走的那一步。
                // 顺序反了就是**上一帧显示成本帧的像素**（实测：204 帧里 180 帧不符，见 silent-failures.md）。
                lastUpdate?.join()
                if (!isPlaying()) return@updateJob
                if (!muted) surfaceContext?.postFrame(codecType, frame)
                // seek 后的丢帧收敛（ffplay frame_drops_early）：`diff = dpts - master_clock`，主时钟 ==
                // resyncTo，`|diff| < AV_NOSYNC_THRESHOLD && diff < 0` ⇒ 解码后立刻丢。**必须排在送显之前、
                // 且音频也要丢**（落点是容器级的）—— 两条约束与实测见 `silent-failures.md`。
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
                if (codecType == AVMediaType.VIDEO && onNextFrame?.isActive != true) {
                  while (frame.timeStamp > pts.now(surfaceContext?.speedRatio ?: 1f)) {
                    delay(1.milliseconds)
                    if (!isPlaying()) return@updateJob
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
                  // 单帧步进的位置记录（见 [prevFrameTs]）：本轮收敛过，前一帧就用
                  // 丢帧结果补；没收敛（顺序播放）时，前一帧就是上一次的当前帧。
                  prevFrameTs = if (converging) lastDropped else lastFrameTs
                  converging = false
                  lastFrameTs = frame.timeStamp
                  // 帧间隔只在「真看到连续两帧」时可信：上面刚把 [prevFrameTs] 摆到
                  // 当前帧的前一帧，差值就是间隔（[frameIntervalUs] 的用途）。
                  val prev = prevFrameTs
                  if (prev != null && frame.timeStamp > prev) {
                    frameIntervalUs = frame.timeStamp - prev
                  }
                  if (onNextFrame?.isActive == true) {
                    pts.update(frame.timeStamp)
                    onNextFrame.resumeWith(Result.success(true))
                  }
                }
                onEvent(Event.Frame(frame.timeStamp))
              }.also { job ->
                job.invokeOnCompletion {
                  // 轮次在「已经取到帧、还没显示」时停住是常态（单帧步进的每一轮都是）：这种帧**不能出队**，
                  // 否则它再也不会被显示。帧的归还也在这里收口（native 侧把所有权交给了 Java）：出队就归还，
                  // 「队列里已经没了」同样算出队 —— 判据只能用 contains，**别拿 remove() 的返回值当判据**（踩过）。
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
      // 步进轮不碰音频设备：真实实现里 `resume()` 就是 `line.start()`，而 `pause()` 的
      // `line.stop()` **不丢弃**已排队的 PCM —— 每步 start 一次，就把上一次播放留在
      // 缓冲里的那一段声音原样放出来（实测每步各一次 resume）。
      if (!stepMode) surfaceContext?.resume()
      var sendingPacket = 0
      var isReadTimeout = false
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
            // 不额外退避：上面那个 `withTimeout` 本身就是节拍（100 ms 一轮）。超时**不会**重进
            // native —— 通道还活着时 [AvFormat.getPacket] 走 `packetChannel` 的非空分支，那条读
            // 作业始终是同一条、park 在 `getPacketNative` 里，一轮的代价只是消费侧
            // `for (v in channel)` 空等到超时。
            continue
          }
        if (isReadTimeout) onEvent(Event.ReadTimeoutResume)
        isReadTimeout = false

        if (packet == null) {
          if (frames.map { it.value.size }.sum() == 0) {
            break
          }
          delay(100.milliseconds)
          continue
        }
        if (this@FFPlayer.pts != pts) {
          // 轮次换了（seek / play）：包属于**旧位置**，就地归还。
          // 不是「从码流里删掉一帧」—— 新位置会从关键帧把这一段重读出来（见 [seekImpl]），
          // 而这个包若喂下去只会让新位置的解码器吃到错参考解。
          packet.close()
          break
        }
        // 只是暂停（对象没换、位置没变）：**不停在这里**，照常把包送进解码器入队 —— 这就是暂停期间的
        // 「预读缓存」。判据只取对象身份、不含 `pts.playing`，与 [isPlaying] 正好相反：那边判假要停、这边
        // 判真要继续。下载器模式（streams 为空）下这个包没有消费方，必须就地归还（已离开 demuxer）。
        if (pts.streams.isEmpty()) {
          packet.close()
          continue
        }
        // 计数只在这一处增：它记的是**投出去还没回来**的解码作业数（下面的 async 里减）。
        // 放在判空之后 —— 下载器模式没有解码作业，去增再减只是白做。
        sendingPacket++
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
      if (pts == this@FFPlayer.pts) onEvent(Event.Frame(null))
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
      // 作废读轮，**必须排在 [super.closeDeferred] 之前**：它要在 formatDispatcher 之外就地发出
      // io.abort()（那根线程正被读作业的 runBlocking 占死，排队就排到自己要叫醒的东西后面），
      // 并 join 到读作业死透 —— 后者保证 [AvFormat.releaseImpl] 销毁 native 上下文时没有读作业
      // 还拿着它。理由与逐条判据见 [AvFormat.resetChannel] 的 KDoc。
      resetChannel()
      super.closeDeferred().join()
      // 每个 codec 有自己的归属 dispatcher：这里要等**归还真的落地**，
      // 而不是投出去就返回 —— 所以 await 各自的 closeDeferred()。
      codecs
        .map { async(playerDispatcher) { it.value.closeDeferred().join() } }
        .awaitAll()
      codecs.clear()
    }
}
