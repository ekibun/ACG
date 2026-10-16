package soko.ekibun.acg.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import soko.ekibun.acg.source.BgmCollectionItem
import soko.ekibun.acg.source.BgmCollectionStatus
import soko.ekibun.acg.source.BgmSource
import soko.ekibun.acg.source.BgmSubjectType
import soko.ekibun.acg.source.BgmUser

/** 每页条数。v0 的 `limit` 上限是 50，取满省往返。 */
private const val PAGE_SIZE = 50

/**
 * 收藏页的状态与流程 —— 从 [CollectionScreen] 里分出来，是因为它管的都是**跨次点击**的东西：
 * 登录态、当前筛的条目类型与收藏状态、翻到第几页。放在 composable 里就只能靠一堆 `remember`
 * 互相串，翻页与「筛选变了要重拉」这类逻辑也没法单独验。
 *
 * 全部方法都是 `suspend`，由界面在自己的协程域里调 —— 本类**不持有 scope**：
 * 网络出口是插件（`bgm.js`），它的生命周期归 [soko.ekibun.acg.engine.JsEngine]，
 * 这里的活干到一半被取消是正常的（界面走了就不该再往状态里写）。
 */
class CollectionState {
  private val source = BgmSource()

  /** 登录用户；null = 没登录。它同时是「要不要画登录那一栏」的判据。 */
  var user by mutableStateOf<BgmUser?>(null)
    private set

  /** 已存的凭据，给输入框做回填。**不是**当前输入框里的值 —— 那个归界面自己管。 */
  var storedClientId by mutableStateOf("")
    private set
  var storedClientSecret by mutableStateOf("")
    private set

  var subjectType by mutableStateOf(BgmSubjectType.Anime)
    private set
  var status by mutableStateOf(BgmCollectionStatus.Doing)
    private set

  var items by mutableStateOf<List<BgmCollectionItem>>(emptyList())
    private set

  /** 服务端给的收藏总数，用来判「还有没有下一页」。不是 [items] 的大小。 */
  var total by mutableIntStateOf(0)
    private set

  /** 首屏 / 换筛选时的整表加载。 */
  var loading by mutableStateOf(false)
    private set

  /** 翻下一页。与 [loading] 分开：翻页时列表还在，只该在尾巴上加个圈。 */
  var loadingMore by mutableStateOf(false)
    private set

  /** 登录进行中（浏览器那一步要人操作，可能很久）。 */
  var loggingIn by mutableStateOf(false)
    private set

  /** 最近一次失败的原因，界面直接显示。成功一次就清掉。 */
  var error by mutableStateOf<String?>(null)
    private set

  val hasMore: Boolean get() = items.size < total

  /**
   * 整表的序号：每 [reloadList] 自增一次，用它把**过期的应答**丢掉。
   *
   * 界面上换筛选是连点就能触发的，两轮请求会同时在飞 —— 没有这道闸，先发的后回就把
   * 后发的挤掉，页面停在已经不被选中的那个筛选上（而且是「不报错只错内容」）。
   */
  private var generation = 0

  /**
   * 界面首帧：把凭据读出来回填，登录态还在就直接拉第一页。
   *
   * 读凭据真失败（落盘坏了）也当没配：这是个「先让人看见输入框」的路径，不是登录流程本身。
   */
  suspend fun load() {
    val credentials = runCatching { BgmSource.credentials() }.getOrNull()
    storedClientId = credentials?.first.orEmpty()
    storedClientSecret = credentials?.second.orEmpty()
    // `isLoggedIn` 只看本地存的 session，不问服务端 —— 没有网络也不该卡在登录栏上。
    if (!runCatching { source.isLoggedIn() }.getOrDefault(false)) return
    val loggedInUser = runCatching { source.me() }.getOrNull() ?: return
    user = loggedInUser
    reloadList()
  }

  /**
   * 存下凭据再走一遍授权码流程。**先存**：授权那步会在浏览器里跳出应用，
   * 用户中途放弃也不该把刚填的凭据弄丢。
   */
  suspend fun login(
    clientId: String,
    clientSecret: String,
  ) {
    if (loggingIn) return
    loggingIn = true
    error = null
    try {
      BgmSource.setCredentials(clientId, clientSecret)
      storedClientId = clientId
      storedClientSecret = clientSecret
      user = source.login()
      reloadList()
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (t: Exception) {
      error = t.message ?: t.toString()
    } finally {
      loggingIn = false
    }
  }

  /** 退出：只清登录态，**凭据留着**（下次点登录不用重填）。 */
  suspend fun logout() {
    error = null
    runCatching { source.logout() }
    // 过期的应答不能再往状态里写，否则「退出」之后列表又自己回来了。
    generation++
    user = null
    items = emptyList()
    total = 0
    loading = false
    loadingMore = false
  }

  suspend fun selectSubjectType(type: BgmSubjectType) {
    if (type == subjectType) return
    subjectType = type
    reloadList()
  }

  suspend fun selectStatus(next: BgmCollectionStatus) {
    if (next == status) return
    status = next
    reloadList()
  }

  /** 拉到下一页接到尾巴上。界面滚到底时调；[hasMore] 为假时直接返回。 */
  suspend fun loadMore() {
    if (loading || loadingMore || !hasMore) return
    val username = user?.username ?: return
    val current = generation
    val offset = items.size
    loadingMore = true
    try {
      val page = source.collections(username, subjectType, status, offset, PAGE_SIZE)
      if (current != generation) return
      items = items + page.items
      total = page.total
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (t: Exception) {
      if (current == generation) error = t.message ?: t.toString()
    } finally {
      if (current == generation) loadingMore = false
    }
  }

  private suspend fun reloadList() {
    val username = user?.username ?: return
    val current = ++generation
    loading = true
    error = null
    try {
      val page = source.collections(username, subjectType, status, 0, PAGE_SIZE)
      if (current != generation) return
      items = page.items
      total = page.total
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (t: Exception) {
      if (current == generation) error = t.message ?: t.toString()
    } finally {
      if (current == generation) loading = false
    }
  }
}
