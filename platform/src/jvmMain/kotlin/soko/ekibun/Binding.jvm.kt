package soko.ekibun

import java.io.File

/** 应用资源目录的 system property（Compose 插件在跑 / 打包时设置）。 */
private const val APP_RESOURCES_DIR = "compose.application.resources.dir"

/**
 * 加载 native 库：依次找应用资源目录、目录型 classpath、`java.library.path`。
 * 顺序与理由见 `docs/architecture.md` 的「native 库的加载顺序」。
 */
@Suppress("UnsafeDynamicallyLoadedCode")
actual fun loadLibrary(name: String) {
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
      // toURI 还原路径里的转义（空格 / 非 ASCII）
      ?.let { url -> runCatching { File(url.toURI()) }.getOrNull() }
  if (onDisk != null && onDisk.isFile) {
    System.load(onDisk.absolutePath)
    return
  }

  // 3. 兜底：让 OS 去 java.library.path 找
  System.loadLibrary(name)
}
