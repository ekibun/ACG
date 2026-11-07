package soko.ekibun.ffmpeg

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Window
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面零拷贝：反射拿到 Skiko 的 D3D 设备、native 在同一 GPU 上建共享纹理、Skia 跨 API 打开。
 * 判据是帧真的进了纹理（present 失败会被 native 丢掉）；三段共用一个窗口。
 */
class DesktopSurfaceContextTest {
  @Test(timeout = 120_000)
  fun sharedTexturePathHoldsEndToEnd() =
    runBlocking {
      val ready = CompletableDeferred<DesktopSurfaceContext>()
      lateinit var window: ComposeWindow
      SwingUtilities.invokeAndWait {
        window = ComposeWindow()
        window.setContent {
          VideoSurface(
            onSurfaceContext = { context ->
              if (context is DesktopSurfaceContext) ready.complete(context)
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
        // 必须是「已显示」的窗口：Skiko 的 DirectX 设备只在 show 之后才有。但没必要让它
        // 挡屏幕 —— 挪到屏幕外、不进任务栏、不抢焦点。
        window.type = Window.Type.UTILITY
        window.isAutoRequestFocus = false
        window.setSize(640, 360)
        window.setLocation(-5000, -5000)
        window.isVisible = true
      }
      try {
        val surface = withTimeout(10_000) { ready.await() }
        assertTrue(
          surface.isHardwareVideo,
          "the shared-texture path did not come up (Skiko device or D3D11 sharing failed)",
        )
        val media = TestMedia.file(MEDIA)

        val hardware = FFPlayer(media.absolutePath, TestFileIO(media), surface, hardware = true) {}
        try {
          val video = hardware.getStreams().first { it.codecType == AVMediaType.VIDEO }
          // play() 挂到整轮结束，不能等：判据只看最先到的那几帧
          launch { hardware.play(mapOf(AVMediaType.VIDEO to video), 0) }
          withTimeout(20_000) {
            while (surface.frame < 5 || !surface.isHardwareVideoDrawn) delay(10.milliseconds)
          }
          assertTrue(surface.frame >= 5, "no frames reached the shared texture")
          assertTrue(surface.isHardwareVideoDrawn, "the shared texture never reached the canvas")
        } finally {
          hardware.closeAsync()
        }

        // 软解帧没有共享纹理：必须转成 Skia 图像才算画出来。拿「有没有输出端」当判据
        // 会把这一整类帧判成「没有画面」，表现为有声音、黑屏。
        // 先确认硬解帧没留下图像，否则下面那条断言会捡上一段的便宜。
        assertNull(surface.currentFrame, "hardware frames must not land in currentFrame")
        val software = FFPlayer(media.absolutePath, TestFileIO(media), surface, hardware = false) {}
        try {
          val video = software.getStreams().first { it.codecType == AVMediaType.VIDEO }
          launch { software.play(mapOf(AVMediaType.VIDEO to video), 0) }
          var drawn = false
          withTimeout(20_000) {
            while (!drawn) {
              surface.getImage { drawn = true }
              if (!drawn) delay(10.milliseconds)
            }
          }
          assertTrue(drawn, "soft frames were dropped: nothing reached currentFrame")
        } finally {
          software.closeAsync()
        }
      } finally {
        SwingUtilities.invokeAndWait { window.dispose() }
      }
    }

  private companion object {
    const val MEDIA = TestMedia.BBB_640X360_12S_FASTSTART
  }
}
