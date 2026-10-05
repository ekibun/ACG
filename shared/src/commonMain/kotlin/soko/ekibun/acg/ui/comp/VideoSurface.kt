package soko.ekibun.acg.ui.comp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import soko.ekibun.acg.player.Playback
import soko.ekibun.ffmpeg.AvPlayback.Event

/**
 * [onPlayback] 播放器就绪时回调（Android 在 SurfaceTexture 创建后，桌面端则立即创建），界面销毁时
 * 以 null 回调；[onFrame] 每显示一帧时回调当前 PTS，播放结束时以 null 回调；[onEvent] 取包超时
 * 这类播放侧状态翻转时回调（见 [Event]）。
 */
@Composable
expect fun VideoSurface(
  onPlayback: (Playback?) -> Unit,
  onFrame: (Long?) -> Unit,
  onEvent: (Event) -> Unit,
  modifier: Modifier = Modifier,
)
