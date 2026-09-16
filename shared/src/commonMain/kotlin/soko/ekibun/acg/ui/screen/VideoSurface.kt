package soko.ekibun.acg.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import soko.ekibun.acg.player.Playback

/**
 * 平台相关的视频输出区域。
 *
 * @param onPlayback 播放器就绪时回调（Android 在 SurfaceTexture 创建后，桌面端则立即创建）；
 *   界面销毁时以 null 回调。
 * @param onFrame 每显示一帧时回调当前 PTS，播放结束时以 null 回调。
 */
@Composable
expect fun VideoSurface(
  onPlayback: (Playback?) -> Unit,
  onFrame: (Long?) -> Unit,
  modifier: Modifier = Modifier,
)
