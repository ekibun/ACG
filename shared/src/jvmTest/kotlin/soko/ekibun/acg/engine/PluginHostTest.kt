package soko.ekibun.acg.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.TestPaths
import soko.ekibun.common.settingsStore
import soko.ekibun.quickjs.JSError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [JsEngine.callPlugin] 这条通路：`import()` 取模块 → 取成员 → 调用 → 收 Promise。
 * 顺带钉住引擎层自己的模块加载（`Res` 读打包资源那一层，平台层的 `QuickJSTest` 看不到）。
 *
 * 打的是**真的** `files/js/module/bgm.js`（不是测试专用的假模块，免得通路在真模块上
 * 另有一副样子），但只调它那些不发网络的部分（纯函数、设置读写）。
 */
class PluginHostTest {
  /**
   * 会话存档的键名在两边各写一份（插件里的 `SESSION_KEY`、这里的字面量）—— 这正是要钉住的：
   * 宿主写得进去、插件读得出来。宿主只写凭据、插件只写登录态，所以会话键在 Kotlin 侧没有常量。
   */
  private val sessionKey = "bgm.session"

  @BeforeTest
  fun initPaths() {
    TestPaths.ensureInitialized()
  }

  @Test
  fun callsExportedFunctionAndUnwrapsItsPromise() {
    val params =
      call("parseCallback", "http://127.0.0.1:51987/9f3a4b?code=4808dfb7&state=probe%26x")

    assertEquals(
      mapOf("code" to "4808dfb7", "state" to "probe&x"),
      params,
      "decode segment by segment: decoding the whole string turns a %26 in a value into &",
    )
  }

  @Test
  fun missingModuleSurfacesJsError() {
    val failure = assertFailsWith<JSError> { call("parseCallback", "x", module = "no-such-plugin") }

    assertTrue(
      failure.message?.contains("no-such-plugin") == true,
      "the error must name the module: ${failure.message}",
    )
  }

  /**
   * 模块加载走的是 [JsEngine] 自己的处理器（`Res` 读打包资源），比平台层的内存处理器多一层
   * —— `QuickJSTest.missingModuleThrows` 只钉平台层那一层。这里钉引擎层：模块缺失时
   * JS 侧必须拿到可捕获的异常（而不是挂起或读到过期异常），且那一下之后 runtime 仍能正常销毁。
   *
   * `@AfterTest` 的 `reset()` 就是后半个判据：`import()` 打乱引用计数时，
   * `JS_FreeRuntime` 会撞 `assert(list_empty(&rt->gc_obj_list))` 直接 abort 整个测试进程。
   */
  @Test
  fun importOfMissingModuleRejectsWithJsError() {
    val failure =
      assertFailsWith<JSError> {
        runBlocking {
          val pending =
            assertIs<Deferred<Any?>>(JsEngine.instance.evaluate("import('no-such-module')", "<plugin-host>"))
          pending.await()
        }
      }
    assertTrue(
      failure.message?.contains("no-such-module") == true ||
        failure.message?.contains("could not load module") == true,
      "expected module load failure, got: ${failure.message}",
    )
  }

  @Test
  fun exportThatIsNotAFunctionSurfacesJsError() {
    // crypto.js 只 `export default` 一个对象；`callPlugin` 要的是函数。
    val failure = assertFailsWith<JSError> { call("default", module = "crypto") }

    assertTrue(
      failure.message?.contains("crypto") == true && failure.message?.contains("default") == true,
      "the error must name both the module and the member: ${failure.message}",
    )
  }

  @Test
  fun readsBackTheSessionItStored() =
    runBlocking {
      val engine = JsEngine.instance
      // 从干净状态起：落盘是跨测试进程留着的，上一次运行可能留下了存档。
      engine.callPlugin("bgm", "logout")
      assertNull(engine.callPlugin("bgm", "session"), "after logout the plugin should be logged out")

      settingsStore.write(mapOf(sessionKey to """{"access_token":"probe","refresh_token":"r"}"""))
      val session = engine.callPlugin("bgm", "session")
      assertEquals("probe", assertIs<Map<*, *>>(session)["access_token"])

      engine.callPlugin("bgm", "logout")
      assertNull(
        engine.callPlugin("bgm", "session"),
        "logout must delete the stored session, not leave an empty object",
      )
    }

  @Test
  fun treatsCorruptStoredSessionAsLoggedOut() =
    runBlocking {
      settingsStore.write(mapOf(sessionKey to "{not json"))
      assertNull(
        JsEngine.instance.callPlugin("bgm", "session"),
        "a corrupt stored session must be treated as logged out instead of throwing at the caller",
      )
      JsEngine.instance.callPlugin("bgm", "logout")
      // 结束在断言上（JUnit 4 要求测试方法返回 void，尾表达式是 `callPlugin` 就推不出 Unit）。
      assertNull(
        JsEngine.instance.callPlugin("bgm", "session"),
        "the corrupt stored session must be purged so it does not poison the next case",
      )
    }

  private fun call(
    function: String,
    vararg args: Any?,
    module: String = "bgm",
  ): Any? =
    runBlocking {
      JsEngine.instance.callPlugin(module, function, *args)
    }

  @AfterTest
  fun resetEngine() =
    runBlocking {
      JsEngine.instance.reset()
    }
}
