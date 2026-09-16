package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import soko.ekibun.acg.player.DesktopPlayback
import soko.ekibun.acg.player.Playback

@Composable
actual fun VideoSurface(
  onPlayback: (Playback?) -> Unit,
  onFrame: (Long?) -> Unit,
  modifier: Modifier,
) {
  val currentOnPlayback by rememberUpdatedState(onPlayback)
  val currentOnFrame by rememberUpdatedState(onFrame)
  // 桌面端不需要外部 surface，直接创建播放器，解码出的帧通过 image 暴露给 Compose
  val playback = remember { DesktopPlayback { currentOnFrame(it) } }

  DisposableEffect(playback) {
    currentOnPlayback(playback)
    onDispose {
      currentOnPlayback(null)
      playback.close()
    }
  }

  Box(modifier) {
    playback.image?.let {
      Image(
        bitmap = it,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
      )
    }
  }
}
