package soko.ekibun.acg.ui.comp.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import soko.ekibun.acg.player.IOHandler
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.FFPlayer
import soko.ekibun.ffmpeg.SurfaceContext

/** `Opening` 之后才可能有帧。 */
enum class PlayerStatus { Idle, Opening, Ready, Ended, Error }

/**
 * 被跳过的广告断点。[from] 是断点前最后一帧（回退落点），[to] 是断点后第一帧（撤销落点）。
 */
data class AdSkip(
  val from: Long,
  val to: Long,
  /** 已经回到广告里，按钮是「撤销」。 */
  val reverted: Boolean = false,
)

/**
 * 播放状态快照。位置不在这里：[PlayerController.positionUs] 每帧都动，混进来会让整份快照按帧频率发。
 */
data class PlayerState(
  val status: PlayerStatus = PlayerStatus.Idle,
  /** 播放意图，不是「正在出帧」。 */
  val playWhenReady: Boolean = false,
  /** 总时长（声明轴微秒）；0 表示未知。 */
  val durationUs: Long = 0L,
  /** 读包卡住（`ReadTimeout` 到 `ReadTimeoutResume` 之间）。 */
  val buffering: Boolean = false,
  val adSkip: AdSkip? = null,
)

/**
 * 播放器状态持有者：把 [FFPlayer] 的挂起命令与帧事件折成两条流。
 *
 * 地址走 [open] 命令、不进组合参数，重开同一地址再调一次即可。[open] 可以早于画面：
 * `FFPlayer` 构造时就要 surface，地址先存着，[attachSurface] 到了再开。
 */
class PlayerController {
  private val _state = MutableStateFlow(PlayerState())
  val state: StateFlow<PlayerState> = _state.asStateFlow()

  private val _positionUs = MutableStateFlow(0L)
  val positionUs: StateFlow<Long> = _positionUs.asStateFlow()

  private var player: FFPlayer? = null
  private var surface: SurfaceContext? = null
  private var pendingUrl: String? = null

  /**
   * 控制器的作业域，与组合无关：`onDispose` 之后收场还要跑，而 `FFPlayer.play()` 挂到整轮结束又不能等。
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** 记住画面，并打开待播地址。画面消失不还播放器，归还归 [close]。 */
  suspend fun attachSurface(context: SurfaceContext) {
    surface = context
    val url = pendingUrl ?: return
    pendingUrl = null
    openWith(url, context)
  }

  /** 换源或重开：同一地址再调一次就是重连。 */
  suspend fun open(url: String) {
    val context = surface
    if (context == null) {
      pendingUrl = url
      return
    }
    pendingUrl = null
    openWith(url, context)
  }

  suspend fun togglePlay() {
    val current = player ?: return
    val wantPlay = !_state.value.playWhenReady
    if (wantPlay) current.resume() else current.pause()
    _state.update { it.copy(playWhenReady = wantPlay) }
  }

  suspend fun seekTo(positionUs: Long) {
    player?.seekTo(positionUs)
    _positionUs.value = positionUs
  }

  suspend fun stepForward() {
    player?.stepForward()
  }

  suspend fun stepBack() {
    player?.stepBack()
  }

  /** 广告提示的动作：跳过的那段回退进去，已回退的再推出来。 */
  suspend fun toggleAdSkip() {
    val notice = _state.value.adSkip ?: return
    val target = player ?: return
    // 先翻状态再动播放器：seek 落地前按钮就得换向
    _state.update { it.copy(adSkip = notice.copy(reverted = !notice.reverted)) }
    if (notice.reverted) target.seekTo(notice.to) else target.reverseAd(notice.from)
  }

  /** 只清自己这一条：同一断点的提示可能已经被下一次跳过替换掉了。 */
  fun clearAdSkip(notice: AdSkip) {
    _state.update { if (it.adSkip === notice) it.copy(adSkip = null) else it }
  }

  /** 归还播放器。`closeAsync()` 是挂起的，而组合作用域在 `onDispose` 前已被取消，所以投到 [scope]。 */
  fun close() {
    val closing = player
    player = null
    pendingUrl = null
    _state.value = PlayerState()
    _positionUs.value = 0L
    if (closing != null) scope.launch { closing.closeAsync() }
  }

  private suspend fun openWith(
    url: String,
    context: SurfaceContext,
  ) {
    player?.closeAsync()
    player = null
    _positionUs.value = 0L
    _state.value = PlayerState(status = PlayerStatus.Opening, playWhenReady = true)
    try {
      val prepared = preparePlayer(url, context, ::onPlayerEvent)
      player = prepared.player
      _state.update { it.copy(status = PlayerStatus.Ready, durationUs = prepared.durationUs) }
      // 起播不等它：play() 挂到整轮结束
      scope.launch { startRound(prepared) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      e.printStackTrace()
      _state.update { it.copy(status = PlayerStatus.Error, playWhenReady = false) }
    }
  }

  private suspend fun startRound(prepared: PreparedPlayer) {
    try {
      prepared.player.play(prepared.streams, 0)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      e.printStackTrace()
      _state.update { it.copy(status = PlayerStatus.Error, playWhenReady = false) }
    }
  }

  private fun onPlayerEvent(event: FFPlayer.Event) {
    when (event) {
      is FFPlayer.Event.Frame -> {
        val pts = event.pts
        if (pts == null) {
          // 本轮收场：收掉扫光，别让卡住的提示留在界面上
          _state.update { it.copy(status = PlayerStatus.Ended, buffering = false) }
        } else {
          _positionUs.value = pts
          _state.update { if (it.status == PlayerStatus.Ended) it.copy(status = PlayerStatus.Ready) else it }
        }
      }
      FFPlayer.Event.ReadTimeout -> _state.update { it.copy(buffering = true) }
      FFPlayer.Event.ReadTimeoutResume -> _state.update { it.copy(buffering = false) }
      is FFPlayer.Event.AdSkipped -> _state.update { it.copy(adSkip = AdSkip(from = event.from, to = event.to)) }
    }
  }
}

/** 与组合同生命周期：离开时归还播放器。 */
@Composable
fun rememberPlayerController(): PlayerController {
  val controller = remember { PlayerController() }
  DisposableEffect(controller) {
    onDispose { controller.close() }
  }
  return controller
}

/** 建好的播放器、它的两路流与总时长（微秒）。[streams] 要留给 [FFPlayer.play]。 */
private class PreparedPlayer(
  val player: FFPlayer,
  val streams: Map<Int, AvStream>,
  val durationUs: Long,
)

/** 建播放器、挑出视频 / 音频两路流并算总时长。不起播。 */
private suspend fun preparePlayer(
  url: String,
  surface: SurfaceContext,
  onEvent: (FFPlayer.Event) -> Unit,
): PreparedPlayer {
  val player = FFPlayer(url, IOHandler(), surface, onEvent = onEvent)
  val streams = player.getStreams()
  val ptsStreams = HashMap<Int, AvStream>()
  for (type in arrayOf(AVMediaType.VIDEO, AVMediaType.AUDIO)) {
    streams.firstOrNull { it.codecType == type }?.let { ptsStreams[type] = it }
  }
  // 总时长：优先取格式层，拿不到退回流时长；两路都没有就按未知处理
  val durationUs =
    player.getDurationUs().takeIf { it > 0 }
      ?: (ptsStreams.values.maxOfOrNull { it.duration } ?: 0L)
  return PreparedPlayer(player, ptsStreams, durationUs)
}
