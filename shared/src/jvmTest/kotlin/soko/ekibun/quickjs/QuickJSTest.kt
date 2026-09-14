package soko.ekibun.quickjs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 参照 https://github.com/ekibun/flutter_qjs 的 test/flutter_qjs_test.dart 编写的用例集。
 *
 * 目标是把本工程的 quickjs 桥接层与 flutter_qjs 已确立的语义对齐，逐项验证：
 * - evaluate（全局 / module）与异常抛出
 * - 原始类型、ArrayBuffer、数组、Map 的往返转换
 * - 循环引用对象（对应 javaToJsImpl 的 cache 保护）
 * - 函数 / thisVal、JSInvokable、Java 异常回流
 * - Promise <-> Deferred 双向桥接
 * - 模块加载（moduleHandler）
 */
class QuickJSTest {

  private fun context(moduleHandler: ((String) -> String?)? = null) =
    QuickJS.Context(moduleHandler)

  /**
   * `async (a) => a` 在 JS 侧返回 Promise，桥接层会转成 Deferred，
   * 所以调用此类函数要 await 才能拿到真正的值（与 flutter_qjs 的
   * `await testWrap.invoke([...])` 一致）。
   */
  private fun callAsync(fn: JSFunction, vararg argv: Any?): Any? =
    runBlocking { assertIs<Deferred<Any?>>(fn.invoke(*argv)).await() }

  @Test
  fun evaluatePrimitives() {
    val ctx = context()
    try {
      assertEquals(1L, ctx.evaluate("1"))
      assertEquals("str", ctx.evaluate("'str'"))
      assertEquals(true, ctx.evaluate("true"))
      assertEquals(0.5, ctx.evaluate("0.5"))
      assertEquals(null, ctx.evaluate("null"))
    } finally {
      ctx.close()
    }
  }

  @Test
  fun evaluateThrowsJSError() {
    val ctx = context()
    try {
      val err = assertFailsWith<JSError> { ctx.evaluate("throw new Error('boom')") }
        assertEquals(
            err.message?.contains("boom"),
            true,
            "message should carry the JS text, got: ${err.message}"
        )
    } finally {
      ctx.close()
    }
  }

  /** flutter_qjs: 'stack overflow' -> a=()=>a();a() */
  @Test
  fun stackOverflowIsReported() {
    val ctx = context()
    try {
      val err = assertFailsWith<JSError> { ctx.evaluate("a=()=>a();a();") }
        assertEquals(
            err.message?.contains("stack overflow", ignoreCase = true),
            true,
            "expected a stack overflow error, got: ${err.message}"
        )
    } finally {
      ctx.close()
    }
  }

  /** flutter_qjs: 'data conversion' 的 wrap null / primities 部分 */
  @Test
  fun javaToJsRoundTripPrimitives() {
    val ctx = context()
    try {
        assertIs<JSFunction>(ctx.evaluate("async (a) => a", name = "<testWrap>")).use { wrap ->
            assertEquals(null, callAsync(wrap, null))
            val primitives = listOf<Any?>(0, 1, 0.1, true, false, "str")
            val wrapped = callAsync(wrap, primitives) as Array<*>
            assertEquals(primitives.size, wrapped.size)
            for (i in primitives.indices) {
                val expected = primitives[i]
                val actual = wrapped[i]
                // 整数经 JS_TAG_INT 回来是 Long，小数经 JS_TAG_FLOAT64 回来是 Double
                if (expected is Int) assertEquals(expected.toLong(), actual) else assertEquals(expected, actual)
            }
        }
    } finally {
      ctx.close()
    }
  }

  /**
   * flutter_qjs: 'data conversion' 的 recursive object 部分。
   *
   * 只断言**功能**（`a['a'] === a`），泄漏检查交给 [referenceLeak] —— 这与
   * flutter_qjs 的分工一致：它的 `recursive object` expect 在 `qjs.close()` 之前
   * 完成，而泄漏由独立的 `test('reference leak')` 以异常形式暴露。
   */
  @Test
  fun recursiveObjectKeepsIdentity() {
    val ctx = context()
    try {
      val wrap = assertIs<JSFunction>(ctx.evaluate("async (a) => a", name = "<testWrap>"))
      try {
        // a['a'] = a —— 依赖 javaToJsImpl 的 cache 才不会在转换时无限递归
        val a = HashMap<String, Any?>()
        a["a"] = a
        val wrapped = assertIs<JSObject>(callAsync(wrap, a))
        try {
          assertTrue(wrapped["a"] === wrapped, "recursive reference must map back to the same object")
        } finally {
          // jsToJava 出来的 JSObject 也持有 JS 引用，必须显式释放
          wrapped.close()
        }
      } finally {
        wrap.close()
      }
    } finally {
      // 显式归还之后不该有残留；有残留会在这里抛出来（而不是让进程 abort）
      ctx.closeAndCheckLeaks()
    }
  }

  /**
   * flutter_qjs: `test('reference leak')`。
   *
   * 那条测试的用意是：**故意**不释放 `()=>{}` 的返回值，然后断言 close 时抛出
   * `reference leak:` 前缀的异常。这验证的是「泄漏不会静默变成 C 层断言」。
   *
   * 对照本工程重构前的表现：同样场景会让 QuickJS 在 `JS_FreeRuntime` 里
   * `assert(list_empty(&rt->gc_obj_list))` 失败并 `abort()`，整个测试进程死掉，
   * 根本轮不到断言。现在它变成了一条普通的、可捕获的 JSError。
   */
  @Test
  fun referenceLeak() {
    val ctx = context()
    // 故意不 close()：这个 JSFunction 包装在 close 时仍被登记着
    ctx.evaluate("()=>{}", name = "<eval>")
    val err = assertFailsWith<JSError> { ctx.closeAndCheckLeaks() }
    assertTrue(
      err.message?.startsWith("reference leak:") == true,
      "expected a reference leak report, got: ${err.message}"
    )
  }

  /** 正常路径不该报泄漏：所有包装都显式归还后，closeAndCheckLeaks 必须安静通过 */
  @Test
  fun noLeakWhenEverythingIsReleased() {
    val ctx = context()
    val fn = assertIs<JSFunction>(ctx.evaluate("()=>1", name = "<eval>"))
    assertEquals(1L, fn.invoke())
    fn.close()
    // 不抛异常即通过
    ctx.closeAndCheckLeaks()
  }

  /** dup/free 的计数语义：多持有一次就要多归还一次 */
  @Test
  fun dupAndFreeBalance() {
    val ctx = context()
    try {
      val fn = assertIs<JSFunction>(ctx.evaluate("()=>1", name = "<eval>"))
      assertEquals(1, fn.refCount)
      fn.dup()
      assertEquals(2, fn.refCount)
      fn.free()
      assertEquals(1, fn.refCount)
      assertTrue(!fn.released, "still held once, must not be released yet")
      fn.free()
      assertTrue(fn.released, "refcount hit zero, must be released")
      // 归零后重复 free 必须安全（重构前这里会双重释放 native 句柄）
      fn.free()
      fn.close()
    } finally {
      ctx.closeAndCheckLeaks()
    }
  }

  /** JS 侧调用 Java 函数时，参数与 thisVal 都要原样送达 */
  @Test
  fun jsInvokableReceivesThisValAndArgs() {
    val ctx = context()
    try {
      val seenThis = ArrayList<Any?>()
      val seenArgs = ArrayList<List<Any?>>()
      val func = object : JSInvokable {
        override fun invoke(vararg argv: Any?, thisVal: Any?): Any? {
          seenThis += thisVal
          seenArgs += argv.toList()
          return "ok"
        }
      }
      val call = assertIs<JSFunction>(
        ctx.evaluate("(function (func, arg) { return func.call(this, arg) })", name = "<testThis>")
      )
      try {
        val ret = call.invoke(func, "arg", thisVal = mapOf("name" to "this"))
        assertEquals("ok", ret)
        assertEquals("arg", seenArgs[0][0])
        val thisVal = assertIs<JSObject>(seenThis[0])
        try {
          assertEquals("this", thisVal["name"])
        } finally {
          // callback 里 jsToJava 交出来的 JSObject 同样要归还
          thisVal.close()
        }
      } finally {
        call.close()
      }
    } finally {
      ctx.closeAndCheckLeaks()
    }
  }

  /** Java 异常必须转成 JS 异常，而不是被吞掉 */
  @Test
  fun javaExceptionBecomesJsError() {
    val ctx = context()
    try {
      val boom = object : JSInvokable {
        override fun invoke(vararg argv: Any?, thisVal: Any?): Any? =
          throw IllegalStateException("kaboom")
      }
      val setter = assertIs<JSFunction>(ctx.evaluate("(f) => { this.__f = f }", name = "<set>"))
      try {
        setter.invoke(boom)
        val err = assertFailsWith<JSError> { ctx.evaluate("__f()") }
        assertTrue(
          err.message?.contains("kaboom") == true,
          "expected java message to surface, got: ${err.message}"
        )
      } finally {
        setter.close()
      }
    } finally {
      ctx.close()
    }
  }

  /** flutter_qjs: '[Promise.reject, Promise.resolve, new Promise(()=>{})]' 全部转成 Deferred */
  @Test
  fun promisesBecomeDeferred() = runBlocking {
    val ctx = context()
    try {
      val promises = ctx.evaluate(
        "[Promise.reject('reject'), Promise.resolve('resolve'), new Promise(() => {})]",
        name = "<promises>"
      ) as Array<*>
      assertEquals(3, promises.size)
      assertTrue(promises.all { it is Deferred<*> }, "each promise should map to a Deferred")

      // 运行时类型是 Object[]，只能逐元素取用，整体 cast 成 Array<Deferred> 会失败
      val rejected = assertIs<Deferred<Any?>>(promises[0])
      val resolved = assertIs<Deferred<Any?>>(promises[1])
      val pending = assertIs<Deferred<Any?>>(promises[2])
      assertEquals("resolve", resolved.await())
      // reject 的值不是 Throwable，桥接层会包成 JSError
      val rejection = assertFailsWith<Throwable> { rejected.await() }
      assertTrue(
        rejection is JSError || rejection.message?.contains("reject") == true,
        "rejection should surface, got: $rejection"
      )
      // 永不 settle 的 promise 不应完成
      assertEquals(null, withTimeoutOrNull(200) { pending.await() })
    } finally {
      ctx.close()
    }
  }

  /** Kotlin 的 Deferred 传进 JS 后应当是 Promise，可被 await */
  @Test
  fun deferredBecomesJsPromise() = runBlocking {
    val ctx = context()
    try {
      val d = CompletableDeferred<Any?>()
      val consume = assertIs<JSFunction>(ctx.evaluate("async (p) => await p", name = "<await>"))
      try {
        val job = CoroutineScope(Dispatchers.Default).launch {
          delay(50)
          d.complete("done")
        }
        assertEquals("done", callAsync(consume, d))
        job.join()
      } finally {
        consume.close()
      }
    } finally {
      ctx.close()
    }
  }

  /** 数组元素转换：每个元素都应原样落位（回归 jsToJava 数组分支的双转换 bug） */
  @Test
  fun arrayElementsConvertOnce() {
    val ctx = context()
    try {
      // jsToJava 的数组分支用 NewObjectArray 构造，所以回来的是 Object[]（Array<*>）
      val arr = ctx.evaluate("[1, 'a', true, null, [2, 3]]", name = "<arr>") as Array<*>
      assertEquals(5, arr.size)
      assertEquals(1L, arr[0])
      assertEquals("a", arr[1])
      assertEquals(true, arr[2])
      assertEquals(null, arr[3])
      assertEquals(listOf(2L, 3L), (arr[4] as Array<*>).toList())
    } finally {
      ctx.close()
    }
  }

  /** ArrayBuffer <-> ByteArray */
  @Test
  fun arrayBufferRoundTrip() {
    val ctx = context()
    try {
      val bytes = byteArrayOf(1, 2, 3, 4)
      val wrap = assertIs<JSFunction>(ctx.evaluate("async (b) => b", name = "<testWrap>"))
      try {
        assertContentEquals(bytes, callAsync(wrap, bytes) as ByteArray)
      } finally {
        wrap.close()
      }
    } finally {
      ctx.close()
    }
  }

  /** 对象反复往返不应崩溃，也用来覆盖 ArrayBuffer 探针后的异常清理 */
  @Test
  fun objectWithVariousTagsRoundTrips() {
    val ctx = context()
    try {
      val wrap = assertIs<JSFunction>(ctx.evaluate("async (a) => a", name = "<testWrap>"))
      try {
        val obj = HashMap<String, Any?>()
        obj["n"] = 42
        obj["d"] = 1.5
        obj["s"] = "text"
        obj["b"] = true
        obj["bytes"] = byteArrayOf(9, 8, 7)
        obj["list"] = listOf(1, 2, 3)
        val wrapped = assertIs<JSObject>(callAsync(wrap, obj))
        try {
          assertEquals(42L, wrapped["n"])
          assertEquals(1.5, wrapped["d"])
          assertEquals("text", wrapped["s"])
          assertEquals(true, wrapped["b"])
          assertContentEquals(byteArrayOf(9, 8, 7), wrapped["bytes"] as ByteArray)
          assertEquals(listOf(1L, 2L, 3L), (wrapped["list"] as Array<*>).toList())
        } finally {
          // jsToJava 出来的 JSObject 也持有一票，必须显式归还
          wrapped.close()
        }
      } finally {
        wrap.close()
      }
    } finally {
      ctx.closeAndCheckLeaks()
    }
  }

  /** 模块加载：moduleHandler 返回的源码应能被 import */
  @Test
  fun moduleLoading() {
    val ctx = context { name -> if (name == "test") "export default 'test module';" else null }
    try {
      ctx.evaluate(
        """
        import handlerData from 'test';
        export default { data: handlerData };
        """.trimIndent(),
        name = "evalModule",
        flag = JSEvalFlag.MODULE
      )
      val eventual = ctx.evaluate("import('evalModule')", name = "<import>")
      val mod = assertIs<Deferred<Any?>>(eventual)
      val awaited = assertIs<JSObject>(runBlocking { mod.await() })
      try {
        val default = assertIs<JSObject>(awaited["default"])
        try {
          assertEquals("test module", default["data"])
        } finally {
          default.close()
        }
      } finally {
        awaited.close()
      }
    } finally {
      ctx.closeAndCheckLeaks()
    }
  }

  /** moduleHandler 返回 null 时 import 必须抛错，而不是挂起或读到过期异常 */
  @Test
  fun missingModuleThrows() {
    val ctx = context { null }
    try {
      val err = assertFailsWith<JSError> {
        runBlocking {
          val p = assertIs<Deferred<Any?>>(ctx.evaluate("import('nope')", name = "<import>"))
          p.await()
        }
      }
      assertTrue(
        err.message?.contains("nope") == true || err.message?.contains("could not load module") == true,
        "expected module load failure, got: ${err.message}"
      )
    } finally {
      ctx.close()
    }
  }

  /** JSObject 应像 Map 一样工作 */
  @Test
  fun jsObjectBehavesLikeMap() {
    val ctx = context()
    try {
      val wrap = assertIs<JSFunction>(ctx.evaluate("async (o) => o", name = "<w>"))
      try {
        val obj = assertIs<JSObject>(ctx.evaluate("({a: 1, b: 'two'})"))
        try {
          val wrapped = assertIs<JSObject>(callAsync(wrap, obj))
          try {
            assertEquals(setOf("a", "b"), wrapped.keys)
            assertEquals(1L, wrapped["a"])
            assertEquals("two", wrapped["b"])
          } finally {
            wrapped.close()
          }
        } finally {
          obj.close()
        }
      } finally {
        wrap.close()
      }
    } finally {
      ctx.closeAndCheckLeaks()
    }
  }

  /**
   * 未显式释放的值：**会被报告为泄漏，但不会让进程崩掉**。
   *
   * 这一条替换了原来「未释放 + close() 不崩溃」的断言 —— 那个前提在 QuickJS 侧根本
   * 不成立。`JS_FreeRuntime` 结尾有 `assert(list_empty(&rt->gc_obj_list))`，只要还有
   * 活引用就会 abort，整个进程死掉、测试连报告都发不出来。
   *
   * [QuickJS.Context.closeAndCheckLeaks] 的正确行为是两件事一起做：
   * 1. 把仍在册的对象**报告**出来（这就是「泄漏」的可断言形式）；
   * 2. 在销毁 runtime 之前把它们逐个**归还**，从而避开 C 层断言。
   *
   * 对照 flutter_qjs 的 `test('reference leak')`：那边只断言「抛异常」，
   * 这边进一步确认「抛异常 + 进程存活」，少了任何一半都不算通过。
   */
  @Test
  fun unclosedValuesAreSweptByCloseAndCheckLeaks() {
    val ctx = context()
    repeat(64) {
      ctx.evaluate("({a: 1, b: [1,2,3]})", name = "<leak>")
    }
    // 64 个对象都没归还 → closeAndCheckLeaks 必须报出来
    val err = assertFailsWith<JSError> { ctx.closeAndCheckLeaks() }
    assertTrue(
      err.message?.startsWith("reference leak:") == true,
      "expected a reference leak report, got: ${err.message}"
    )
    // 再次调用应当幂等（已关闭）
    ctx.closeAndCheckLeaks()
  }

  /** close 之后的 Context 不应再被使用（当前实现会抛错，属于可接受行为） */
  @Test
  fun closedContextIsInert() {
    val ctx = context()
    assertEquals(2L, ctx.evaluate("1 + 1"))
    ctx.close()
    assertFailsWith<Throwable> { ctx.evaluate("1 + 1") }
  }

  /** flutter_qjs: 'infinite loop' —— timeout 必须把死循环变成可捕获的 JS 错误 */
  @Test
  fun timeoutInterruptsInfiniteLoop() {
    val ctx = QuickJS.Context(timeout = 1000)
    try {
      assertEquals(1L, ctx.evaluate("1"))
      val err = assertFailsWith<JSError> { ctx.evaluate("while(true) {}") }
      assertTrue(
        err.message?.startsWith("InternalError: interrupted") == true,
        "expected an interrupt error, got: ${err.message}"
      )
    } finally {
      ctx.close()
    }
  }

  /** flutter_qjs: 'memory leak' —— memoryLimit 触发 out of memory */
  @Test
  fun memoryLimitIsEnforced() {
    val ctx = QuickJS.Context(memoryLimit = 1_000_000)
    try {
      val err = assertFailsWith<JSError> { ctx.evaluate("new Array(1000000).fill(0)") }
      assertTrue(
        err.message?.startsWith("InternalError: out of memory") == true,
        "expected an out-of-memory error, got: ${err.message}"
      )
    } finally {
      ctx.close()
    }
  }
}
