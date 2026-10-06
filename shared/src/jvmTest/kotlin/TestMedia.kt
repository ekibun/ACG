package soko.ekibun

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 随源码入库的测试媒体（`src/jvmTest/resources/media/`，来源与规格见那儿的 `README.md`）。
 * 素材**入库**而不是放在工作区外的临时目录 —— 那份目录整体 gitignore，换台机器（或 CI）跑就
 * 静默跳过、用例形同不存在。
 *
 * 目前只有一份素材：`media/bbb-640x360-12s-faststart.mp4`（h264 High 640×360 25fps 12s +
 * AAC 44.1kHz 立体声，**moov 在头部** / faststart，443 KB）。规格与压制参数见 README。
 *
 * ## 取路径：**优先解析真实路径，拿不到才复制**
 *
 * `FileIO` 要的是磁盘上的**真实路径**。Gradle 跑 jvmTest 时资源是**目录形态**
 * （`build/processedResources/jvm/test/…`，URL 协议 `file:`）⇒ [resourcePath] 直接把它转成
 * [Path]，一次拷贝都不用。只有资源被**打进 jar** 时才拿不到路径（URL 形如
 * `jar:file:…!/media/x.mp4`）—— 那时退回 [copyToTemp] 流式复制到临时文件。
 * 两条路都覆盖 ⇒ 随源码入库的素材在任何环境都取得到真实文件。
 */
internal object TestMedia {
  /**
   * h264 High 640×360 25fps 12s + AAC 44.1kHz 立体声，**moov 在头部**（faststart），443 KB。
   * 来源与压制参数见 `resources/media/README.md`。
   */
  const val BBB_640X360_12S_FASTSTART = "media/bbb-640x360-12s-faststart.mp4"

  /**
   * 取素材的真实路径：**能解析出文件就走文件**，否则退回「复制成临时文件」。
   *
   * 传进 [FileIO] / native 的路径必须真实存在，所以这里**不返回 classpath URL**。
   */
  fun path(name: String): Path = resourcePath(name) ?: copyToTemp(name)

  /**
   * 试把 classpath 资源解析成真实路径。
   *
   * 判据是 **URL 协议为 `file:`**：Gradle 跑测试时资源落在 `build/processedResources/…` 下的
   * 真目录里 ⇒ 直接 [Paths.get]；被打进 jar 时协议是 `jar:` ⇒ 返回 null，交给 [copyToTemp]。
   *
   * 用 [URI] 而不是 `url.path` 拼字符串：路径里有空格 / 非 ASCII（比如中文目录名）时后者会漏掉转义。
   */
  private fun resourcePath(name: String): Path? {
    val url = TestMedia::class.java.classLoader.getResource(name) ?: return null
    if (url.protocol != "file") return null
    return runCatching { Paths.get(url.toURI()) }
      .getOrNull()
      ?.takeIf { it.toFile().isFile }
  }

  /**
   * 把 `media/` 下的素材复制成临时文件并返回它的路径（进程退出时自动清理）。
   *
   * 只在 [resourcePath] 拿不到真实路径时走（资源被打进 jar）。**取的是副本，不是资源路径本身** ——
   * classpath 上的资源在打成 jar 之后就取不到路径了。
   */
  private fun copyToTemp(name: String): Path {
    val out = Files.createTempFile("acg-test-", "-" + name.substringAfterLast('/'))
    out.toFile().deleteOnExit()
    TestMedia::class.java.classLoader.getResourceAsStream(name)!!.use { input ->
      Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
    }
    return out
  }
}
