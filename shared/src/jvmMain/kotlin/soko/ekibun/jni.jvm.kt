package soko.ekibun

import java.io.File

/**
 * Compose 桌面插件在「跑 / 打包 / 安装」各条路上都会设的 system property：指向**应用资源目录**
 * 的绝对路径（打包后实测落在 `<app-image>/app/resources/`）。见 `desktopApp/build.gradle.kts`
 * 的 `appResourcesRootDir`。
 */
private const val APP_RESOURCES_DIR = "compose.application.resources.dir"

/**
 * 加载 native 库 —— **能不解包就不解包**。
 *
 * `System.load` 的形参只能是**文件路径**（OS 的 loader 认不了 jar 或流）。原来为了「解得出来」
 * 把整包 dll 都打进了 jar，运行时再无条件解到 `java.io.tmpdir`；而 `deleteOnExit()` 在 Windows
 * 上删不掉**已被 `System.load` 映射**的文件（JDK-4171239，1998 年记着的已知行为）—— 实测一天
 * 攒下 115 个 113 MB 的 `ffmpeg.dll`（12.4 GB）。
 *
 * 现在 dll **不再进 jar**：它们随安装包落成一堆散文件（`appResourcesRootDir` →
 * `<app-image>/app/resources/`），开发 / IDE / 测试态则直接待在 classpath 的**资源目录**里
 * （`build/resources/main`、`processedResources/jvm/test`）。两种情况拿到的都是「磁盘上的真文件」，
 * 一次都不用解，按序找：
 *
 * 1. **打包态**：[APP_RESOURCES_DIR] 下的同名文件；
 * 2. **开发 / IDE / 测试态**：classpath 条目是**目录**时，资源 URL 协议就是 `file:`，那个 URL
 *    直接指向真文件（`toURI` 把空格 / 非 ASCII 转义还原成真实路径）；
 * 3. **兜底**：上面两条都不成立（库既不在应用资源、也不在 classpath 目录）时，退到
 *    `System.loadLibrary` —— 让 OS 去 `java.library.path` 上找。
 *
 * 前两步是**加法**：属性没设、文件不在就继续往下走。
 */
@Suppress("UnsafeDynamicallyLoadedCode")
actual fun jniLoadLibrary(name: String) {
  val osName = System.getProperty("os.name").lowercase()
  val extension =
    when {
      osName.contains("win") -> ".dll"
      osName.contains("mac") -> ".dylib"
      else -> ".so"
    }
  val fileName = if (osName.contains("win")) "$name$extension" else "lib$name$extension"

  // 1. 打包态：应用资源目录里的真文件
  val packaged = System.getProperty(APP_RESOURCES_DIR)?.let { File(it, fileName) }
  if (packaged != null && packaged.isFile) {
    System.load(packaged.absolutePath)
    return
  }

  // 2. 目录型 classpath 条目：资源 URL 就是文件路径
  val loader = Thread.currentThread().contextClassLoader ?: object {}.javaClass.classLoader
  val onDisk =
    loader
      .getResource(fileName)
      ?.takeIf { it.protocol == "file" }
      // toURI 会把路径里的转义（空格 / 非 ASCII）还原成真实路径，别直接拿 url.path 去拼。
      ?.let { url -> runCatching { File(url.toURI()) }.getOrNull() }
  if (onDisk != null && onDisk.isFile) {
    System.load(onDisk.absolutePath)
    return
  }

  // 3. 兜底：库既不在应用资源、也不在 classpath 目录，让 OS 去 java.library.path 找。
  System.loadLibrary(name)
}
