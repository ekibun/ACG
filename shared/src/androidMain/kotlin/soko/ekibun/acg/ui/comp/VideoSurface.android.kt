package soko.ekibun.acg.ui.comp

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import soko.ekibun.acg.player.AndroidPlayback
import soko.ekibun.acg.player.Playback
import soko.ekibun.ffmpeg.AvPlayback

@Composable
actual fun VideoSurface(
  onPlayback: (Playback?) -> Unit,
  onFrame: (Long?) -> Unit,
  onEvent: (AvPlayback.Event) -> Unit,
  modifier: Modifier,
) {
  val currentOnPlayback by rememberUpdatedState(onPlayback)
  val currentOnFrame by rememberUpdatedState(onFrame)
  val currentOnEvent by rememberUpdatedState(onEvent)
  // 播放器在 factory 的 surfaceTexture 回调里才建得出来（TextureView 那侧
  // onSurfaceTextureDestroyed 恒 false、不代放，所有权归回调方），这里用状态
  // 把它接出来，销毁时按 common 契约（见 VideoSurface.kt）回调 null 并 close
  // —— 不收的话，每次离开本页都漏一个 AudioTrack 会话 + Surface。
  var playback by remember { mutableStateOf<AndroidPlayback?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      playback?.let { p ->
        currentOnPlayback(null)
        p.close()
      }
    }
  }

  AndroidView(
    modifier = modifier,
    factory = {
      TextureView(it).also { view ->
        view.surfaceTextureListener =
          object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
              p0: SurfaceTexture,
              p1: Int,
              p2: Int,
            ) {
              val created = AndroidPlayback(p0, currentOnFrame, currentOnEvent)
              playback = created
              currentOnPlayback(created)
            }

            override fun onSurfaceTextureSizeChanged(
              p0: SurfaceTexture,
              videoWidth: Int,
              videoHeight: Int,
            ) {
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean = false

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {}
          }
      }
    },
  )
}
