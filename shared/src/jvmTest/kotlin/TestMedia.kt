package soko.ekibun

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** 随源码入库的测试媒体（仓库根的 `test-media/`，两个模块的 jvmTest 共用）：取真实路径，拿不到才复制成临时文件。 */
internal object TestMedia {
  /**
   * h264 High 640×360 25fps 12s + AAC 44.1kHz 立体声，moov 在头部（faststart），443 KB。
   */
  const val BBB_640X360_12S_FASTSTART = "bbb-640x360-12s-faststart.mp4"

  /** 夹广告的 HLS（12s 主片 + 5.1s 广告 + 12s 主片），见 `test-media/README.md`。 */
  const val HLS_AD_PLAYLIST = "hls-ad/playlist.m3u8"

  /** 取素材的真实路径：能解析出文件就用文件，否则复制成临时文件。 */
  fun path(name: String): Path = resourcePath(name) ?: copyToTemp(name)

  /** 协议为 `file:` 时返回真实路径，否则 null。 */
  private fun resourcePath(name: String): Path? {
    val url = TestMedia::class.java.classLoader.getResource(name) ?: return null
    if (url.protocol != "file") return null
    return runCatching { Paths.get(url.toURI()) }
      .getOrNull()
      ?.takeIf { it.toFile().isFile }
  }

  /** 把素材复制成临时文件并返回路径，进程退出时清理。 */
  private fun copyToTemp(name: String): Path {
    val out = Files.createTempFile("acg-test-", "-" + name.substringAfterLast('/'))
    out.toFile().deleteOnExit()
    TestMedia::class.java.classLoader.getResourceAsStream(name)!!.use { input ->
      Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
    }
    return out
  }
}
