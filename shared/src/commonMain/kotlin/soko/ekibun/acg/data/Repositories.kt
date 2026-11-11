package soko.ekibun.acg.data

/**
 * 入口在读窗口之前调一次：把落盘的状态读进内存。
 *
 * 两端的入口只认这一个函数 —— 以后加仓库挂在这里，不用再去改 `MainActivity` 与桌面的 `main`。
 * 顺序按依赖排：设置先读，账号的读写都落在设置表里。
 */
suspend fun loadRepositories() {
  SettingsRepository.load()
  AccountRepository.load()
}
