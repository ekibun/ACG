package soko.ekibun.ffmpeg

import java.io.File

/** 仓库根 `test-media/` 的素材，取法同 `:shared` 的 `TestMedia`。必须是目录资源（要真实路径）。 */
internal object TestMedia {
  /** 640×360 / 25fps / 12s，有画面也有音轨（见 `test-media/README.md`）。 */
  const val BBB_640X360_12S_FASTSTART = "bbb-640x360-12s-faststart.mp4"

  /** 主片中间插一段广告的 HLS：声明轴 29.1 s，广告在 12 s 处。 */
  const val HLS_AD_PLAYLIST = "hls-ad/playlist.m3u8"

  /** 取 `jvmTest` 资源里的媒体文件。 */
  fun file(name: String): File {
    val url =
      requireNotNull(TestMedia::class.java.classLoader.getResource(name)) {
        "missing test media: $name"
      }
    check(url.protocol == "file") { "test media must be a directory resource, got $url" }
    return File(url.toURI())
  }
}
