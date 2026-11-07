package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `AvSurfaceContext` 的缓冲契约：视频跨帧复用同一块数组、每帧一格 RGBA；音频非空且长度种类有界。 */
class AvSurfaceContextTest {
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

  /** 解完整条流，每帧交给 [onFrame]（帧由本函数关）；收尾送空包冲刷到吐 null。 */
  private suspend fun decodeAll(
    format: AvFormat,
    codecType: Int,
    onFrame: suspend (AvFrame) -> Unit,
  ) {
    val stream = format.getStreams().first { it.codecType == codecType }
    AvCodec(stream).use { codec ->
      suspend fun deliver(frames: List<AvFrame>) {
        for (frame in frames) {
          try {
            onFrame(frame)
          } finally {
            frame.close()
          }
        }
      }
      while (true) {
        val packet = format.getPacket(emptyList()) ?: break
        try {
          deliver(codec.sendPacketAndGetFrames(packet).second ?: emptyList())
        } finally {
          // packet 由调用方负责关闭
          packet.close()
        }
      }
      while (true) {
        val tail = codec.sendPacketAndGetFrames().second ?: break
        if (tail.isEmpty()) break
        deliver(tail)
      }
    }
  }

  @Test(timeout = 60_000)
  fun buffersAreReusedOnBothStreams() {
    val videoBuffers = ArrayList<ByteArray>()
    var videoBytes = 0L
    val audioBuffers = ArrayList<ByteArray>()
    var audioBytes = 0L
    val sink =
      TestSurface(
        reportAudioTs = true,
        onVideo = {
          videoBuffers += it
          videoBytes += it.size
        },
        onAudio = {
          audioBuffers += it
          audioBytes += it.size
        },
      )

    runBlocking {
      AvFormat("memory:test.y4m", TestIO(y4mStream(WIDTH, HEIGHT, FRAMES))).use { format ->
        decodeAll(format, AVMediaType.VIDEO) { frame ->
          sink.postFrame(AVMediaType.VIDEO, frame)
          sink.flushFrame(AVMediaType.VIDEO, frame)
        }
      }
      AvFormat("memory:test.wav", TestIO(WavMedia.bytes(durationSec = 2))).use { format ->
        decodeAll(format, AVMediaType.AUDIO) { frame ->
          sink.postFrame(AVMediaType.AUDIO, frame)
          val shown = sink.flushFrame(AVMediaType.AUDIO, frame)
          // 上屏时间戳等于帧自身 pts（假件回报的就是帧时间戳）
          assertEquals(frame.timeStamp, shown, "flush timestamp must equal the frame pts when the sink reports it")
        }
      }
    }
    sink.close()

    assertTrue(
      videoBuffers.size >= 2,
      "at least 2 frames must reach the sink, got ${videoBuffers.size}",
    )
    // 判据是引用相等（同一块数组），不是内容相等
    assertTrue(
      videoBuffers.zipWithNext().all { (first, second) -> first === second },
      "the frame buffer must be one reused array, got ${videoBuffers.distinct().size} arrays for ${videoBuffers.size} frames",
    )
    assertEquals(
      WIDTH * HEIGHT * 4L * videoBuffers.size,
      videoBytes,
      "each frame must hand out exactly one RGBA frame (${WIDTH}x$HEIGHT)",
    )

    assertTrue(
      audioBuffers.size >= 2,
      "at least 2 frames must reach the sink, got ${audioBuffers.size}",
    )
    assertTrue(audioBytes > 0, "the audio buffer must not be empty")
    val sizes = audioBuffers.map { it.size }.distinct()
    // 长度种类数远少于帧数
    assertTrue(
      sizes.size * 4 <= audioBuffers.size,
      "audio buffer sizes must not grow per frame, got ${sizes.size} kinds in ${audioBuffers.size} frames: $sizes",
    )
  }
}
