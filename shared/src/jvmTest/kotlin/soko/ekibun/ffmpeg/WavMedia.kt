package soko.ekibun.ffmpeg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 测试用的合成 WAV（16-bit PCM 单声道 8 kHz）。
 *
 * 仓库**不进**二进制测试媒体（`.workbuddy/` 整体 gitignore，B8 的老约束），
 * 所以 FFmpeg 真路径用例需要的素材在测试里现场合成 —— WAV 的头 44 字节 +
 * 裸 PCM，`avformat_open_input` 直接能解。
 */
internal object WavMedia {
  const val SAMPLE_RATE = 8000
  const val CHANNELS = 1

  /** 生成 [durationSec] 秒的 WAV 到临时目录；内容是线性锯齿波（帧间可区分）。 */
  fun write(durationSec: Int): Path {
    val frames = SAMPLE_RATE * durationSec
    val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until frames) {
      val sample = (i % SAMPLE_RATE) * 65535L / SAMPLE_RATE - 32768L
      data.putShort(sample.toShort())
    }
    val bytes = ByteBuffer.allocate(44 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
    bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
    bytes.putInt(36 + data.capacity())
    bytes.put("WAVE".toByteArray(Charsets.US_ASCII))
    bytes.put("fmt ".toByteArray(Charsets.US_ASCII))
    bytes.putInt(16) // PCM fmt chunk 大小
    bytes.putShort(1) // PCM
    bytes.putShort(CHANNELS.toShort())
    bytes.putInt(SAMPLE_RATE)
    bytes.putInt(SAMPLE_RATE * 2 * CHANNELS) // byte rate
    bytes.putShort((2 * CHANNELS).toShort()) // block align
    bytes.putShort(16) // bits per sample
    bytes.put("data".toByteArray(Charsets.US_ASCII))
    bytes.putInt(data.capacity())
    bytes.put(data.array())
    return Files.write(Files.createTempFile("acg-test-", ".wav"), bytes.array())
  }
}
