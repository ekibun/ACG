package soko.ekibun.acg.engine

import kotlinx.coroutines.runBlocking
import soko.ekibun.common.settingsStore
import soko.ekibun.quickjs.JSError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [JsEngine.callPlugin] 这条通路：`import()` 取模块 → 取成员 → 调用 → 收 Promise。
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

  @Test
  fun callsExportedFunctionAndUnwrapsItsPromise() {
    val params =
      call("parseCallback", "http://127.0.0.1:51987/9f3a4b?code=4808dfb7&state=probe%26x")

    assertEquals(
      mapOf("code" to "4808dfb7", "state" to "probe&x"),
      params,
      "回调要逐段解码 —— 整串解会把值里的 %26 变成 &、把参数切开",
    )
  }

  @Test
  fun missingModuleSurfacesJsError() {
    val failure = assertFailsWith<JSError> { call("parseCallback", "x", module = "no-such-plugin") }

    assertTrue(
      failure.message?.contains("no-such-plugin") == true,
      "报错要说清是哪个模块：${failure.message}",
    )
  }

  @Test
  fun exportThatIsNotAFunctionSurfacesJsError() {
    // crypto.js 只 `export default` 一个对象；`callPlugin` 要的是函数。
    val failure = assertFailsWith<JSError> { call("default", module = "crypto") }

    assertTrue(
      failure.message?.contains("crypto") == true && failure.message?.contains("default") == true,
      "报错要同时点出模块与成员：${failure.message}",
    )
  }

  @Test
  fun readsBackTheSessionItStored() =
    runBlocking {
      val engine = JsEngine.instance
      // 从干净状态起：落盘是跨测试进程留着的，上一次运行可能留下了存档。
      engine.callPlugin("bgm", "logout")
      assertNull(engine.callPlugin("bgm", "session"), "清过之后就该是未登录")

      settingsStore.write(mapOf(sessionKey to """{"access_token":"probe","refresh_token":"r"}"""))
      val session = engine.callPlugin("bgm", "session")
      assertEquals("probe", assertIs<Map<*, *>>(session)["access_token"])

      engine.callPlugin("bgm", "logout")
      assertNull(engine.callPlugin("bgm", "session"), "退出登录要把存档删掉，不是留个空对象")
    }

  @Test
  fun treatsCorruptStoredSessionAsLoggedOut() =
    runBlocking {
      settingsStore.write(mapOf(sessionKey to "{not json"))
      assertNull(JsEngine.instance.callPlugin("bgm", "session"), "存档坏了要当没登录，而不是把异常抛给调用方")
      JsEngine.instance.callPlugin("bgm", "logout")
      // 结束在断言上（JUnit 4 要求测试方法返回 void，尾表达式是 `callPlugin` 就推不出 Unit）。
      assertNull(JsEngine.instance.callPlugin("bgm", "session"), "坏了的那份存档要被清掉，不留着恶心下一条用例")
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
