package soko.ekibun.acg.data

import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.quickjs.JSError

/** 支持登录的站点；「添加账号」列的就是它。 */
data class LoginPlugin(
  val site: String,
  val label: String,
)

/**
 * 宿主对插件的账号面：登录、打开账号在站点上的主页。
 *
 * 页面都由插件用 `webview`（前台）开 —— 宿主不碰登录页、不拼站点地址、也不往 jar 里写东西。
 */
interface AccountLogin {
  val plugins: List<LoginPlugin>

  suspend fun login(plugin: LoginPlugin): Account

  /** 打开这个账号在站点上的主页；用户关掉页面后才返回。 */
  suspend fun openHome(account: Account)
}

/** 走插件桥：`login()` 交回 `{ id, name, avatar, session }`，宿主按 `id` 认出这个人。 */
internal class PluginAccountLogin(
  override val plugins: List<LoginPlugin>,
) : AccountLogin {
  override suspend fun login(plugin: LoginPlugin): Account {
    val result =
      JsEngine.callPlugin(plugin.site, "login") as? Map<*, *>
        ?: throw JSError("plugin '${plugin.site}' login returned no object")
    // 少字段或类型不对就让它炸：插件没按契约来，宿主不猜默认值
    return Account(
      site = plugin.site,
      id = result["id"] as String,
      name = result["name"] as String,
      session = result["session"] as String,
      avatar = result["avatar"] as? String,
    )
  }

  // 怎么把会话装进页面是插件的事（未必是 cookie），宿主只把账号摊成参数递过去
  override suspend fun openHome(account: Account) {
    JsEngine.callPlugin(account.site, "openHome", account.toPluginParam())
  }
}
