package soko.ekibun.acg.ui.comp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import soko.ekibun.acg.player.SurfaceContext

/**
 * [onSurfaceContext] 播放器就绪时回调（Android 在 SurfaceTexture 创建后，桌面端则立即创建），界面销毁时
 * 以 null 回调。帧 PTS 与取包超时经 `FFPlayer.onEvent` 上报，不走这里。
 */
@Composable
expect fun VideoSurface(
  onSurfaceContext: (SurfaceContext?) -> Unit,
  modifier: Modifier = Modifier,
)
