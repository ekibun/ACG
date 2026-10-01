package soko.ekibun.ffmpeg

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.acg.player.FileIO
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 送显/送声的缓冲**必须零拷贝、且复用同一块 native 内存**。
 *
 * 2026-10-01 实测的两笔账：
 * 1. `AvPlayback.getBuffer` 原先每帧 `NewByteArray` + `SetByteArrayRegion`，视频 1308×736 RGBA =
 *    **3.67 MB/帧**、约 60 帧/s ⇒ 20 s 里分配 **3250 MB**，Java 堆被顶在 `-Xmx` 上
 *    （探针实测堆在 13.8↔222 MB 之间锯齿；改成 direct buffer 后同一探针变成平稳的 27→52 MB）。
 * 2. 现在交出去的是 **direct ByteBuffer**，直接指向 native 那块输出缓冲 —— 所以「复用」这条
 *    不再体现在对象身份上（每次 `NewDirectByteBuffer` 都是新包装），而在**地址**上：连续帧的
 *    地址必须相同，且必须是 direct 的。
 *
 * 用**真 native 上下文**（`initNative` 真跑，走的是真正的 `getBuffer`）+ **空设备**
 * （不碰 SDL / 声卡；音频只用挂起模拟设备节拍）—— 不需要任何设备就能在 jvmTest 里跑。
 * 素材是仓库根下的 `.workbuddy/test.mp4`（gitignore），找不到就跳过。
 */
class AvPlaybackBufferReuseTest {
  /** 真上下文 + 空设备：只记下每帧拿到的缓冲地址。 */
  private class Sink : AvPlayback({ _ -> }) {
    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16
    val videoAddresses = ArrayList<Long>()
    var allDirect = true
    var videoBytes = 0L

    override suspend fun flushAudioBuffer(buf: ByteBuffer): Int {
      if (!buf.isDirect) allDirect = false
      // 声卡在放：按采样数睡掉一段时间（真身是设备缓冲在节流）
      val samples = buf.remaining() / (channels * 2)
      if (samples > 0) delay((samples * 1000L / sampleRate).coerceAtLeast(1))
      return 0
    }

    override fun flushVideoBuffer(
      buf: ByteBuffer,
      width: Int,
      height: Int,
    ) {
      if (!buf.isDirect) allDirect = false
      videoBytes += buf.remaining()
      videoAddresses += addressOf(buf)
    }

    override suspend fun resume() = Unit

    override suspend fun pause() = Unit

    override suspend fun stop() = Unit
  }

  private fun findVideo(): Path? {
    var dir: Path? = Path.of("").toAbsolutePath()
    while (dir != null) {
      val candidate = dir.resolve(".workbuddy/test.mp4")
      if (Files.exists(candidate)) return candidate
      dir = dir.parent
    }
    return null
  }

  @Test
  fun videoBufferIsZeroCopyAndReused() {
    val url = findVideo()
    if (url == null) {
      println("跳过：找不到 .workbuddy/test.mp4（素材在 gitignore 里，不随仓库分发）")
      return
    }
    runBlocking {
      val sink = Sink()
      val player = FFPlayer(url.toString(), FileIO.Handler(), sink)
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val job = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
        withTimeout(20_000) {
          while (sink.videoAddresses.size < 10) delay(5)
        }
        assertTrue(sink.allDirect, "交给平台的缓冲必须是 direct buffer（零拷贝）")
        val addresses = sink.videoAddresses.filter { it != 0L }.distinct()
        assertEquals(1, addresses.size, "送显缓冲必须复用同一块 native 内存：实际 ${addresses.size} 个地址")
        assertTrue(sink.videoBytes > 0, "缓冲不该是空的")
        player.pause()
        job.join()
      } finally {
        withTimeout(10_000) { player.closeAsync() }
      }
    }
  }
}
