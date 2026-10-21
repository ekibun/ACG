package soko.ekibun.common

/** 按 [charset] 把文本编码成字节；[charset] 不认识时抛异常。 */
expect fun encodeTextToBytes(
  text: String,
  charset: String,
): ByteArray

/** [encodeTextToBytes] 的反向：非法字节按编码的默认策略替换，不抛。 */
expect fun decodeBytesToText(
  bytes: ByteArray,
  charset: String,
): String
