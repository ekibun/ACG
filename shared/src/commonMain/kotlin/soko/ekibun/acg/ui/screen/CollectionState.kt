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

/** 每页条数（v0 的 `limit` 上限 50）。 */
private const val PAGE_SIZE = 50

/** 收藏页的状态与流程：登录态、当前筛选、已加载的页；方法都挂起，由界面在自己的协程域里调。 */
class CollectionState {
  private val source = BgmSource()

  /** 登录用户；null 表示未登录。 */
  var user by mutableStateOf<BgmUser?>(null)
    private set

  /** 已存的凭据，给输入框回填用。 */
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

  /** 服务端给的收藏总数（不是 [items] 的大小）。 */
  var total by mutableIntStateOf(0)
    private set

  /** 首屏 / 换筛选时的整表加载。 */
  var loading by mutableStateOf(false)
    private set

  /** 翻下一页进行中。 */
  var loadingMore by mutableStateOf(false)
    private set

  /** 登录进行中。 */
  var loggingIn by mutableStateOf(false)
    private set

  /** 最近一次失败的原因，界面直接显示。成功一次就清掉。 */
  var error by mutableStateOf<String?>(null)
    private set

  val hasMore: Boolean get() = items.size < total

  /** 整表请求的序号，用来丢掉过期应答。 */
  private var generation = 0

  /** 界面首帧：回填凭据，登录态还在就拉第一页。 */
  suspend fun load() {
    val credentials = runCatching { BgmSource.credentials() }.getOrNull()
    storedClientId = credentials?.first.orEmpty()
    storedClientSecret = credentials?.second.orEmpty()
    // 只看本地存的登录态
    if (!runCatching { source.isLoggedIn() }.getOrDefault(false)) return
    val loggedInUser = runCatching { source.me() }.getOrNull() ?: return
    user = loggedInUser
    reloadList()
  }

  /** 存下凭据并走授权码流程。 */
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

  /** 退出登录：只清登录态。 */
  suspend fun logout() {
    error = null
    runCatching { source.logout() }
    // 递增序号，作废还在飞的应答
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

  /** 拉下一页并接到 [items] 末尾；[hasMore] 为假时直接返回。 */
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
