package soko.ekibun.acg.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 收藏表的合并规则与状态推导：验「一批结果进来后表长什么样」和「几个判断读出来的意思对不对」。
 * [CollectionRepository.load] 的调度与取消要真发请求，属于手动验证。
 */
class CollectionRepositoryTest {
  /** 第一次读：插件给数据的档落整档，没给的档只落一个键（界面上是「还没读」，不是「空」）。 */
  @Test
  fun theFirstReadKeepsEveryKeyAndLeavesTheUnreadOnesEmpty() {
    val table =
      CollectionTable(accountKey = KEY).merge(
        mapOf(
          "动画|在看" to CollectionCategory(data = listOf(item(1), item(2)), page = 1, pageTotal = 1),
          "动画|想看" to null,
          "动画|看过" to null,
        ),
      )

    val watching = table.categories.getValue("动画|在看")
    val wish = table.categories.getValue("动画|想看")

    assertEquals(listOf("动画|在看", "动画|想看", "动画|看过"), table.categories.keys.toList(), "键表顺序来自插件")
    assertEquals(listOf(1, 2), watching.data.map { it.id })
    assertEquals(0, wish.page, "没读过的档页号是 0")
    assertFalse(wish.hasMore, "没读过不代表还有下一页")
  }

  /** 第二页接在第一页后面；第一页是整档覆盖（刷新、切档都是这条路）。 */
  @Test
  fun laterPagesAppendWhileTheFirstPageReplaces() {
    var table = CollectionTable(accountKey = KEY).merge(mapOf(KEY_ANIME to category(1, 3, 1, 2)))
    table = table.merge(mapOf(KEY_ANIME to category(2, 3, 3, 4)))
    var anime = table.categories.getValue(KEY_ANIME)

    assertEquals(listOf(1, 2, 3, 4), anime.data.map { it.id }, "后页接在已有的后面")
    assertEquals(2, anime.page)
    assertTrue(anime.hasMore, "3 页里读到第 2 页还有下一页")

    table = table.merge(mapOf(KEY_ANIME to category(1, 4, 9)))
    anime = table.categories.getValue(KEY_ANIME)
    assertEquals(listOf(9), anime.data.map { it.id }, "第一页整档覆盖")
    assertEquals(4, anime.pageTotal, "总页数跟着这一页重算")
    assertTrue(anime.hasMore)
  }

  /** 这一批里没出现的档原样留着：那是「这次没读到」，不是「读到空了」。 */
  @Test
  fun categoriesThatDidNotComeBackKeepWhatWasRead() {
    val read = CollectionTable(accountKey = KEY).merge(mapOf(KEY_ANIME to category(1, 5, 1, 2)))
    val anime = read.merge(mapOf("书籍|在读" to category(1, 1, 10))).categories.getValue(KEY_ANIME)

    assertEquals(listOf(1, 2), anime.data.map { it.id })
    assertEquals(1, anime.page, "缺席的档页号不动")
  }

  /** 失败只挂在出错那一档，页号与已读数据都不动；下一条成功的结果把它清掉。 */
  @Test
  fun failureMarksOneCategoryAndSuccessClearsIt() {
    val read = CollectionTable(accountKey = KEY).merge(mapOf(KEY_ANIME to category(1, 3, 1, 2)))
    val failed = read.fail(KEY_ANIME, 2, "boom").categories.getValue(KEY_ANIME)

    assertEquals(CollectionError("boom", 2), failed.error, "失败的页号一并记下，重试才知道要哪一页")
    assertEquals(1, failed.page, "失败的这一次不改已读页号")
    assertEquals(listOf(1, 2), failed.data.map { it.id })

    val recovered = read.fail(KEY_ANIME, 2, "boom").merge(mapOf(KEY_ANIME to category(1, 3, 3, 4)))
    assertNull(recovered.categories.getValue(KEY_ANIME).error, "成功一次就清掉错误")
  }

  /** 开始读一页：页号记在 `loadingPage` 上，上一次的失败先作废 —— 正在重读，旧结论不该还挂着。 */
  @Test
  fun beginningAPageClearsTheLastFailure() {
    val failed = CollectionTable(accountKey = KEY).fail(KEY_ANIME, 2, "boom")
    val retrying = failed.begin(KEY_ANIME, 2).categories.getValue(KEY_ANIME)

    assertNull(retrying.error)
    assertEquals(2, retrying.loadingPage)

    // 整份读同样先作废旧错误：两处对称，界面才不必靠「先判 loading」盖住它
    val wholeRetry = failed.copy(error = "boom").begin(null, 1)
    assertNull(wholeRetry.error)
    assertTrue(wholeRetry.loading)
  }

  /** 成功读一页就把「在飞」放下来，圈跟着熄。 */
  @Test
  fun aSuccessfulPageStopsTheSpinner() {
    val loading = CollectionTable(accountKey = KEY).begin(KEY_ANIME, 1)
    assertEquals(1, loading.categories.getValue(KEY_ANIME).loadingPage)

    val anime = loading.merge(mapOf(KEY_ANIME to category(1, 1, 1, 2))).categories.getValue(KEY_ANIME)
    assertNull(anime.loadingPage)
    assertNull(anime.error)
  }

  /** 界面靠这三个判断决定圈转在哪、错误显示在整页还是顶上；它们是同一个 `loadingPage` 推出来的。 */
  @Test
  fun theFlyingPageDecidesWhichProgressAndErrorTheScreenShows() {
    // 首读：手上一片空白，转的是整页的圈，不是顶上那个
    val firstRead = CollectionCategory(loadingPage = 1)
    assertFalse(firstRead.refreshing)

    // 已有内容再读第一页：顶上的圈
    val refreshing = CollectionCategory(data = listOf(item(1)), page = 1, pageTotal = 2, loadingPage = 1)
    assertTrue(refreshing.refreshing)
    assertFalse(refreshing.loadingMore)

    // 读后面的页：尾巴那个圈
    val more = CollectionCategory(data = listOf(item(1)), page = 1, pageTotal = 3, loadingPage = 2)
    assertTrue(more.loadingMore)
    assertFalse(more.refreshing)

    // 第一页失败但内容还在：顶上一条提示，列表照常
    assertEquals(
      CollectionError("x", 1),
      refreshing.copy(loadingPage = null, error = CollectionError("x", 1)).refreshError,
    )
    // 一条都没有时归整页错误，不该走顶部提示
    assertNull(CollectionCategory(error = CollectionError("x", 1)).refreshError)
  }

  /** 同一页里就重复的条目（插件那边并列了两遍）也要去重：列表拿 `id` 当 key，重复即崩。 */
  @Test
  fun aPageThatRepeatsTheSameSubjectIsDeduped() {
    val table = CollectionTable(accountKey = KEY).merge(mapOf(KEY_ANIME to category(1, 3, 1, 1, 2)))

    val anime = table.categories.getValue(KEY_ANIME)
    assertEquals(listOf(1, 2), anime.data.map { it.id }, "重复的只留第一次出现")
    assertEquals(1, anime.page)
    assertEquals(3, anime.pageTotal, "去重不动页号")
  }

  /** 插件的形状不对就当场抛，不静默当成「没有收藏」。 */
  @Test
  fun aWrongShapeThrows() {
    assertFailsWith<ClassCastException> { mapOf(KEY_ANIME to "not a category").toCollectionCategories() }
  }

  private fun category(
    page: Int,
    pageTotal: Int,
    vararg ids: Int,
  ) = CollectionCategory(data = ids.map(::item), page = page, pageTotal = pageTotal)

  private fun item(id: Int) = CollectionItem(id = id, name = "subject$id")

  private companion object {
    const val KEY = "bgm:ekibun"
    const val KEY_ANIME = "动画|看过"
  }
}
