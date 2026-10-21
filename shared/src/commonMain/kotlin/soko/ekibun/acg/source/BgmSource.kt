package soko.ekibun.acg.source

import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.common.settingsStore

/** Bangumi 条目的收藏状态 —— v0 的整数枚举。 */
enum class BgmCollectionStatus(
  val value: Int,
  val label: String,
) {
  Wish(1, "想看"),
  Done(2, "看过"),
  Doing(3, "在看"),
  OnHold(4, "搁置"),
  Dropped(5, "抛弃"),
}

/** Bangumi 条目类型 —— v0 的整数枚举。 */
enum class BgmSubjectType(
  val value: Int,
  val label: String,
) {
  Book(1, "书籍"),
  Anime(2, "动画"),
  Music(3, "音乐"),
  Game(4, "游戏"),
  Real(6, "三次元"),
}

/** `/v0/me` 折出来的用户。 */
data class BgmUser(
  val id: Long,
  val username: String,
  val nickname: String,
  val avatarUrl: String?,
)

/** 一条收藏：界面画得出东西的字段；[score] / [rank] 为 0 表示没有。 */
data class BgmCollectionItem(
  val subjectId: Long,
  val name: String,
  val nameCn: String?,
  val coverUrl: String?,
  val score: Double,
  val rank: Int,
  val eps: Int,
  val epStatus: Int,
  val date: String?,
)

/** 一页收藏。[total] 是服务端给的总数，用来判「还有没有下一页」。 */
data class BgmCollectionPage(
  val total: Int,
  val items: List<BgmCollectionItem>,
)

/** Bangumi 客户端：把插件交回的 `Map` 收成强类型，收口宿主与插件共用的设置键名。 */
class BgmSource {
  companion object {
    const val CLIENT_ID_KEY = "bgm.client_id"
    const val CLIENT_SECRET_KEY = "bgm.client_secret"

    /** 读客户端凭据；任一项缺失或为空都返回 null。 */
    suspend fun credentials(): Pair<String, String>? {
      val stored = settingsStore.read()
      val clientId = stored[CLIENT_ID_KEY]?.takeIf(String::isNotEmpty) ?: return null
      val clientSecret = stored[CLIENT_SECRET_KEY]?.takeIf(String::isNotEmpty) ?: return null
      return clientId to clientSecret
    }

    /** 写凭据；传 `null` 即清掉该键。 */
    suspend fun setCredentials(
      clientId: String?,
      clientSecret: String?,
    ) {
      settingsStore.write(
        mapOf(
          CLIENT_ID_KEY to clientId,
          CLIENT_SECRET_KEY to clientSecret,
        ),
      )
    }
  }

  /** 登录态还在不在（`bgm.session` 里有 token 即为真）。 */
  suspend fun isLoggedIn(): Boolean = call("session") != null

  /** 走一遍授权码流程，返回当前用户。 */
  suspend fun login(): BgmUser {
    call("login")
    return me()
  }

  /** 退出登录：只清登录态。 */
  suspend fun logout() {
    call("logout")
  }

  /** 当前登录用户；插件抛出的异常原样上抛。 */
  suspend fun me(): BgmUser = bgmUserOf(call("me"))

  /** 某个用户的收藏一页。[offset] 从 0 起。 */
  suspend fun collections(
    username: String,
    subjectType: BgmSubjectType,
    status: BgmCollectionStatus,
    offset: Int,
    limit: Int,
  ): BgmCollectionPage =
    bgmCollectionPageOf(call("collections", username, subjectType.value, status.value, offset, limit))

  private suspend fun call(
    function: String,
    vararg args: Any?,
  ): Any? = JsEngine.instance.callPlugin("bgm", function, *args)
}

/** `/v0/me` 的应答 → [BgmUser]；字段缺失或类型不符时取默认值。 */
internal fun bgmUserOf(raw: Any?): BgmUser {
  val map = raw as? Map<*, *> ?: throw IllegalStateException("bangumi /v0/me did not return a user object")
  val avatar = (map["avatar"] as? Map<*, *>)?.get("large")
  return BgmUser(
    id = map["id"].asLongOrZero(),
    username = map["username"].asStringOrNull().orEmpty(),
    nickname = map["nickname"].asStringOrNull().orEmpty(),
    avatarUrl = avatar.asStringOrNull(),
  )
}

/** `/v0/users/{u}/collections` 的应答 → [BgmCollectionPage]。 */
internal fun bgmCollectionPageOf(raw: Any?): BgmCollectionPage {
  val map = raw as? Map<*, *> ?: throw IllegalStateException("bangumi collections did not return an object")
  val items = (map["data"] as? List<*>).orEmpty().mapNotNull { bgmCollectionItemOf(it) }
  return BgmCollectionPage(total = map["total"].asLongOrZero().toInt(), items = items)
}

private fun bgmCollectionItemOf(raw: Any?): BgmCollectionItem? {
  val map = raw as? Map<*, *> ?: return null
  val subject = map["subject"] as? Map<*, *> ?: return null
  val subjectId = (map["subject_id"] ?: subject["id"]).asLongOrZero()
  if (subjectId == 0L) return null
  return BgmCollectionItem(
    subjectId = subjectId,
    name = subject["name"].asStringOrNull().orEmpty(),
    nameCn = subject["name_cn"].asStringOrNull()?.takeIf(String::isNotEmpty),
    // 封面取 grid 档（约 100px）
    coverUrl = (subject["images"] as? Map<*, *>)?.get("grid").asStringOrNull(),
    score = subject["score"].asDoubleOrZero(),
    rank = subject["rank"].asLongOrZero().toInt(),
    eps = subject["eps"].asLongOrZero().toInt(),
    epStatus = map["ep_status"].asLongOrZero().toInt(),
    date = subject["date"].asStringOrNull()?.takeIf(String::isNotEmpty),
  )
}

private fun Any?.asStringOrNull(): String? = this as? String

private fun Any?.asLongOrZero(): Long =
  when (this) {
    is Long -> this
    is Int -> toLong()
    is Double -> toLong()
    is Boolean -> if (this) 1L else 0L
    else -> 0L
  }

private fun Any?.asDoubleOrZero(): Double =
  when (this) {
    is Double -> this
    is Long -> toDouble()
    is Int -> toDouble()
    else -> 0.0
  }
