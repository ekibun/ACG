package soko.ekibun.acg.data

import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.quickjs.JSError

/** 支持登录的站点；「添加账号」列的就是它。 */
data class LoginPlugin(
  val site: String,
  val label: String,
)

/**
 * 宿主对插件的账号面：登录、以及打开账号在站点上的主页。
 *
 * 页面都由插件自己用 `webview`（前台）开 —— 宿主不碰登录页、不拼站点地址、也不往 jar 里写东西，
 * 只认插件交回的账号。
 */
interface AccountLogin {
  val plugins: List<LoginPlugin>

  suspend fun login(plugin: LoginPlugin): Account

  /** 打开这个账号在站点上的主页；用户关掉页面后才返回。 */
  suspend fun openHome(account: Account)
}

/** 走插件桥：`login()` 交回 `{ userId, name, avatar, session }`，宿主拼出账号 id。 */
internal class PluginAccountLogin(
  override val plugins: List<LoginPlugin>,
) : AccountLogin {
  override suspend fun login(plugin: LoginPlugin): Account {
    val result =
      JsEngine.instance.callPlugin(plugin.site, "login") as? Map<*, *>
        ?: throw JSError("plugin '${plugin.site}' login returned no object")
    val userId = result.string(plugin.site, "login", "userId")
    return Account(
      id = "${plugin.site}:$userId",
      site = plugin.site,
      name = result.string(plugin.site, "login", "name"),
      session = result.string(plugin.site, "login", "session"),
      avatar = result["avatar"] as? String,
    )
  }

  // 怎么把会话装进页面是插件的事（未必是 cookie），宿主只把串原样递过去
  override suspend fun openHome(account: Account) {
    JsEngine.instance.callPlugin(account.site, "openHome", account.session)
  }
}

/** 缺字段就是插件没按契约来，直接报错；不猜默认值。 */
private fun Map<*, *>.string(
  site: String,
  function: String,
  key: String,
): String = this[key] as? String ?: throw JSError("plugin '$site' $function returned no '$key'")
