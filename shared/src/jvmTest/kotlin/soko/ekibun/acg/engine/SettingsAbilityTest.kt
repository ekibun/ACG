package soko.ekibun.acg.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `settings.*` 这两个能力在 JS 侧的样子。
 *
 * 落点在 `acg.dataDir` 指的临时目录（见 `shared/build.gradle.kts` 的 jvmTest 配置），
 * 所以不走 `createSettingsStore` 那条注入路也一样安全。
 *
 * 用例之间共享同一份文件，谁都不假设自己是第一次跑：断言只比自己写入的那两个键。
 */
class SettingsAbilityTest {
  @Test
  fun setThenGetRoundTrips() {
    assertEquals(
      "client-1",
      evalAsync(
        """
        await settings.set("probe.client_id", "client-1");
        return await settings.get("probe.client_id");
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
        await settings.set("probe.session", "{}");
        await settings.set("probe.session", null);
        return await settings.get("probe.session");
        """,
      )

    assertNull(afterDelete, "值为 null 是删键，读回来该是 null（不是空串、也不是 undefined）")
  }

  @Test
  fun missingKeyReadsAsNull() {
    assertNull(evalAsync("""return await settings.get("probe.never-written");"""))
  }

  @Test
  fun nonStringValueIsCoercedByTheGlobal() {
    // 插件够不着 `_binding`（它是 init.js 那个工厂函数的形参），只能经全局 —— 全局这一层
    // 已经把值折成字符串，所以 Kotlin 侧的收窄永远只面对 String 或 null。
    assertEquals(
      "42",
      evalAsync(
        """
        await settings.set("probe.number", 42);
        return await settings.get("probe.number");
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
