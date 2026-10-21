package soko.ekibun.browser

import java.awt.Desktop
import java.net.URI

// 桌面端的外部浏览器：调 AWT 的 `Desktop.browse` 打开系统默认浏览器。

actual suspend fun launchExternalBrowser(url: String) {
  // 先确认当前环境支持 Desktop
  val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
  if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
    throw IllegalStateException("Opening an external browser is not supported here: $url")
  }
  desktop.browse(URI(url))
}
