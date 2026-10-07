package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 送显/送声的缓冲**必须零拷贝、且复用固定几块 native 内存** —— 直接驱动 [AvSurfaceContext]
 * （不经 [FFPlayer]）：[AvFormat] 解出包、[AvCodec] 解出帧，逐帧 `postFrame` / `flushFrame`。
 * FFPlayer 在这里缺席是有意的，被测的是 AvSurfaceContext 自己的契约，播放编排不属于它。
 *
 * 素材全部在测试里现场合成，不依赖任何入库文件：
 * - 视频：Y4M（`YUV4MPEG2` 文本头 + 裸 YUV 帧）喂 `rawvideo` 解码器 —— 它只是 memcpy，
 *   `sws` 的输入端（YUV420P）就有了。`AvFrame` 只能从解码链出来（native 构造），Kotlin 侧
 *   没有「从裸字节造 AVFrame」的口子，这是不用真编码器就能批量出帧的最短路径；输出端照旧
 *   是写死的 `AV_PIX_FMT_RGBA`。
 * - 音频：[WavMedia] 的内存 WAV，`pcm_s16le` 解码 + `swr` 重采样到 Sink 声明的输出规格。
 *
 * 判据与来由：
 * 1. （历史回归）`getBuffer` 曾每帧 `NewByteArray` + `SetByteArrayRegion`，视频 1308×736
 *    RGBA = 3.67 MB/帧 ⇒ 60 帧/s 的播放 20 s 分配 3250 MB，Java 堆被顶在 `-Xmx` 上。现在必须
 *    交 **direct ByteBuffer**，直接指向 native 那块输出缓冲；
 * 2. 「复用」不体现在对象身份上（每次 `NewDirectByteBuffer` 都是新包装），而在**地址**上：
 *    视频缓冲整个上下文就一块，地址必须恒同；音频是**双缓冲交换**（`postFrameAudio` 把
 *    `_audioBuffer1` 与 `audioBuffer` 对调），地址在两个槽位间轮换 —— 上限 2，不能随帧数增长。
 */
class AvSurfaceContextBufferReuseTest {
  /**
   * 真上下文（`initNative` 真跑）+ 空设备：只记下每帧拿到的缓冲地址与大小。
   * 直驱全程单线程（`AvSurfaceContext` 没有归属 dispatcher，回调就地执行），普通列表即可。
   */
  private class Sink : AvSurfaceContext() {
    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    val videoAddresses = ArrayList<Long>()
    val audioAddresses = ArrayList<Long>()

    @Volatile
    var allDirect = true

    @Volatile
    var videoBytes = 0L

    @Volatile
    var audioBytes = 0L

    /** 声卡在放：真身按采样数节流，这里直驱不模拟节拍（判据与时间无关）。 */
    override suspend fun flushAudioBuffer(buf: ByteBuffer): Int {
      if (!buf.isDirect) allDirect = false
      audioBytes += buf.remaining()
      audioAddresses += addressOf(buf)
      // 返回 0 = 没有重采样前导偏移，flushFrame 的时间戳就是帧自己的 pts
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

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 48
    const val FRAMES = 5
  }

  /**
   * 现场合成一段 Y4M 流：头声明 `C420jpeg`（= `AV_PIX_FMT_YUV420P`），每帧 `FRAME\n` 后跟
   * Y/U/V 三个平面 —— 正是 `av_image_get_buffer_size(YUV420P, w, h, 1)` 的打包布局，与
   * `yuv4mpegdec.c` 的 `s->packet_size` 一致。内容逐帧可区分。
   */
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
  fun videoBufferIsZeroCopyAndReused() {
    val sink = Sink()
    runBlocking {
      AvFormat("memory:test.y4m", TestIo(y4mStream(WIDTH, HEIGHT, FRAMES))).use { format ->
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        AvCodec(video).use { codec ->
          while (true) {
            val packet = format.getPacket(emptyList()) ?: break
            // packet 由 sendPacketAndGetFrames 内部收掉，调用方只管帧
            for (frame in codec.sendPacketAndGetFrames(packet)) {
              sink.postFrame(AVMediaType.VIDEO, frame)
              sink.flushFrame(AVMediaType.VIDEO, frame)
              frame.close()
            }
          }
          for (frame in codec.drain()) {
            sink.postFrame(AVMediaType.VIDEO, frame)
            sink.flushFrame(AVMediaType.VIDEO, frame)
            frame.close()
          }
        }
      }
    }
    sink.close()
    assertTrue(sink.videoAddresses.size >= 2, "at least 2 frames must reach the sink, got ${sink.videoAddresses.size}")
    assertTrue(sink.allDirect, "buffers handed to the platform must be direct (zero-copy)")
    val addresses = sink.videoAddresses.filter { it != 0L }.distinct()
    assertEquals(
      1,
      addresses.size,
      "the frame buffer must reuse one native address, got ${addresses.size}",
    )
    assertEquals(
      WIDTH * HEIGHT * 4L * sink.videoAddresses.size,
      sink.videoBytes,
      "each frame must hand out exactly one RGBA frame (${WIDTH}x$HEIGHT)",
    )
  }

  @Test(timeout = 60_000)
  fun audioBufferIsZeroCopyAndBoundedReuse() {
    val sink = Sink()
    runBlocking {
      AvFormat("memory:test.wav", TestIo(WavMedia.bytes(durationSec = 2))).use { format ->
        val audio = format.getStreams().first { it.codecType == AVMediaType.AUDIO }
        AvCodec(audio).use { codec ->
          while (true) {
            val packet = format.getPacket(emptyList()) ?: break
            for (frame in codec.sendPacketAndGetFrames(packet)) {
              sink.postFrame(AVMediaType.AUDIO, frame)
              val shown = sink.flushFrame(AVMediaType.AUDIO, frame)
              // flushAudioBuffer 返回 0（无前导静音）⇒ 上屏时间戳就是帧自己的 pts
              assertEquals(frame.timeStamp, shown, "flush timestamp must equal the frame pts when sink returns 0")
              frame.close()
            }
          }
          for (frame in codec.drain()) {
            sink.postFrame(AVMediaType.AUDIO, frame)
            sink.flushFrame(AVMediaType.AUDIO, frame)
            frame.close()
          }
        }
      }
    }
    sink.close()
    assertTrue(sink.audioAddresses.size >= 2, "at least 2 frames must reach the sink, got ${sink.audioAddresses.size}")
    assertTrue(sink.allDirect, "buffers handed to the platform must be direct (zero-copy)")
    val addresses = sink.audioAddresses.filter { it != 0L }.distinct()
    assertTrue(
      addresses.size <= 2,
      "the audio buffer swaps between two slots, must not grow per frame, got ${addresses.size}",
    )
    assertTrue(sink.audioBytes > 0, "the audio buffer must not be empty")
  }
}
