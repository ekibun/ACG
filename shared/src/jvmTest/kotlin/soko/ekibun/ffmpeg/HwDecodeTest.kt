package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.acg.player.IOHandler
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 硬解出的帧必须带着自己的像素与时间戳回到内存：桌面 D3D11VA 的帧是显存句柄，Android 的
 * 是 MediaCodec 拷出来的 NV12，两者都靠 native 的收帧处落到内存才交给上层。
 *
 * 判据不区分「这次真的走了加速」—— 设备不存在、codec 没有加速版本时 native 回落软解，
 * 下面每一条在软解上也成立。
 */
class HwDecodeTest {
  private class Decoded(
    val timestamps: List<Long>,
    /** 前 [KEEP_FRAMES] 帧的整帧 RGBA；再往后只记 [frameBytes]。 */
    val pixels: List<ByteArray>,
    val frameBytes: Int,
  )

  /** 只收下前这么多帧的像素：整帧 RGBA 一帧 ~0.9 MB，全留会撑爆测试堆。 */
  private companion object {
    const val KEEP_FRAMES = 10
  }

  private suspend fun decode(hardware: Boolean): Decoded {
    val timestamps = ArrayList<Long>()
    val pixels = ArrayList<ByteArray>()
    var frameBytes = 0
    val sink =
      object : AvSurfaceContext() {
        override val sampleRate: Int = 48_000
        override val channels: Int = 2
        override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

        override suspend fun flushFrameImpl(
          codecType: Int,
          frame: AvFrame,
          getBuffer: () -> ByteArray?,
        ): Long {
          val buffer = getBuffer() ?: return -1
          frameBytes = buffer.size
          if (pixels.size < KEEP_FRAMES) pixels += buffer.copyOf()
          return -1
        }

        override suspend fun resume() = Unit

        override suspend fun pause() = Unit

        override suspend fun stop() = Unit
      }
    val format = AvFormat(TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).toString(), IOHandler())
    try {
      val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
      val codec = AvCodec(video, hardware)
      try {
        suspend fun collect(frames: List<AvFrame>) =
          frames.forEach { frame ->
            timestamps += frame.timeStamp
            sink.postFrame(AVMediaType.VIDEO, frame)
            sink.flushFrame(AVMediaType.VIDEO, frame)
            frame.close()
          }
        while (true) {
          val packet = format.getPacket(listOf(video)) ?: break
          collect(codec.sendPacketAndGetFrames(packet))
        }
        // 重排缓冲里的尾帧只有 drain 拿得到
        collect(codec.drain())
      } finally {
        codec.closeDeferred().join()
      }
    } finally {
      format.closeDeferred().join()
      sink.closeDeferred().join()
    }
    return Decoded(timestamps, pixels, frameBytes)
  }

  @Test(timeout = 120_000)
  fun hardwareFramesCarryTheirOwnPixels() =
    runBlocking {
      val software = decode(hardware = false)
      val accelerator = decode(hardware = true)

      assertEquals(software.timestamps.size, accelerator.timestamps.size, "hardware path dropped frames")
      // 丢一帧时间戳，就会在别处补一个 0：transfer 不搬属性、少一步 copy_props 就是这样
      assertEquals(software.timestamps, accelerator.timestamps, "timestamps must survive the hw transfer")
      assertTrue(accelerator.timestamps.last() > 0, "frame timestamps are empty")

      assertEquals(software.frameBytes, accelerator.frameBytes)
      assertEquals(KEEP_FRAMES, accelerator.pixels.size)
      // 全零 = 硬解帧没转到内存（sws 读到空平面）：黑屏，但解码器一声不吭
      accelerator.pixels.forEachIndexed { index, picture ->
        assertTrue(picture.any { it != 0.toByte() }, "frame #$index has no pixels")
      }

      // 跨解码路径（NV12 / YUV420P）有 1~3 LSB 的舍入差，逐比特比不了；均值足够区分错帧
      var total = 0L
      var count = 0L
      var max = 0
      accelerator.pixels.forEachIndexed { index, picture ->
        val reference = software.pixels[index]
        assertEquals(reference.size, picture.size)
        for (i in reference.indices) {
          val diff = abs((reference[i].toInt() and 0xff) - (picture[i].toInt() and 0xff))
          if (diff > max) max = diff
          total += diff
          count++
        }
      }
      val mean = total.toDouble() / count
      println("[hw] per-channel diff: max=$max mean=$mean")
      assertTrue(mean <= 4, "hardware picture drifts from the software one (max=$max mean=$mean)")
    }
}
