package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import soko.ekibun.ffmpeg.AvFrame
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AvSurfaceContext] 的缓冲契约：视频缓冲跨帧复用同一块数组；音频缓冲非空，且长度种类远少于帧数。
 */
class AvSurfaceContextBufferReuseTest {
  /** 真上下文 + 空设备：记下每帧拿到的数组与大小。 */
  private class Sink : AvSurfaceContext() {
    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    val videoBuffers = ArrayList<ByteArray>()
    val audioBuffers = ArrayList<ByteArray>()

    @Volatile
    var videoBytes = 0L

    @Volatile
    var audioBytes = 0L

    override suspend fun flushFrameImpl(
      codecType: Int,
      frame: AvFrame,
      getBuffer: () -> ByteArray?,
    ): Long {
      when (codecType) {
        AVMediaType.AUDIO -> {
          getBuffer()?.let { buf ->
            audioBytes += buf.size
            audioBuffers += buf
          }
          // 音频返回帧自身 pts
          return frame.timeStamp
        }
        AVMediaType.VIDEO ->
          getBuffer()?.let { buf ->
            videoBytes += buf.size
            videoBuffers += buf
          }
      }
      return -1
    }

    override suspend fun resume() = Unit

    override suspend fun pause() = Unit

    override suspend fun stop() = Unit
  }

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 48
    const val FRAMES = 5
  }

  /** 现场合成 Y4M：头声明 `C420jpeg`，每帧 `FRAME\n` 后跟 Y/U/V 三个平面，内容逐帧可区分。 */
  private fun y4mStream(
    width: Int,
    height: Int,
    frames: Int,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("YUV4MPEG2 W$width H$height F25:1 Ip A1:1 C420jpeg\n".toByteArray(Charsets.US_ASCII))
    val chroma = (width / 2) * (height / 2)
    repeat(frames) { frame ->
      out.write("FRAME\n".toByteArray(Charsets.US_ASCII))
      out.write(ByteArray(width * height) { ((it + frame * 37) % 256).toByte() })
      out.write(ByteArray(chroma) { ((it + frame * 53) % 256).toByte() })
      out.write(ByteArray(chroma) { ((it + frame * 71) % 256).toByte() })
    }
    return out.toByteArray()
  }

  @Test(timeout = 60_000)
  fun videoBufferIsReusedAcrossFrames() {
    val sink = Sink()
    runBlocking {
      AvFormat("memory:test.y4m", TestIo(y4mStream(WIDTH, HEIGHT, FRAMES))).use { format ->
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        AvCodec(video).use { codec ->
          while (true) {
            val packet = format.getPacket(emptyList()) ?: break
            // packet 由调用方负责关闭
            for (frame in codec.sendPacketAndGetFrames(packet).second ?: emptyList()) {
              sink.postFrame(AVMediaType.VIDEO, frame)
              sink.flushFrame(AVMediaType.VIDEO, frame)
              frame.close()
            }
            packet.close()
          }
          // 冲刷 = 送一个空包，收帧直到解码器吐干净（null）
          while (true) {
            val tail = codec.sendPacketAndGetFrames().second ?: break
            if (tail.isEmpty()) break
            for (frame in tail) {
              sink.postFrame(AVMediaType.VIDEO, frame)
              sink.flushFrame(AVMediaType.VIDEO, frame)
              frame.close()
            }
          }
        }
      }
    }
    sink.close()
    assertTrue(
      sink.videoBuffers.size >= 2,
      "at least 2 frames must reach the sink, got ${sink.videoBuffers.size}",
    )
    // 判据是引用相等（同一块数组），不是内容相等
    val buffers = sink.videoBuffers
    assertTrue(
      buffers.zipWithNext().all { (first, second) -> first === second },
      "the frame buffer must be one reused array, got ${buffers.distinct().size} arrays for ${buffers.size} frames",
    )
    assertEquals(
      WIDTH * HEIGHT * 4L * sink.videoBuffers.size,
      sink.videoBytes,
      "each frame must hand out exactly one RGBA frame (${WIDTH}x$HEIGHT)",
    )
  }

  @Test(timeout = 60_000)
  fun audioBufferIsNotEmptyAndBounded() {
    val sink = Sink()
    runBlocking {
      AvFormat("memory:test.wav", TestIo(WavMedia.bytes(durationSec = 2))).use { format ->
        val audio = format.getStreams().first { it.codecType == AVMediaType.AUDIO }
        AvCodec(audio).use { codec ->
          while (true) {
            val packet = format.getPacket(emptyList()) ?: break
            for (frame in codec.sendPacketAndGetFrames(packet).second ?: emptyList()) {
              sink.postFrame(AVMediaType.AUDIO, frame)
              val shown = sink.flushFrame(AVMediaType.AUDIO, frame)
              // 上屏时间戳等于帧自身 pts
              assertEquals(frame.timeStamp, shown, "flush timestamp must equal the frame pts when sink returns 0")
              frame.close()
            }
            packet.close()
          }
          // 冲刷 = 送一个空包，收帧直到解码器吐干净（null）
          while (true) {
            val tail = codec.sendPacketAndGetFrames().second ?: break
            if (tail.isEmpty()) break
            for (frame in tail) {
              sink.postFrame(AVMediaType.AUDIO, frame)
              sink.flushFrame(AVMediaType.AUDIO, frame)
              frame.close()
            }
          }
        }
      }
    }
    sink.close()
    assertTrue(
      sink.audioBuffers.size >= 2,
      "at least 2 frames must reach the sink, got ${sink.audioBuffers.size}",
    )
    assertTrue(sink.audioBytes > 0, "the audio buffer must not be empty")
    val sizes = sink.audioBuffers.map { it.size }.distinct()
    // 长度种类数远少于帧数
    assertTrue(
      sizes.size * 4 <= sink.audioBuffers.size,
      "audio buffer sizes must not grow per frame, got ${sizes.size} kinds in ${sink.audioBuffers.size} frames: $sizes",
    )
  }
}
