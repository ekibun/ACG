package soko.ekibun.ffmpeg

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 测试用的合成 WAV（16-bit PCM 单声道 8 kHz），体积与 read 次数可算。 */
internal object WavMedia {
  const val SAMPLE_RATE = 8000
  const val CHANNELS = 1

  /** 生成 [durationSec] 秒的 WAV 字节；内容是线性锯齿波，帧间可区分。 */
  fun bytes(durationSec: Int): ByteArray {
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
    bytes.putInt(16) // fmt chunk 大小
    bytes.putShort(1) // PCM 格式
    bytes.putShort(CHANNELS.toShort())
    bytes.putInt(SAMPLE_RATE)
    bytes.putInt(SAMPLE_RATE * 2 * CHANNELS) // 字节率
    bytes.putShort((2 * CHANNELS).toShort()) // 块对齐
    bytes.putShort(16) // 位深
    bytes.put("data".toByteArray(Charsets.US_ASCII))
    bytes.putInt(data.capacity())
    bytes.put(data.array())
    return bytes.array()
  }
}
