package soko.ekibun.acg.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import soko.ekibun.common.settingsStore

/** 一个已登录的账号。会话归宿主持有、插件不存登录态，所以同一站点能登多个；交给插件的是整个账号。 */
@Serializable
data class Account(
  /** 插件标识；一个插件服务一个站点。 */
  val site: String,
  /** 站点上的用户 id（`bgm` 是用户名，即 `/user/<用户名>` 那一段）。 */
  val id: String,
  val name: String,
  /** 对宿主不透明的会话串，形状由插件定。 */
  val session: String,
  /** 站点交回的头像地址；没有就退回字母头像。 */
  val avatar: String? = null,
) {
  /** 账号表键 `"$site:$id"`；不进落盘 JSON（`@Serializable` 只认构造参数）。 */
  val key = "$site:$id"

  /** 交给插件的参数：字段与本类同名，不含 [key]；桥不认识 [Account]，加字段只改这一处。 */
  fun toPluginParam(): Map<String, Any?> =
    mapOf(
      "site" to site,
      "id" to id,
      "name" to name,
      "session" to session,
      "avatar" to avatar,
    )
}

/** 账号表与当前账号，界面只读这份。 */
interface AccountStore {
  val accounts: StateFlow<Map<String, Account>>

  val current: StateFlow<Account?>
}

/** 落盘形状：包一层对象，以后加字段不用改顶层。 */
@Serializable
private data class AccountsSave(
  /** 当前账号的键（[Account.key]）。 */
  val current: String? = null,
  val accounts: Map<String, Account> = emptyMap(),
)

/** 宽松：未知字段忽略，显式 null 落默认值。 */
private val accountsJson =
  Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

private const val ACCOUNTS_KEY = "accounts"

object AccountRepository : AccountStore {
  /** 组合根：站点目录暂只有 Bangumi，等插件清单接入后由它提供。 */
  private val accountLogin: AccountLogin = PluginAccountLogin(listOf(LoginPlugin(site = "bgm", label = "Bangumi")))

  private val _accounts = MutableStateFlow<Map<String, Account>>(emptyMap())
  override val accounts: StateFlow<Map<String, Account>> = _accounts.asStateFlow()

  private val _current = MutableStateFlow<Account?>(null)
  override val current: StateFlow<Account?> = _current.asStateFlow()

  /** 支持登录的站点目录。 */
  val loginPlugins: List<LoginPlugin> get() = accountLogin.plugins

  /** 走一遍这个站点的登录（页面由插件自己开），成功后落表并选中。 */
  suspend fun login(plugin: LoginPlugin): Account = accountLogin.login(plugin).also { add(it) }

  /** 从磁盘读一次。读坏当没有账号，不抛 —— 账号入口不该因为一个坏字符串就起不来。 */
  suspend fun load() {
    val stored =
      settingsStore.get(ACCOUNTS_KEY)?.let { raw ->
        try {
          accountsJson.decodeFromString<AccountsSave>(raw)
        } catch (_: IllegalArgumentException) {
          null
        }
      }
    val accounts = stored?.accounts ?: emptyMap()
    _accounts.value = accounts
    _current.value = stored?.current?.let(accounts::get)
  }

  /** 登录成功后落表并选中；同一个人重登就盖掉旧的那条。 */
  suspend fun add(account: Account) {
    _accounts.update { it + (account.key to account) }
    _current.value = account
    save()
  }

  /** 切换当前账号：内存立即生效，落盘跟着写完才返回。 */
  suspend fun select(key: String) {
    val account = _accounts.value[key] ?: return
    _current.value = account
    save()
  }

  /** 打开这个账号在站点上的主页（前台 webview，页面由插件开）；只读，不动账号表与当前账号。 */
  suspend fun openHome(key: String) {
    val account = _accounts.value[key] ?: return
    accountLogin.openHome(account)
  }

  /** 登出：删掉这条账号（会话跟着没了）；删的是当前账号就切到剩下的第一个，并清掉它的收藏表。 */
  suspend fun remove(key: String) {
    val wasCurrent = _current.value?.key == key
    _accounts.update { it - key }
    if (wasCurrent) {
      _current.value = _accounts.value.values.firstOrNull()
      // 收藏表跟着账号走：登出后表里那份属于它，留着会被同一个人重登时直接摆出来
      CollectionRepository.clear()
    }
    save()
  }

  /**
   * 调用方先改内存再进来，提交顺序即点击顺序；写由 `DataStore` 串行。
   * 失败只留日志：内存已改，这次运行里账号就是可用的，不该把登录或切换报成失败。
   */
  private suspend fun save() {
    try {
      settingsStore.set(
        ACCOUNTS_KEY,
        accountsJson.encodeToString(AccountsSave(current = _current.value?.key, accounts = _accounts.value)),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      println("account: write failed: $t")
    }
  }
}
