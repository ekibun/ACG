package soko.ekibun.acg.source

import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.common.settingsStore

/** Bangumi 条目的收藏状态 —— **v0 的整数**枚举，不是老 API 那套 `wish` / `do` 字符串。 */
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

/** Bangumi 条目类型 —— v0 的整数枚举；没有 5（「人物」那档已废）。 */
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

/**
 * 一条收藏 —— 只留界面画得出东西的字段。
 *
 * [score] / [rank] 为 0 表示没有评分 / 没有排名，界面上要按「没有」画而不是画个 0。
 */
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

/**
 * Bangumi 客户端 —— 薄薄一层，真正的服务商知识在 `files/js/module/bgm.js` 里。
 *
 * 这一层只干两件事：
 * 1. 把插件交回来的 `Map` 收成强类型（JS 那侧拼串没有编译期检查，形状变化只会在运行期暴露）；
 * 2. 收口宿主与插件之间的键空间 —— **凭据由宿主写、登录态由插件写**（见 `docs/design/bgm-plugin.md`），
 *    所以这两个键名在两边各有一份，改一处就得改两处。
 */
class BgmSource {
  companion object {
    const val CLIENT_ID_KEY = "bgm.client_id"
    const val CLIENT_SECRET_KEY = "bgm.client_secret"

    /**
     * 读凭据。两个键缺任一个都算没配 —— 少了 secret 换不到 token，报错要报在用户填的地方。
     */
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

  /** 登录态还在不在（`bgm.session` 有没有存下 token）。不校验 token 还能不能用。 */
  suspend fun isLoggedIn(): Boolean = call("session") != null

  /** 走一遍授权码流程，并顺手问一次 `me` 把用户信息一并交回去。 */
  suspend fun login(): BgmUser {
    call("login")
    return me()
  }

  /** 退出登录：只清登录态，凭据留着。 */
  suspend fun logout() {
    call("logout")
  }

  /** 当前登录用户。没登录时插件会抛，异常一路到这里。 */
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

/**
 * `/v0/me` 的应答 → [BgmUser]。
 *
 * 字段缺了或换了类型一律当**没有**，不抛：接口加字段是常事，为它把整页打不开不划算。
 * 只有「整个应答根本不是个对象」才算真出错。
 */
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

/**
 * `/v0/users/{u}/collections` 的应答 → [BgmCollectionPage]。
 *
 * 每项的形状是 `{subject: {…}, subject_id, type, rate, ep_status, …}` —— 条目的名字、封面、
 * 评分挂在 `subject` 里，而**用户自己的进度**（`ep_status`）在**外层**，两边都要读。
 */
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
    // 封面用 grid（100px 那档）：列表里就是小图，取 large 白等一趟大文件。
    coverUrl = (subject["images"] as? Map<*, *>)?.get("grid").asStringOrNull(),
    score = subject["score"].asDoubleOrZero(),
    rank = subject["rank"].asLongOrZero().toInt(),
    eps = subject["eps"].asLongOrZero().toInt(),
    epStatus = map["ep_status"].asLongOrZero().toInt(),
    date = subject["date"].asStringOrNull()?.takeIf(String::isNotEmpty),
  )
}

/*
 * 桥把 JS 数字按**内部标签**分派（`JS_TAG_INT` → Long、`JS_TAG_FLOAT64` → Double），
 * 而 `JSON.parse` 对 `7` 与 `7.0` 给出的标签不同 —— 同一个字段两次请求可能落到不同分支，
 * 所以读取器必须两个都认。类型不对当 0 / 没有，理由见 bgmUserOf 的 KDoc。
 */

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
