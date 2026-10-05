package soko.ekibun.acg.ui.comp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import soko.ekibun.acg.player.DesktopPlayback
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
  // 桌面端不需要外部 surface，直接创建播放器，解码出的帧通过 frame 暴露给 Compose
  val playback = remember { DesktopPlayback({ currentOnFrame(it) }, { currentOnEvent(it) }) }

  DisposableEffect(playback) {
    currentOnPlayback(playback)
    onDispose {
      currentOnPlayback(null)
      playback.close()
    }
  }

  Box(modifier) {
    // 读一下当订阅：第一帧到达时才需要开始画（`Canvas` 里的读取负责后续每帧的重绘）
    if (playback.frame != null) {
      // 传进 `Canvas` 的位图是**复用**的（内容每帧被覆写，见 DesktopPlayback 的位图池说明），
      // 所以这里每次绘制都从它拷一份快照再画 —— 拷贝由 `close()` 让 Skia 同步释放，不进 Cleaner。
      // 每帧只有一次 memcpy（写）+ 一次整帧拷贝（读），**没有大对象分配** ⇒ RSS 稳在百 MB 量级。
      Canvas(Modifier.fillMaxSize()) {
        val frame = playback.frame ?: return@Canvas
        val image = Image.makeFromBitmap(frame)
        try {
          drawIntoCanvas { canvas ->
            // 拉满整个盒子（Compose 的 `Image(contentScale = FillBounds)` 就是这个效果）：
            canvas.skiaCanvas.drawImageRect(image, Rect(0f, 0f, size.width, size.height))
          }
        } finally {
          image.close()
        }
      }
    }
  }
}
