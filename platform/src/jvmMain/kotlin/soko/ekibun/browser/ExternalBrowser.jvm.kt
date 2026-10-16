package soko.ekibun.browser

import java.awt.Desktop
import java.net.URI

// 桌面端的外部浏览器：走 AWT 的 `Desktop.browse` —— 系统默认浏览器，不是第二个进程，
// 也不涉及自定义 scheme。

actual suspend fun launchExternalBrowser(url: String) {
  // 无桌面环境的 JVM（headless）里 `Desktop.getDesktop()` 会抛，先问支不支持。
  val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
  if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
    throw IllegalStateException("Opening an external browser is not supported here: $url")
  }
  desktop.browse(URI(url))
}
