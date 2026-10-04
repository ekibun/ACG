package soko.ekibun

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 随源码入库的测试媒体（`src/jvmTest/resources/media/`，来源与规格见那儿的 `README.md`）。
 * 素材**入库**而不是放在工作区外的临时目录 —— 那份目录整体 gitignore，换台机器（或 CI）跑就
 * 静默跳过、用例形同不存在。
 *
 * 取的是**临时文件副本**，不是资源路径本身：`FileIO` 要的是真实路径，而 classpath 上的资源在
 * 打成 jar 之后就取不到路径了。
 */
internal object TestMedia {
  /** h264 High 320×240 30fps 25s + AAC（44.1 kHz 单声道），关键帧 0 / 8.333 / 16.667s。 */
  const val SILENT_BLACK_25S = "media/silent-black-25s.mp4"

  /** 把 `media/` 下的素材复制成临时文件并返回它的路径（进程退出时自动清理）。 */
  fun copyToTemp(name: String): Path {
    val out = Files.createTempFile("acg-test-", "-" + name.substringAfterLast('/'))
    out.toFile().deleteOnExit()
    TestMedia::class.java.classLoader.getResourceAsStream(name)!!.use { input ->
      Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
    }
    return out
  }
}
