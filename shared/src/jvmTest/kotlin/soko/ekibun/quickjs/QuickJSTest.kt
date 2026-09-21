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
  private fun context(moduleHandler: ((String) -> String?)? = null) = QuickJS(moduleHandler)

  /**
   * `async (a) => a` 在 JS 侧返回 Promise，桥接层会转成 Deferred，
   * 所以调用此类函数要 await 才能拿到真正的值（与 flutter_qjs 的
   * `await testWrap.invoke([...])` 一致）。
   */
  private fun callAsync(
    fn: JSFunction,
    vararg argv: Any?,
  ): Any? = runBlocking { assertIs<Deferred<Any?>>(fn.invoke(*argv)).await() }

  /**
   * 测试里的求值入口。
   *
   * [soko.ekibun.quickjs.QuickJS.evaluate] 是**挂起**的 —— 求值要搬到 runtime 的归属
   * 线程上。而这些用例是普通函数、没有协程上下文，于是统一在这里阻塞等一次。
   */
  private fun eval(
    ctx: QuickJS,
    cmd: String,
    name: String = "<eval>",
    flag: Int = JSEvalFlag.GLOBAL,
  ): Any? = runBlocking { ctx.evaluate(cmd, name, flag) }

  @Test
  fun evaluatePrimitives() {
    val ctx = context()
    try {
      assertEquals(1L, eval(ctx, "1"))
      assertEquals("str", eval(ctx, "'str'"))
      assertEquals(true, eval(ctx, "true"))
      assertEquals(0.5, eval(ctx, "0.5"))
      assertEquals(null, eval(ctx, "null"))
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  @Test
  fun evaluateThrowsJSError() {
    val ctx = context()
    try {
      val err = assertFailsWith<JSError> { eval(ctx, "throw new Error('boom')") }
      assertEquals(
        err.message?.contains("boom"),
        true,
        "message should carry the JS text, got: ${err.message}",
      )
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  /** flutter_qjs: 'stack overflow' -> a=()=>a();a() */
  @Test
  fun stackOverflowIsReported() {
    val ctx = context()
    try {
      val err = assertFailsWith<JSError> { eval(ctx, "a=()=>a();a();") }
      assertEquals(
        err.message?.contains("stack overflow", ignoreCase = true),
        true,
        "expected a stack overflow error, got: ${err.message}",
      )
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  /** flutter_qjs: 'data conversion' 的 wrap null / primities 部分 */
  @Test
  fun javaToJsRoundTripPrimitives() {
    val ctx = context()
    try {
      assertIs<JSFunction>(eval(ctx, "async (a) => a", name = "<testWrap>")).use { wrap ->
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
      runBlocking { ctx.closeAndCollect().await() }
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
      val wrap = assertIs<JSFunction>(eval(ctx, "async (a) => a", name = "<testWrap>"))
      try {
        // a['a'] = a —— 依赖 javaToJsImpl 的 cache 才不会在转换时无限递归
        val a = HashMap<String, Any?>()
        a["a"] = a
        // 整图展开后普通对象就是纯数据 Map，没有引用要还。`a['a'] === a` 能成立
        // 靠的是「填之前先把自己登记进 cache」—— 递归回自身时命中的正是那个 Map。
        val wrapped = assertIs<Map<*, *>>(callAsync(wrap, a))
        val self = assertIs<Map<*, *>>(wrapped["a"])
        assertTrue(self === wrapped, "recursive reference must map back to the same object")
      } finally {
        wrap.close()
      }
    } finally {
      // 显式归还之后不该有残留；有残留会在这里抛出来（而不是让进程 abort）
      runBlocking { ctx.closeAndCheckLeaks() }
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
    eval(ctx, "()=>{}", name = "<eval>")
    val err = assertFailsWith<JSError> { runBlocking { ctx.closeAndCheckLeaks() } }
    assertTrue(
      err.message?.startsWith("reference leak:") == true,
      "expected a reference leak report, got: ${err.message}",
    )
  }

  /** 正常路径不该报泄漏：所有包装都显式归还后，closeAndCheckLeaks 必须安静通过 */
  @Test
  fun noLeakWhenEverythingIsReleased() {
    val ctx = context()
    val fn = assertIs<JSFunction>(eval(ctx, "()=>1", name = "<eval>"))
    assertEquals(1L, fn.invoke())
    fn.close()
    // 不抛异常即通过
    runBlocking { ctx.closeAndCheckLeaks() }
  }

  /** dup/free 的计数语义：多持有一次就要多归还一次 */
  @Test
  fun dupAndFreeBalance() {
    val ctx = context()
    try {
      val fn = assertIs<JSFunction>(eval(ctx, "()=>1", name = "<eval>"))
      assertEquals(1, fn.refCount)
      fn.dup()
      assertEquals(2, fn.refCount)
      fn.free()
      assertEquals(1, fn.refCount)
      assertTrue(!fn.isClosed, "still held once, must not be released yet")
      fn.free()
      assertTrue(fn.isClosed, "refcount hit zero, must be released")
      // 归零后重复 free 必须安全（重构前这里会双重释放 native 句柄）
      fn.free()
      fn.close()
    } finally {
      runBlocking { ctx.closeAndCheckLeaks() }
    }
  }

  /** JS 侧调用 Java 函数时，参数与 thisVal 都要原样送达 */
  @Test
  fun jsInvokableReceivesThisValAndArgs() {
    val ctx = context()
    try {
      val seenThis = ArrayList<Any?>()
      val seenArgs = ArrayList<List<Any?>>()
      val func =
        object : JSInvokable {
          override fun invoke(
            vararg argv: Any?,
            thisVal: Any?,
          ): Any? {
            seenThis += thisVal
            seenArgs += argv.toList()
            return "ok"
          }
        }
      val call =
        assertIs<JSFunction>(
          eval(ctx, "(function (func, arg) { return func.call(this, arg) })", name = "<testThis>"),
        )
      try {
        val ret = call.invoke(func, "arg", thisVal = mapOf("name" to "this"))
        assertEquals("ok", ret)
        assertEquals("arg", seenArgs[0][0])
        // 回调里 jsToJava 交出来的普通对象同样是整图展开的纯数据
        val thisVal = assertIs<Map<*, *>>(seenThis[0])
        assertEquals("this", thisVal["name"])
      } finally {
        call.close()
      }
    } finally {
      runBlocking { ctx.closeAndCheckLeaks() }
    }
  }

  /** Java 异常必须转成 JS 异常，而不是被吞掉 */
  @Test
  fun javaExceptionBecomesJsError() {
    val ctx = context()
    try {
      val boom =
        object : JSInvokable {
          override fun invoke(
            vararg argv: Any?,
            thisVal: Any?,
          ): Any? = throw IllegalStateException("kaboom")
        }
      val setter = assertIs<JSFunction>(eval(ctx, "(f) => { this.__f = f }", name = "<set>"))
      try {
        setter.invoke(boom)
        val err = assertFailsWith<JSError> { eval(ctx, "__f()") }
        assertTrue(
          err.message?.contains("kaboom") == true,
          "expected java message to surface, got: ${err.message}",
        )
      } finally {
        setter.close()
      }
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  /** flutter_qjs: '[Promise.reject, Promise.resolve, new Promise(()=>{})]' 全部转成 Deferred */
  @Test
  fun promisesBecomeDeferred() =
    runBlocking {
      val ctx = context()
      try {
        val promises =
          eval(
            ctx,
            "[Promise.reject('reject'), Promise.resolve('resolve'), new Promise(() => {})]",
            name = "<promises>",
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
          "rejection should surface, got: $rejection",
        )
        // 永不 settle 的 promise 不应完成
        assertEquals(null, withTimeoutOrNull(200) { pending.await() })
      } finally {
        runBlocking { ctx.closeAndCollect().await() }
      }
    }

  /** Kotlin 的 Deferred 传进 JS 后应当是 Promise，可被 await */
  @Test
  fun deferredBecomesJsPromise() =
    runBlocking {
      val ctx = context()
      try {
        val d = CompletableDeferred<Any?>()
        val consume = assertIs<JSFunction>(eval(ctx, "async (p) => await p", name = "<await>"))
        try {
          val job =
            CoroutineScope(Dispatchers.Default).launch {
              delay(50)
              d.complete("done")
            }
          assertEquals("done", callAsync(consume, d))
          job.join()
        } finally {
          consume.close()
        }
      } finally {
        runBlocking { ctx.closeAndCollect().await() }
      }
    }

  /** 数组元素转换：每个元素都应原样落位（回归 jsToJava 数组分支的双转换 bug） */
  @Test
  fun arrayElementsConvertOnce() {
    val ctx = context()
    try {
      // jsToJava 的数组分支用 NewObjectArray 构造，所以回来的是 Object[]（Array<*>）
      val arr = eval(ctx, "[1, 'a', true, null, [2, 3]]", name = "<arr>") as Array<*>
      assertEquals(5, arr.size)
      assertEquals(1L, arr[0])
      assertEquals("a", arr[1])
      assertEquals(true, arr[2])
      assertEquals(null, arr[3])
      assertEquals(listOf(2L, 3L), (arr[4] as Array<*>).toList())
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  /** ArrayBuffer <-> ByteArray */
  @Test
  fun arrayBufferRoundTrip() {
    val ctx = context()
    try {
      val bytes = byteArrayOf(1, 2, 3, 4)
      val wrap = assertIs<JSFunction>(eval(ctx, "async (b) => b", name = "<testWrap>"))
      try {
        assertContentEquals(bytes, callAsync(wrap, bytes) as ByteArray)
      } finally {
        wrap.close()
      }
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  /** 对象反复往返不应崩溃，也用来覆盖 ArrayBuffer 探针后的异常清理 */
  @Test
  fun objectWithVariousTagsRoundTrips() {
    val ctx = context()
    try {
      val wrap = assertIs<JSFunction>(eval(ctx, "async (a) => a", name = "<testWrap>"))
      try {
        val obj = HashMap<String, Any?>()
        obj["n"] = 42
        obj["d"] = 1.5
        obj["s"] = "text"
        obj["b"] = true
        obj["bytes"] = byteArrayOf(9, 8, 7)
        obj["list"] = listOf(1, 2, 3)
        // 普通对象整图展开成 Map；只有函数还会是包装
        val wrapped = assertIs<Map<*, *>>(callAsync(wrap, obj))
        assertEquals(42L, wrapped["n"])
        assertEquals(1.5, wrapped["d"])
        assertEquals("text", wrapped["s"])
        assertEquals(true, wrapped["b"])
        assertContentEquals(byteArrayOf(9, 8, 7), wrapped["bytes"] as ByteArray)
        assertEquals(listOf(1L, 2L, 3L), (wrapped["list"] as Array<*>).toList())
      } finally {
        wrap.close()
      }
    } finally {
      runBlocking { ctx.closeAndCheckLeaks() }
    }
  }

  /** 模块加载：moduleHandler 返回的源码应能被 import */
  @Test
  fun moduleLoading() {
    val ctx = context { name -> if (name == "test") "export default 'test module';" else null }
    try {
      eval(
        ctx,
        """
        import handlerData from 'test';
        export default { data: handlerData };
        """.trimIndent(),
        name = "evalModule",
        flag = JSEvalFlag.MODULE,
      )
      val eventual = eval(ctx, "import('evalModule')", name = "<import>")
      val mod = assertIs<Deferred<Any?>>(eventual)
      val awaited = assertIs<Map<*, *>>(runBlocking { mod.await() })
      val default = assertIs<Map<*, *>>(awaited["default"])
      assertEquals("test module", default["data"])
    } finally {
      runBlocking { ctx.closeAndCheckLeaks() }
    }
  }

  /** moduleHandler 返回 null 时 import 必须抛错，而不是挂起或读到过期异常 */
  @Test
  fun missingModuleThrows() {
    val ctx = context { null }
    try {
      val err =
        assertFailsWith<JSError> {
          runBlocking {
            val p = assertIs<Deferred<Any?>>(eval(ctx, "import('nope')", name = "<import>"))
            p.await()
          }
        }
      assertTrue(
        err.message?.contains("nope") == true || err.message?.contains("could not load module") == true,
        "expected module load failure, got: ${err.message}",
      )
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  /** 整图展开出来的普通对象就是 `Map`，两端各转一圈后读法不变 */
  @Test
  fun expandedObjectBehavesLikeMap() {
    val ctx = context()
    try {
      val wrap = assertIs<JSFunction>(eval(ctx, "async (o) => o", name = "<w>"))
      try {
        val obj = assertIs<Map<*, *>>(eval(ctx, "({a: 1, b: 'two'})"))
        val wrapped = assertIs<Map<*, *>>(callAsync(wrap, obj))
        assertEquals(setOf("a", "b"), wrapped.keys)
        assertEquals(1L, wrapped["a"])
        assertEquals("two", wrapped["b"])
      } finally {
        wrap.close()
      }
    } finally {
      runBlocking { ctx.closeAndCheckLeaks() }
    }
  }

  /**
   * 未显式释放的值：**会被报告为泄漏，但不会让进程崩掉**。
   *
   * 这一条替换了原来「未释放 + close() 不崩溃」的断言 —— 那个前提在 QuickJS 侧根本
   * 不成立。`JS_FreeRuntime` 结尾有 `assert(list_empty(&rt->gc_obj_list))`，只要还有
   * 活引用就会 abort，整个进程死掉、测试连报告都发不出来。
   *
   * [QuickJS.closeAndCheckLeaks] 的正确行为是两件事一起做：
   * 1. 把仍在册的对象**报告**出来（这就是「泄漏」的可断言形式）；
   * 2. 在销毁 runtime 之前把它们逐个**归还**，从而避开 C 层断言。
   *
   * 对照 flutter_qjs 的 `test('reference leak')`：那边只断言「抛异常」，
   * 这边进一步确认「抛异常 + 进程存活」，少了任何一半都不算通过。
   */
  @Test
  fun unclosedValuesAreSweptByCloseAndCheckLeaks() {
    val ctx = context()
    // 故意不 close()：函数是整图展开之后**唯一**还持票的东西 —— 普通对象现在直接
    // 展开成纯数据的 Map，拿它们造泄漏已经造不出来了。
    repeat(64) {
      eval(ctx, "(() => 1)", name = "<leak>")
    }
    // 64 个函数都没归还 → closeAndCheckLeaks 必须报出来
    val err = assertFailsWith<JSError> { runBlocking { ctx.closeAndCheckLeaks() } }
    assertTrue(
      err.message?.startsWith("reference leak:") == true,
      "expected a reference leak report, got: ${err.message}",
    )
    // 再次调用应当幂等（已关闭）
    runBlocking { ctx.closeAndCheckLeaks() }
  }

  /** close 之后的 QuickJS 不应再被使用（当前实现会抛错，属于可接受行为） */
  @Test
  fun closedContextIsInert() {
    val ctx = context()
    assertEquals(2L, eval(ctx, "1 + 1"))
    runBlocking { ctx.closeAndCollect().await() }
    assertFailsWith<Throwable> { eval(ctx, "1 + 1") }
  }

  /**
   * 销毁只有一条路径，而且必须幂等。
   *
   * `JS_FreeRuntime` 不可重入 —— 同一个句柄调两次就是双重释放。出场守卫
   * （一次性取走句柄）与 `closed` 标志共同挡住第二次；这里从两条入口穿插关闭，
   * 覆盖 `close()` 与 `closeAndCheckLeaks()` 互为第二次的情况。
   */
  @Test
  fun closeIsIdempotentAcrossEntryPoints() {
    val ctx = context()
    runBlocking { ctx.closeAndCollect().await() }
    runBlocking { ctx.closeAndCheckLeaks() }
    runBlocking { ctx.closeAndCollect().await() }
  }

  /** flutter_qjs: 'infinite loop' —— timeout 必须把死循环变成可捕获的 JS 错误 */
  @Test
  fun timeoutInterruptsInfiniteLoop() {
    val ctx = QuickJS(timeout = 1000)
    try {
      assertEquals(1L, eval(ctx, "1"))
      val err = assertFailsWith<JSError> { eval(ctx, "while(true) {}") }
      assertTrue(
        err.message?.startsWith("InternalError: interrupted") == true,
        "expected an interrupt error, got: ${err.message}",
      )
    } finally {
      runBlocking { ctx.closeAndCollect().await() }
    }
  }

  /** flutter_qjs: 'memory leak' —— memoryLimit 触发 out of memory */
  @Test
  fun memoryLimitIsEnforced() {
    // QuickJS 现在实现了 AutoCloseable，可以直接 `use {}`
    QuickJS(memoryLimit = 1_000_000).use { ctx ->
      val err = assertFailsWith<JSError> { eval(ctx, "new Array(1000000).fill(0)") }
      assertTrue(
        err.message?.startsWith("InternalError: out of memory") == true,
        "expected an out-of-memory error, got: ${err.message}",
      )
    }
  }
}
