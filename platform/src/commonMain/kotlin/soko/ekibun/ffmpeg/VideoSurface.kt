package soko.ekibun.ffmpeg

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 播放器就绪时以非 null 回调 [onSurfaceContext]，界面销毁时以 null 回调（Android 在
 * SurfaceTexture 创建后，桌面端立即创建）。帧 PTS 与取包超时经 `FFPlayer.onEvent` 上报，不走这里。
 */
@Composable
expect fun VideoSurface(
  onSurfaceContext: (SurfaceContext?) -> Unit,
  modifier: Modifier = Modifier,
)
