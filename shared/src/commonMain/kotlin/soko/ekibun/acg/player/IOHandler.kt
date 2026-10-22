package soko.ekibun.acg.player

import soko.ekibun.ffmpeg.AvIO

/**
 * native `io_open` 的入口：按 url 的 scheme 分发到 [HttpIO] / [FileIO]，并持有「叫醒当前读」的唯一投递口。
 *
 * 打不开只能返回恒失败的 [AvIO]，不能抛（`open` 跑在 JNI 回调里，见
 * `docs/development/playback-invariants.md` 第 1.5 节）。
 */
class IOHandler(
  /** 交给 [HttpIO] 的源选项（`headers` 等）。 */
  private val options: Map<String, Any> = mapOf(),
) : AvIO.Handler {
  /** 当前在等数据的那次读的投递口，由 [BaseIO.read] 挂上 / 摘掉。 */
  @Volatile
  private var raise: (() -> Unit)? = null

  /** 只对 http(s) 建网络会话，其余一律当本地路径。 */
  override fun open(url: String): AvIO =
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
      HttpIO(url, options, this)
    } else {
      FileIO(urlToPath(url))
    }

  override fun abort() {
    raise?.invoke()
  }

  /** 登记当前读的叫醒手段；null 表示摘掉投递口。 */
  internal fun onSignal(block: (() -> Unit)?) {
    raise = block
  }

  /** `file://` 去掉前缀；Windows 盘符前那个多余的斜杠也要去掉。 */
  private fun urlToPath(url: String): String {
    val path = url.removePrefix("file://")
    return if (path.length > 3 && path[0] == '/' && path[2] == ':') path.substring(1) else path
  }
}
