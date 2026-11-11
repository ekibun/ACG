package soko.ekibun.acg.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import soko.ekibun.common.settingsStore

/** 一个已登录的账号。账号与会话归宿主持有，插件不存登录态 —— 所以同一个站点能登多个。 */
@Serializable
data class Account(
  val id: String,
  /** 插件标识；一个插件服务一个站点。 */
  val site: String,
  val name: String,
  /** 对宿主不透明的会话串，形状由插件定。 */
  val session: String,
  /** 站点交回的头像地址；没有就退回字母头像。 */
  val avatar: String? = null,
)

/** 账号表与当前账号，界面只读这份。 */
interface AccountStore {
  val accounts: StateFlow<Map<String, Account>>

  val current: StateFlow<Account?>
}

/** 落盘形状：包一层对象，以后加字段不用改顶层；新字段给默认值就能平滑读写。 */
@Serializable
private data class AccountsSave(
  val currentId: String? = null,
  val accounts: Map<String, Account> = emptyMap(),
)

/** 宽松：删字段不炸（ignoreUnknownKeys），枚举缺值 / 显式 null 落默认（coerceInputValues）。 */
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
        } catch (e: IllegalArgumentException) {
          null
        }
      }
    _accounts.value = stored?.accounts ?: emptyMap()
    _current.value = stored?.currentId?.let(stored.accounts::get)
  }

  /** 登录成功后落表并选中；同一个人重登就盖掉旧的那条。 */
  suspend fun add(account: Account) {
    _accounts.update { it + (account.id to account) }
    _current.value = account
    save()
  }

  /** 切换当前账号：内存立即生效，落盘跟着写完才返回。 */
  suspend fun select(id: String) {
    val account = _accounts.value[id] ?: return
    _current.value = account
    save()
  }

  /** 打开这个账号在站点上的主页（前台 webview，页面由插件开）；只读，不动账号表与当前账号。 */
  suspend fun openHome(id: String) {
    val account = _accounts.value[id] ?: return
    accountLogin.openHome(account)
  }

  /** 登出：删掉这条账号（会话跟着没了）；删的是当前账号就切到剩下的第一个。 */
  suspend fun remove(id: String) {
    val remaining = _accounts.value - id
    _accounts.value = remaining
    if (_current.value?.id == id) _current.value = remaining.values.firstOrNull()
    save()
  }

  /**
   * 调用方先改内存再进来，提交顺序就是点击顺序；写本身由 `DataStore` 串行。
   *
   * 失败只留日志：内存已经改了，这次运行里账号就是可用的，不该把登录或切换回报成失败。
   */
  private suspend fun save() {
    try {
      settingsStore.set(
        ACCOUNTS_KEY,
        accountsJson.encodeToString(AccountsSave(currentId = _current.value?.id, accounts = _accounts.value)),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      println("account: write failed: $t")
    }
  }
}
