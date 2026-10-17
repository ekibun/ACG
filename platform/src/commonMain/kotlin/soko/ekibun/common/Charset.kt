package soko.ekibun.common

/**
 * 按 [charset] 把文本编码成字节 —— JS 侧 `new TextEncoder(label)` 的实现。
 *
 * [charset] **原样交给平台的编码表**：大小写、别名（`utf8` / `UTF-8`）、非 UTF 编码都认，
 * **不认识就抛**（JVM 上是 `UnsupportedCharsetException` / `IllegalCharsetNameException`），
 * JS 侧 `catch` 接得住。**别兜底成 UTF-8**：静默换编码会让请求体以另一种编码发出去，而对端按
 * 声明的编码解，服务端收到的是乱码却不报错。
 */
expect fun encodeTextToBytes(
  text: String,
  charset: String,
): ByteArray

/** [encodeTextToBytes] 的反向：非法字节按编码的默认策略**替换**（U+FFFD），不抛。 */
expect fun decodeBytesToText(
  bytes: ByteArray,
  charset: String,
): String
