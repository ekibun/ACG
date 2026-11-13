package soko.ekibun.acg.data

/** 收藏列表里的一条。[summary] 是列表页那行灰字，不是简介；[progress] 只有主页追番块给。 */
data class CollectionItem(
  val id: Int,
  val name: String,
  val nameCn: String? = null,
  val summary: String? = null,
  val image: String? = null,
  val progress: String? = null,
)

/** 一次读失败。[page] 既是坏掉的那页，也是重试该要的那页。 */
data class CollectionError(
  val message: String,
  val page: Int,
)

/**
 * 一档收藏的状态：读到哪、在飞哪页、哪页失败。界面要的判断全由它推出，不另存一份。
 *
 * [page] 是已并进 [data] 的最后一页（0 是还没读过）；[pageTotal] 是插件给的总页数（0 是不知道）；
 * 两者都不是「正在读的那一页」，那是 [loadingPage]。
 *
 * 名字避开 `Scope`（模块里到处是 `CoroutineScope`）；「档」是插件键表里的一格（`动画|在看`）。
 */
data class CollectionCategory(
  val data: List<CollectionItem> = emptyList(),
  val page: Int = 0,
  val pageTotal: Int = 0,
  /** 在飞的是哪一页；`null` 就是这一档没有人读。 */
  val loadingPage: Int? = null,
  /** 上一次失败；成功读一页就清掉。 */
  val error: CollectionError? = null,
) {
  /** 读到过一页、而且还没到底。 */
  val hasMore: Boolean get() = page in 1 until pageTotal

  /** 顶上那个圈：整档重读，而且手上已经有内容。 */
  val refreshing: Boolean get() = loadingPage == 1 && data.isNotEmpty()

  /** 在续读后面的页。 */
  val loadingMore: Boolean get() = loadingPage != null && loadingPage > 1

  /** 刷新失败：内容还是好的，只是这一轮第一页没读到。 */
  val refreshError: CollectionError? get() = error?.takeIf { it.page == 1 && data.isNotEmpty() }
}

/** 收藏页的一份状态：插件给的档加每档的进度，跟着账号走。 */
data class CollectionTable(
  /** 表属于哪个账号（`Account.key`）：换账号整张换掉。 */
  val accountKey: String? = null,
  /** 键 -> 读到哪。顺序即插件给的顺序（第一个就是默认档）；`page == 0` 是「知道有这一档，还没读」。 */
  val categories: Map<String, CollectionCategory> = emptyMap(),
  /** 整份读（键表）在飞。 */
  val loading: Boolean = false,
  /** 整份都读不到的原因：连一个默认档都没有，页面只能显示这一条。 */
  val error: String? = null,
)

/** 标成「正在读第 [page] 页」并作废旧失败：整份读（[key] 为 `null`）连表上的错误一起清。 */
internal fun CollectionTable.begin(
  key: String?,
  page: Int,
): CollectionTable =
  if (key == null) {
    copy(loading = true, error = null)
  } else {
    val category = (categories[key] ?: CollectionCategory()).copy(loadingPage = page, error = null)
    copy(categories = categories + (key to category))
  }

/**
 * 把插件交回的一批档并进表里：`page <= 1` 的整档直接覆盖，后面的页接在已有的后面
 * （插件给的 `data` 是「这一页」，累积是宿主的事）。两条路都按 `id` 去重 —— 跨页与同页都可能重复，
 * 列表拿 `id` 当 key，重复即崩。值是 `null` 的档只保键、不动数据：那是「这次没读到」，不是「读到空了」。
 */
internal fun CollectionTable.merge(categories: Map<String, CollectionCategory?>): CollectionTable {
  val next = LinkedHashMap(this.categories)
  for ((key, category) in categories) {
    val previous = next[key]
    next[key] =
      when {
        category == null -> previous ?: CollectionCategory()
        // 整档也去重：同一页里就重复的话（插件那边并列了两遍），照着画会撞 key
        category.page <= 1 -> category.copy(data = category.data.distinctBy { it.id })
        else -> category.copy(data = (previous?.data.orEmpty() + category.data).distinctBy { it.id })
      }
  }
  return copy(categories = next)
}

/** 记一次失败：只挂出错的这一档，页号与已读数据都不动；整份读的失败挂在表的 [CollectionTable.error] 上。 */
internal fun CollectionTable.fail(
  key: String?,
  page: Int,
  message: String,
): CollectionTable =
  if (key == null) {
    copy(loading = false, error = message)
  } else {
    val category =
      (categories[key] ?: CollectionCategory()).copy(
        loadingPage = null,
        error = CollectionError(message, page),
      )
    copy(categories = categories + (key to category))
  }

/**
 * 插件交回的一批档：`{ "<键>": { data, page, pageTotal } }`，值是 `null` 的是「还没读」。
 * 桥给的是 `LinkedHashMap` / `ArrayList` 而非 [CollectionCategory]，逐字段硬转，形状不对当场抛。
 */
internal fun Map<*, *>.toCollectionCategories(): Map<String, CollectionCategory?> {
  val categories = LinkedHashMap<String, CollectionCategory?>()
  for ((key, value) in this) {
    val category = value as Map<*, *>?
    categories[key as String] =
      category?.let {
        CollectionCategory(
          data =
            (it["data"] as List<*>).map { item ->
              val fields = item as Map<*, *>
              CollectionItem(
                id = (fields["id"] as Number).toInt(),
                name = fields["name"] as String,
                nameCn = fields["nameCn"] as String?,
                summary = fields["summary"] as String?,
                image = fields["image"] as String?,
                progress = fields["progress"] as String?,
              )
            },
          page = (it["page"] as Number).toInt(),
          pageTotal = (it["pageTotal"] as Number).toInt(),
        )
      }
  }
  return categories
}
