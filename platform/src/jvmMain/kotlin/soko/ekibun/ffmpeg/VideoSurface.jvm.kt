package soko.ekibun.ffmpeg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun VideoSurface(
  onSurfaceContext: (SurfaceContext?) -> Unit,
  modifier: Modifier,
) {
  val currentOnSurfaceContext by rememberUpdatedState(onSurfaceContext)
  // 桌面端不需要外部 surface，直接创建播放器，解码出的帧通过 frame 暴露给 Compose
  val surfaceContext = remember { DesktopSurfaceContext() }
  val window = LocalAwtWindow.current

  // 零拷贝输出端要在播放器建立之前就绪，先等画布把 Skiko 的渲染设备建出来。
  LaunchedEffect(surfaceContext, window) {
    if (window != null) surfaceContext.attachWindow(window)
  }

  DisposableEffect(surfaceContext) {
    currentOnSurfaceContext(surfaceContext)
    onDispose {
      currentOnSurfaceContext(null)
      surfaceContext.close()
    }
  }

  Box(modifier) {
    Canvas(Modifier.fillMaxSize()) {
      // 读一下当订阅：第一帧到达时才需要开始画（`Canvas` 里的读取负责后续每帧的重绘）
      if (surfaceContext.frame > 0) {
        drawIntoCanvas { canvas ->
          surfaceContext.drawFrame(canvas.skiaCanvas, size.width, size.height)
        }
      }
    }
  }
}
