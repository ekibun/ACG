package soko.ekibun.acg.source

import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.TestEnv
import soko.ekibun.acg.engine.JsEngine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 插件应答 → 强类型的收窄：夹具经真桥求值后逐字段比对。 */
class BgmSourceTest {
  @BeforeTest
  fun initPaths() {
    TestEnv.ensurePaths()
  }

  /** 分页夹具逐字段比对。第二条 `score` 是整数：JS 侧可能给 INT 或 FLOAT64，只认后者会静默变成 0。 */
  @Test(timeout = 60000)
  fun mapsEveryCollectionItem() {
    val page = bgmCollectionPageOf(evalFixture(COLLECTIONS_FIXTURE))

    assertEquals(249, page.total)
    assertEquals(2, page.items.size)

    val first = page.items[0]
    assertEquals(497110L, first.subjectId)
    assertEquals("ニンジャバットマン対ヤクザリーグ", first.name)
    assertEquals("忍者蝙蝠侠对极道联盟", first.nameCn)
    assertEquals(
      "https://lain.bgm.tv/r/100/pic/cover/l/65/fb/497110_X6ZIL.jpg",
      first.coverUrl,
      "the cover comes from images.grid",
    )
    assertEquals(6.9, first.score, "a fractional value comes back as JS_TAG_FLOAT64")
    assertEquals(2876, first.rank)
    assertEquals(1, first.eps)
    assertEquals(0, first.epStatus)
    assertEquals("2025-03-21", first.date)

    val second = page.items[1]
    assertEquals(7.0, second.score, "the same field as an INTEGER this time -- both tags must be accepted")
    assertEquals(12, second.eps)
    assertEquals(5, second.epStatus)
    assertNull(second.nameCn, "an empty name_cn means 'no localized name', so the UI falls back to the original name")
  }

  /** 缺字段的项要能画（补默认值），根本画不出来的项（没有 subject / 没有 id）要跳过。 */
  @Test(timeout = 60000)
  fun degenerateItemsDegradeGracefully() {
    val sparse =
      bgmCollectionPageOf(evalFixture("""{"data":[{"subject":{"id":1,"name":"x"}}],"total":1}"""))
    val item = sparse.items.single()
    assertEquals(0.0, item.score, "a missing score falls back to 0")
    assertEquals(0, item.rank)
    assertNull(item.coverUrl)
    assertNull(item.date)

    val unusable =
      bgmCollectionPageOf(evalFixture("""{"data":[{"rate":5},{"subject":{"id":0}}],"total":2}"""))
    assertTrue(unusable.items.isEmpty(), "items without a subject id cannot be drawn: ${unusable.items}")
  }

  @Test(timeout = 60000)
  fun mapsUser() {
    val user = bgmUserOf(evalFixture(USER_FIXTURE))

    assertEquals(12345L, user.id)
    assertEquals("probe", user.username)
    assertEquals("探针", user.nickname)
    assertEquals("https://lain.bgm.tv/a.jpg", user.avatarUrl)
  }

  @Test(timeout = 60000)
  fun enumValuesMatchTheV0Api() {
    // 整数对错一个就会静默查错一档
    assertEquals(listOf(1, 2, 3, 4, 5), BgmCollectionStatus.entries.map { it.value })
    assertEquals(listOf(1, 2, 3, 4, 6), BgmSubjectType.entries.map { it.value })
  }

  /** 夹具是 JSON，直接当表达式求值，过的是真桥。 */
  private fun evalFixture(json: String): Any? = runBlocking { JsEngine.instance.evaluate("($json)") }

  @AfterTest
  fun resetEngine() =
    runBlocking {
      JsEngine.instance.reset()
    }
}

/** 用户夹具：头像是嵌套对象（`avatar.large`）。 */
private const val USER_FIXTURE = """
{"id":12345,"username":"probe","nickname":"探针","avatar":{"large":"https://lain.bgm.tv/a.jpg"}}
"""

private const val COLLECTIONS_FIXTURE = """
{
  "data": [
    {
      "updated_at": "2026-09-15T03:27:37+08:00",
      "comment": null,
      "tags": ["日本", "2025"],
      "subject": {
        "date": "2025-03-21",
        "images": {
          "small": "https://lain.bgm.tv/r/200/pic/cover/l/65/fb/497110_X6ZIL.jpg",
          "grid": "https://lain.bgm.tv/r/100/pic/cover/l/65/fb/497110_X6ZIL.jpg",
          "large": "https://lain.bgm.tv/pic/cover/l/65/fb/497110_X6ZIL.jpg"
        },
        "name": "ニンジャバットマン対ヤクザリーグ",
        "name_cn": "忍者蝙蝠侠对极道联盟",
        "short_summary": "蝙蝠侠与盟友从过去归来仅一天……",
        "score": 6.9,
        "type": 2,
        "id": 497110,
        "eps": 1,
        "volumes": 0,
        "collection_total": 321,
        "rank": 2876
      },
      "subject_id": 497110,
      "vol_status": 0,
      "ep_status": 0,
      "subject_type": 2,
      "type": 2,
      "rate": 6,
      "private": false
    },
    {
      "updated_at": "2026-09-15T03:27:37+08:00",
      "comment": null,
      "tags": [],
      "subject": {
        "date": "2024-10-06",
        "images": { "grid": "https://lain.bgm.tv/r/100/pic/cover/l/aa/bb/2.jpg" },
        "name": "ダンダダン",
        "name_cn": "",
        "score": 7,
        "type": 2,
        "id": 400602,
        "eps": 12,
        "volumes": 0,
        "collection_total": 900,
        "rank": 100
      },
      "subject_id": 400602,
      "vol_status": 0,
      "ep_status": 5,
      "subject_type": 2,
      "type": 3,
      "rate": 0,
      "private": false
    }
  ],
  "total": 249,
  "limit": 2,
  "offset": 0
}
"""
