package soko.ekibun.common

/**
 * 桌面端的设置文件：`-Dacg.dataDir` 指定的目录，缺省 `~/.acg`。
 *
 * 覆盖项是给测试留的 —— 缺省值会让跑一次用例就往开发机的家目录写一份真实设置。
 */
internal actual fun settingsFilePath(): String {
  val dataDir = System.getProperty("acg.dataDir") ?: System.getProperty("user.home") + "/.acg"
  return "$dataDir/settings.preferences_pb"
}
