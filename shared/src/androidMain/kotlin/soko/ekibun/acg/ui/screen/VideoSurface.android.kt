package soko.ekibun.acg.ui.screen

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import soko.ekibun.acg.player.AndroidPlayback
import soko.ekibun.acg.player.Playback

@Composable
actual fun VideoSurface(
  onPlayback: (Playback?) -> Unit,
  onFrame: (Long?) -> Unit,
  modifier: Modifier,
) {
  val currentOnPlayback by rememberUpdatedState(onPlayback)
  val currentOnFrame by rememberUpdatedState(onFrame)

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
              currentOnPlayback(AndroidPlayback(p0, currentOnFrame))
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
