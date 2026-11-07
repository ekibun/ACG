package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 硬解帧必须带着像素与时间戳回到内存；没加速时回落软解，判据同样成立。 */
class AvCodecTest {
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

  private suspend fun decode(
    media: File,
    hardware: Boolean,
  ): Decoded {
    val timestamps = ArrayList<Long>()
    val pixels = ArrayList<ByteArray>()
    var frameBytes = 0
    // 送显侧只收货：像素由 getBuffer 取，正是「硬解帧有没有落到内存」那条判据的观测点
    val sink =
      TestSurface(
        onVideo = { buffer ->
          frameBytes = buffer.size
          if (pixels.size < KEEP_FRAMES) pixels += buffer.copyOf()
        },
      )
    val format = AvFormat(media.absolutePath, TestFileIO(media))
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
          packet.use { collect(codec.sendPacketAndGetFrames(it).second ?: emptyList()) }
        }
        // 重排缓冲里的尾帧要冲刷才拿得到：送个空包，一直收到吐干净
        while (true) {
          val tail = codec.sendPacketAndGetFrames().second ?: break
          if (tail.isEmpty()) break
          collect(tail)
        }
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
      val media = TestMedia.file(TestMedia.BBB_640X360_12S_FASTSTART)
      val software = decode(media, hardware = false)
      val accelerator = decode(media, hardware = true)

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
      assertTrue(mean <= 4, "hardware picture drifts from the software one (max=$max mean=$mean)")
    }
}
