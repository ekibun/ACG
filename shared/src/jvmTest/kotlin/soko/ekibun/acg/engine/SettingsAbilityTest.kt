package soko.ekibun.acg.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `Settings.*` 这两个能力在 JS 侧的样子。落点由 [TestPaths] 注入到临时目录。
 *
 * 用例之间共享同一份文件，谁都不假设自己是第一次跑：断言只比自己写入的那两个键。
 */
class SettingsAbilityTest {
  @BeforeTest
  fun initPaths() {
    TestPaths.ensureInitialized()
  }

  @Test
  fun setThenGetRoundTrips() {
    assertEquals(
      "client-1",
      evalAsync(
        """
        await Settings.set("probe.client_id", "client-1");
        return await Settings.get("probe.client_id");
        """,
      ),
      "写必须 await —— 不 await 的话下一句读与它并发，谁先落盘不确定",
    )
  }

  @Test
  fun nullValueDeletesTheKey() {
    val afterDelete =
      evalAsync(
        """
        await Settings.set("probe.session", "{}");
        await Settings.set("probe.session", null);
        return await Settings.get("probe.session");
        """,
      )

    assertNull(afterDelete, "值为 null 是删键，读回来该是 null（不是空串、也不是 undefined）")
  }

  @Test
  fun missingKeyReadsAsNull() {
    assertNull(evalAsync("""return await Settings.get("probe.never-written");"""))
  }

  @Test
  fun nonStringValueIsRejected() {
    // 全局这层不做类型转换：42 原样过桥，由宿主的 `as String?` 收窄失败 ⇒ Promise 被拒绝。
    // 回空值或悄悄折成 "42" 都会让插件以为写成功了。
    assertEquals(
      "caught",
      evalAsync(
        """
        try {
          await Settings.set("probe.number", 42);
          return "no";
        } catch (e) {
          return "caught";
        }
        """,
      ),
    )
  }

  private fun evalAsync(body: String): Any? =
    runBlocking {
      assertIs<Deferred<Any?>>(
        JsEngine.instance.evaluate("(async () => { $body })()", "<settings>"),
      ).await()
    }

  @AfterTest
  fun resetEngine() =
    runBlocking {
      JsEngine.instance.reset()
    }
}
