package soko.ekibun.acg.source

import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.engine.JsEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 插件应答 → 强类型的收窄。
 *
 * 夹具走**真的**桥（JS 求值一份对象字面量再交给 Kotlin），不是手搓 `Map`：`jsToJava`
 * 按 JS 数字的内部标签分派（整数 → `Long`、小数 → `Double`），手搓的 Map 恰好绕开了
 * 最容易被写错的那条。夹具内容照 `GET /v0/users/{u}/collections` 的实测应答剪裁。
 */
class BgmSourceMappingTest {
  @Test
  fun mapsCollectionPage() {
    val page = bgmCollectionPageOf(evalFixture(COLLECTIONS_FIXTURE))

    assertEquals(249, page.total)
    assertEquals(2, page.items.size)

    val first = page.items[0]
    assertEquals(497110L, first.subjectId)
    assertEquals("ニンジャバットマン対ヤクザリーグ", first.name)
    assertEquals("忍者蝙蝠侠对极道联盟", first.nameCn)
    assertEquals("https://lain.bgm.tv/r/100/pic/cover/l/65/fb/497110_X6ZIL.jpg", first.coverUrl)
    assertEquals(6.9, first.score, "小数走 JS_TAG_FLOAT64")
    assertEquals(2876, first.rank)
    assertEquals(1, first.eps)
    assertEquals(0, first.epStatus)
    assertEquals("2025-03-21", first.date)
  }

  @Test
  fun mapsWholeNumberScoreAndProgress() {
    val second = bgmCollectionPageOf(evalFixture(COLLECTIONS_FIXTURE)).items[1]

    // 同一个字段这次是整数（`7` 而不是 `7.5`）—— 桥给的是 Long，读取器必须两个标签都认。
    assertEquals(7.0, second.score)
    assertEquals(12, second.eps)
    assertEquals(5, second.epStatus)
    assertNull(second.nameCn, "name_cn 是空串：按「没有中文名」处理，让界面回退到原名")
  }

  @Test
  fun missingFieldsDoNotBreakThePage() {
    val page = bgmCollectionPageOf(evalFixture("""{"data":[{"subject":{"id":1,"name":"x"}}],"total":1}"""))

    val item = page.items.single()
    assertEquals(0.0, item.score)
    assertEquals(0, item.rank)
    assertNull(item.coverUrl)
    assertNull(item.date)
  }

  @Test
  fun itemsWithoutSubjectAreDropped() {
    // 没有 subject / 没有 id 的项画不出来，跳过而不是塞一个空壳进列表。
    val page = bgmCollectionPageOf(evalFixture("""{"data":[{"rate":5},{"subject":{"id":0}}],"total":2}"""))

    assertTrue(page.items.isEmpty(), "实际留下：${page.items}")
  }

  @Test
  fun mapsUser() {
    val user = bgmUserOf(evalFixture(USER_FIXTURE))

    assertEquals(12345L, user.id)
    assertEquals("probe", user.username)
    assertEquals("探针", user.nickname)
    assertEquals("https://lain.bgm.tv/a.jpg", user.avatarUrl)
  }

  @Test
  fun enumValuesMatchTheV0Api() {
    // 整数对错一个就会静默查错一档（比如把「在看」查成「看过」），而接口不会报错。
    assertEquals(listOf(1, 2, 3, 4, 5), BgmCollectionStatus.entries.map { it.value })
    assertEquals(listOf(1, 2, 3, 4, 6), BgmSubjectType.entries.map { it.value })
  }

  /** 夹具是 JSON（JS 对象字面量的子集），直接当表达式求值 —— 这样过的是真桥。 */
  private fun evalFixture(json: String): Any? = runBlocking { JsEngine.instance.evaluate("($json)") }

  @AfterTest
  fun resetEngine() =
    runBlocking {
      JsEngine.instance.reset()
    }
}

/** 用户夹具：头像是**嵌套对象**（`avatar.large`），取值要多走一层。 */
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
