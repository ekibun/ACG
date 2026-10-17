package soko.ekibun.common

import java.nio.charset.Charset

actual fun encodeTextToBytes(
  text: String,
  charset: String,
): ByteArray = text.toByteArray(Charset.forName(charset))

actual fun decodeBytesToText(
  bytes: ByteArray,
  charset: String,
): String = String(bytes, Charset.forName(charset))
