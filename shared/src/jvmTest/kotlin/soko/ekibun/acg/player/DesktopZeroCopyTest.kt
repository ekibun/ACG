package soko.ekibun.acg.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.SkiaLayer
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.DesktopSurfaceContext
import soko.ekibun.ffmpeg.FFPlayer
import soko.ekibun.ffmpeg.VideoSurface
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面零拷贝路径必须在真实窗口上成立：反射要能从 Skiko 拿到它的 DirectX 设备，
 * native 要能在同一块 GPU 上建共享纹理并让 Skia 的 D3D12 设备打开。
 *
 * 判据是「帧真的进了共享纹理」—— present 失败的帧会被 native 丢掉，[DesktopSurfaceContext.frame]
 * 就不会走。所以帧数增长即说明转换、共享、跨 API 打开三步都成了。
 */
class DesktopZeroCopyTest {
  @Test(timeout = 120_000)
  fun framesLandInTheSharedTexture() =
    runBlocking {
      val frame = JFrame("zero-copy probe")
      val layer = SkiaLayer()
      frame.contentPane.add(layer, BorderLayout.CENTER)
      frame.setSize(640, 360)
      frame.isVisible = true
      try {
        val surface = DesktopSurfaceContext()
        surface.attachWindow(frame)
        assertTrue(
          surface.isHardwareVideo,
          "the shared-texture path did not come up (Skiko device or D3D11 sharing failed)",
        )

        val player =
          FFPlayer(
            TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(),
            IOHandler(),
            surface,
            hardware = true,
          ) {}
        try {
          val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
          player.play(mapOf(AVMediaType.VIDEO to video), 0)
          withTimeout(20_000) {
            while (surface.frame < 5) delay(10.milliseconds)
          }
          assertTrue(surface.frame >= 5, "no frames reached the shared texture")
        } finally {
          player.closeAsync()
          surface.close()
        }
      } finally {
        frame.dispose()
      }
    }

  /**
   * 挂着输出端、但解码器走软解时，帧必须仍然被转成 Skia 图像画出来。
   *
   * 判据只能看帧本身：软解帧有像素、零拷贝帧没有。拿「有没有输出端」当判据会把这一整类帧
   * 判成「没有画面」，表现为有声音、黑屏。
   */
  @Test(timeout = 120_000)
  fun softFramesStillDrawWithSinkAttached() =
    runBlocking {
      val frame = JFrame("soft probe")
      val layer = SkiaLayer()
      frame.contentPane.add(layer, BorderLayout.CENTER)
      frame.setSize(640, 360)
      frame.isVisible = true
      try {
        val surface = DesktopSurfaceContext()
        surface.attachWindow(frame)
        val player =
          FFPlayer(
            TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(),
            IOHandler(),
            surface,
            hardware = false,
          ) {}
        try {
          val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
          player.play(mapOf(AVMediaType.VIDEO to video), 0)
          var drawn = false
          surface.getImage { drawn = true }
          assertTrue(drawn, "soft frames were dropped: nothing reached currentFrame")
        } finally {
          player.closeAsync()
          surface.close()
        }
      } finally {
        frame.dispose()
      }
    }

  /** 共享纹理必须真的被画进 Compose 的画布：Skia 的 GPU 上下文、包装、blit 三步都成立。 */
  @Test(timeout = 120_000)
  fun composeCanvasDrawsTheSharedTexture() =
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
        window.setSize(640, 360)
        window.isVisible = true
      }
      try {
        val surface = withTimeout(10_000) { ready.await() }
        val player =
          FFPlayer(
            TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(),
            IOHandler(),
            surface,
            hardware = true,
          ) {}
        try {
          val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
          player.play(mapOf(AVMediaType.VIDEO to video), 0)
          withTimeout(20_000) {
            while (!surface.isHardwareVideoDrawn) delay(10.milliseconds)
          }
          assertTrue(surface.isHardwareVideoDrawn, "the shared texture never reached the canvas")
        } finally {
          player.closeAsync()
        }
      } finally {
        SwingUtilities.invokeAndWait { window.dispose() }
      }
    }
}
