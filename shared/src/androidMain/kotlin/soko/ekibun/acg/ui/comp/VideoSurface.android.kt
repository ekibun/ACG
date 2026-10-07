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
import soko.ekibun.acg.player.AndroidSurfaceContext
import soko.ekibun.acg.player.SurfaceContext

@Composable
actual fun VideoSurface(
  onSurfaceContext: (SurfaceContext?) -> Unit,
  modifier: Modifier,
) {
  val currentOnSurfaceContext by rememberUpdatedState(onSurfaceContext)
  // 播放器在 factory 的 surfaceTexture 回调里才建得出来（TextureView 那侧
  // onSurfaceTextureDestroyed 恒 false、不代放，所有权归回调方），这里用状态
  // 把它接出来，销毁时按 common 契约（见 VideoSurface.kt）回调 null 并 close
  // —— 不收的话，每次离开本页都漏一个 AudioTrack 会话 + Surface。
  var surfaceContext by remember { mutableStateOf<AndroidSurfaceContext?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      surfaceContext?.let { p ->
        currentOnSurfaceContext(null)
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
              val created = AndroidSurfaceContext(p0)
              surfaceContext = created
              currentOnSurfaceContext(created)
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
