package soko.ekibun.ffmpeg

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 测试用的合成 WAV（16-bit PCM 单声道 8 kHz）。
 *
 * 给**需要精确刻度**的用例现场合成：体积、read 次数都是算得出的（[AvReadErrorFoldTest] 靠
 * 「10 秒 ≈ 160 KB、第 4 次 32 KB read 起返回 -1」把错误钉在指定阶段；[AvSurfaceContextBufferReuseTest]
 * 用它走一遍 pcm 解码 + 重采样）—— 成品素材体积固定，调不出这种刻度。头 44 字节 + 裸 PCM，
 * `avformat_open_input` 直接能解。
 */
internal object WavMedia {
  const val SAMPLE_RATE = 8000
  const val CHANNELS = 1

  /** 生成 [durationSec] 秒的 WAV 字节（内存形态，喂假 IO）；内容是线性锯齿波（帧间可区分）。 */
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
    return bytes.array()
  }
}
