package soko.ekibun.acg.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.quickjs.JSError
import kotlin.coroutines.coroutineContext

/**
 * 收藏：读一次拿到插件给的档，切一档再读一页。表归宿主、进程内一直留着 —— 切 tab 回来直接摆旧数据，
 * 只有没读过的那一档（`page == 0`）才发请求；刷新重读第一页、整档换掉，后面的页随之作废。
 *
 * 一切状态都在表里，界面不另记：在飞的是 [CollectionCategory.loadingPage]，失败的是 `error.page`，
 * 转圈、续读、错误显示在哪全由两者推出来。所以这里不区分「刷新」与「续读」，[load] 也不返回东西。
 *
 * 数据类与纯规则在 `CollectionTable.kt`；插件契约见 `files/js/module/bgm.js` 的 `collections`。
 */
object CollectionRepository {
  /**
   * 请求跑在自己的作用域里，不在调用方的协程上：`load` 是普通函数，第一行就落下状态（当帧能看到圈），
   * 取消也只在这张表里生效，不会连带取消调用方的 `launch`。
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _table = MutableStateFlow(CollectionTable())
  val table: StateFlow<CollectionTable> = _table.asStateFlow()

  /** 每档当前那一轮：新的提上来时把上一轮取消，这一档只认最后提交的那次读。 */
  private val jobs = mutableMapOf<String?, Job>()

  /** 读一份或一档的一页：[key] 为 `null` 是整份，带了就是那一档（`已读页 + 1` 是续读）。 */
  fun load(
    account: Account,
    key: String? = null,
    page: Int = 1,
  ) {
    // 换账号：在飞的几轮一并作废、整张表换掉 —— 上一个人的应答不落到这张表上
    if (_table.value.accountKey != account.key) {
      jobs.values.forEach { it.cancel() }
      jobs.clear()
      _table.update { CollectionTable(accountKey = account.key) }
    }
    jobs.remove(key)?.cancel()
    _table.update { it.begin(key, page) }
    // 先登记再启动：`Main.immediate` 下 start 会立刻跑到第一个挂起点，那时这一格必须已经是自己
    val job = scope.launch(start = CoroutineStart.LAZY) { fetch(account, key, page) }
    jobs[key] = job
    job.start()
  }

  /** 登出：取消在飞的全部、整张表清空 —— 不然同一个人重登时 `accountKey` 相同，会直接摆出旧数据。 */
  fun clear() {
    jobs.values.forEach { it.cancel() }
    jobs.clear()
    _table.value = CollectionTable()
  }

  private suspend fun fetch(
    account: Account,
    key: String?,
    page: Int,
  ) {
    val self = coroutineContext.job

    // 被顶掉的那轮醒来时这一格已经换了人，结果不写：取消拦不住「await 刚返回」那一瞬
    fun mine() = jobs[key] === self

    val scopes =
      try {
        val result = JsEngine.callPlugin(account.site, COLLECTIONS, account.toPluginParam(), key, page)
        (result as? Map<*, *> ?: throw JSError("plugin '${account.site}' $COLLECTIONS returned no object"))
          .toCollectionCategories()
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        _table.update { if (mine()) it.fail(key, page, t.message ?: FAILED) else it }
        return
      }
    _table.update {
      if (!mine()) {
        it
      } else if (key == null) {
        // 整份读成功：键表到手，之前那次整份失败也算过去了
        it.merge(scopes).copy(loading = false, error = null)
      } else {
        it.merge(scopes)
      }
    }
  }

  private const val COLLECTIONS = "collections"
  private const val FAILED = "收藏加载失败"
}
