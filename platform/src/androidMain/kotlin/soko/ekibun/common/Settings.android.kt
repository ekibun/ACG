package soko.ekibun.common

import soko.ekibun.hostApplicationContext

/** Android 端的设置文件：应用私有目录，随应用卸载一并清掉。 */
internal actual fun settingsFilePath(): String =
  hostApplicationContext.filesDir.absolutePath + "/settings.preferences_pb"
