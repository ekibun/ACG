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

/** `Settings.*` 两个能力在 JS 侧的样子：写入后读回、`null` 删键、非字符串被拒。 */
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
      "the write must be awaited -- without await the next read races it and the persist order is undefined",
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

    assertNull(
      afterDelete,
      "a null value deletes the key, so reading back yields null (not an empty string, not undefined)",
    )
  }

  @Test
  fun missingKeyReadsAsNull() {
    assertNull(evalAsync("""return await Settings.get("probe.never-written");"""))
  }

  @Test
  fun nonStringValueIsRejected() {
    // 非字符串原样过桥，宿主收窄失败 ⇒ Promise 被拒绝
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
