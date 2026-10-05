package soko.ekibun.acg.player

import soko.ekibun.acg.common.File
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO

/**
 * 本地文件的 [AvIO] 实现。
 *
 * 与 [HttpIO] 的差别全在数据来源：本地文件能直接定位到任意偏移，所以这里既没有「网络游标」、
 * 也没有重试 —— [read] 读到 EOF 返回 [AvFormat.AVERROR_EOF]、出错返回 -1。
 *
 * **EOF 必须翻成 [AvFormat.AVERROR_EOF]，不能返回 0** —— aviobuf 的 `fill_buffer` 只把
 * `AVERROR_EOF` / 负数当终止（`aviobuf.c:551-558`），返回 0 会被当成「读空，继续要数据」，
 * wav/pcm 这类 demuxer 会在文件尾**无限空转**。症状与实测见 `silent-failures.md`。
 *
 * 受 [AvIO.seek] 的 `Int` 签名与 native 侧 `(II)I` 方法描述符限制，本实现只对
 * **小于 2 GiB** 的文件正确（偏移在 JNI 边界上就被截成了 32 位）。
 */
class FileIO(
  path: String,
) : AvIO {
  class Handler : AvIO.Handler {
    override fun open(url: String): AvIO = FileIO(urlToPath(url))
  }

  /** 底层句柄；打开失败（路径不存在、没有权限）时为 null。 */
  private val file = File.open(path)

  /** 逻辑读写位置，与底层文件指针保持同步。 */
  private var offset = 0L

  override fun read(buf: ByteArray): Int {
    val file = file ?: return -1
    // 异常先接住（返回 -1 = 出错），EOF 与数据分开翻：
    // -1（RandomAccessFile 的 EOF）→ AVERROR_EOF；正数原样。
    val ret =
      try {
        file.read(buf)
      } catch (e: Throwable) {
        e.printStackTrace()
        return -1
      }
    if (ret > 0) offset += ret
    return if (ret < 0) AvFormat.AVERROR_EOF else ret
  }

  override fun seek(
    offset: Int,
    whence: Int,
  ): Int {
    val file = file ?: return -1
    if (whence == AvFormat.AVSEEK_SIZE) {
      // 契约：返回流总大小；无法确定时必须返回 -1（AVERROR），返回 0 会被 ffmpeg
      // 当成"长度为零的流"，把后续 seek 全判成越界。
      return file.size.toInt()
    }
    return try {
      // 按 POSIX lseek 语义解释 whence：SEEK_SET 绝对 / SEEK_CUR 相对当前位置 /
      // SEEK_END 相对文件末尾，三者公式不同。
      val position =
        when (whence) {
          AvIO.SEEK_SET -> offset.toLong()
          AvIO.SEEK_CUR -> this.offset + offset
          AvIO.SEEK_END -> file.size + offset
          else -> return -1
        }.coerceAtLeast(0)
      file.seek(position)
      this.offset = position
      this.offset.toInt()
    } catch (e: Throwable) {
      e.printStackTrace()
      -1
    }
  }

  override fun close() {
    try {
      file?.close()
    } catch (_: Throwable) {
      // 这里已经在 native 的 io_close2 回调里了，异常逃出去只会 pending 在 JNI 上没人接。
    }
  }
}

/**
 * 把 `file://` 形式的 URL 还原成平台路径。
 *
 * `avformat_open_input` 收到的 url 会被原样送进 `io_open` 回调，所以调用方既可能给
 * `D:\a\b.mp4` 也可能给 `file:///D:/a/b.mp4`；后者只剥 `file://` 会剩下
 * `/D:/a/b.mp4`，多出来的这个前导斜杠在 Windows 上就打不开了。
 */
private fun urlToPath(url: String): String {
  val path = url.removePrefix("file://")
  return if (path.length > 3 && path[0] == '/' && path[2] == ':') path.substring(1) else path
}
