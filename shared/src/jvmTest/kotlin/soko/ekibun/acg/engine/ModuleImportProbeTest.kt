package soko.ekibun.acg.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import soko.ekibun.quickjs.JSError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 探针：**引擎层**的动态 `import()` 到底能不能用。
 *
 * [JsEngine] 的模块处理器走 `Res` 读打包资源，比平台层的内存处理器多一层；而 `init.js`
 * 里那段「import 会把进程打崩」的注记指向的是 `JS_FreeRuntime` 收场。所以这条用例的
 * 关键不是前两步读到什么，而是 [AfterTest] 里那次 [JsEngine.reset] 能不能安然返回 ——
 * 崩在这里的话整个测试 JVM 直接没了。
 *
 * 三步都过 ⇒ 引擎可以走 `import()` 加载插件；任一步 abort ⇒ 先修模块加载链路。
 */
class ModuleImportProbeTest {
  /** 模块存在时：`import()` 求值出的命名空间要能读到 `export default`。 */
  @Test
  fun importsExistingBundledModule() {
    // crypto.js 是 crypto-js 的打包产物，`export default` 一个对象。
    assertEquals("object", evalAsync("""const m = await import("crypto"); return typeof m.default"""))
  }

  /** 模块不存在时：必须在 JS 侧抛异常，而不是 abort 或读到过期异常。 */
  @Test
  fun missingModuleRejectsWithJsError() {
    val failure =
      assertFailsWith<JSError> {
        runBlocking {
          val pending =
            assertIs<Deferred<Any?>>(JsEngine.instance.evaluate("import('no-such-module')", "<probe>"))
          pending.await()
        }
      }
    assertTrue(
      failure.message?.contains("no-such-module") == true ||
        failure.message?.contains("could not load module") == true,
      "expected module load failure, got: ${failure.message}",
    )
  }

  private fun evalAsync(body: String): Any? =
    runBlocking {
      assertIs<Deferred<Any?>>(JsEngine.instance.evaluate("(async () => { $body })()", "<probe>")).await()
    }

  /**
   * 收场：销毁 runtime。这是本探针真正的判据 —— `init.js` 记的第二种崩法
   * （`JS_FreeRuntime` 撞 `assert(list_empty(&rt->gc_obj_list))`）只在这里触发。
   */
  @AfterTest
  fun resetEngine() =
    runBlocking {
      JsEngine.instance.reset()
    }
}
