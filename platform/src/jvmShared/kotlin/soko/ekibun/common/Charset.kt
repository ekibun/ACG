package soko.ekibun.common

import java.nio.charset.Charset

/** 用平台编码表编码。 */
actual fun encodeTextToBytes(
  text: String,
  charset: String,
): ByteArray = text.toByteArray(Charset.forName(charset))

/** 用平台编码表解码。 */
actual fun decodeBytesToText(
  bytes: ByteArray,
  charset: String,
): String = String(bytes, Charset.forName(charset))
