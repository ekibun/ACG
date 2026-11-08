package soko.ekibun.acg.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 账号数据：当前是谁、有哪些人。
 *
 * 按 NiA 的叫法，这类对外接口叫 Repository；具体存储 / 网络的适配才叫 DataSource。
 * 放全局而不是某个页面里 —— 账号要跨 tab、跨独立页面用，将来还要落盘。
 * 现在是占位名单，真账号等登录态与插件里的 provider 落地。
 */
object AccountRepository {
  private val _all = MutableStateFlow(listOf("ekibun", "访客"))
  val all = _all.asStateFlow()

  private val _current = MutableStateFlow(_all.value.first())
  val current = _current.asStateFlow()

  fun select(name: String) {
    if (name in _all.value) _current.value = name
  }
}
