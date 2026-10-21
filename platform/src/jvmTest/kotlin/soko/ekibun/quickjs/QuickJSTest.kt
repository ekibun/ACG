package soko.ekibun.quickjs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * quickjs 桥接层语义：求值与异常、各类型往返、`thisVal`、Java 异常回流、Promise ↔ Deferred、模块加载、泄漏报告。
 */
class QuickJSTest {
  private fun context(moduleHandler: ((String) -> String?)? = null) = QuickJS(moduleHandler)

  /** 调 JS 的 async 函数并 await 它返回的 Deferred。 */
  private fun callAsync(
    fn: JSFunction,
    vararg argv: Any?,
  ): Any? = runBlocking { assertIs<Deferred<Any?>>(fn.invoke(*argv)).await() }

  /** 求值入口的阻塞包装。 */
  private fun eval(
    ctx: QuickJS,
    cmd: String,
    name: String = "<eval>",
    flag: Int = JSEvalFlag.GLOBAL,
  ): Any? = runBlocking { ctx.evaluate(cmd, name, flag) }

  /** 捕获标准输出并等关闭落地，返回捕获到的输出。 */
  private fun QuickJS.closeCapturingOut(): String {
    val buf = ByteArrayOutputStream()
    val old = System.out
    System.setOut(PrintStream(buf, true, Charsets.UTF_8))
    try {
      runBlocking { closeDeferred().join() }
    } finally {
      System.setOut(old)
    }
    return buf.toString("UTF-8")
  }

  /** 关闭并断言输出里没有泄漏报告。 */
  private fun QuickJS.closeAndAssertNoLeak() {
    val out = closeCapturingOut()
    assertTrue("reference leak" !in out, "close must not report a leak:\n$out")
  }

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
      runBlocking { ctx.closeAndAssertNoLeak() }
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 栈溢出要变成可捕获的 [JSError]。 */
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 原始类型经 JS 往返后的类型与取值，含 `Char` / `Byte` / `Short`。 */
  @Test
  fun javaToJsRoundTripPrimitives() {
    val ctx = context()
    try {
      assertIs<JSFunction>(eval(ctx, "async (a) => a", name = "<testWrap>")).use { wrap ->
        assertEquals(null, callAsync(wrap, null))
        val primitives = listOf<Any?>(0, 1, 0.1, true, false, "str")
        // Char / Byte / Short 走「先转 Int 再按 JS_TAG_INT 回来」的分支
        val narrow = listOf<Any>('z', 7.toByte(), 9.toShort())
        val wrapped = callAsync(wrap, primitives + narrow) as List<*>
        assertEquals((primitives + narrow).size, wrapped.size)
        for (i in primitives.indices) {
          val expected = primitives[i]
          val actual = wrapped[i]
          // 整数回来是 Long，小数回来是 Double
          if (expected is Int) assertEquals(expected.toLong(), actual) else assertEquals(expected, actual)
        }
        val n = primitives.size
        assertEquals('z'.code.toLong(), wrapped[n], "Char converts to Int by code point")
        assertEquals(7L, wrapped[n + 1], "Byte converts to Int")
        assertEquals(9L, wrapped[n + 2], "Short converts to Int")
      }
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 循环引用转换后身份保持：`a['a'] === a`。 */
  @Test
  fun recursiveObjectKeepsIdentity() {
    val ctx = context()
    try {
      val wrap = assertIs<JSFunction>(eval(ctx, "async (a) => a", name = "<testWrap>"))
      try {
        // a['a'] = a，依赖 cache 才不会在转换时无限递归
        val a = HashMap<String, Any?>()
        a["a"] = a
        // 整图展开后普通对象就是纯数据 Map，没有引用要还
        val wrapped = assertIs<Map<*, *>>(callAsync(wrap, a))
        val self = assertIs<Map<*, *>>(wrapped["a"])
        assertTrue(self === wrapped, "recursive reference must map back to the same object")
      } finally {
        wrap.close()
      }
    } finally {
      // 显式归还后 close 不该报泄漏
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 全部显式归还后 close 不报泄漏。 */
  @Test
  fun noLeakWhenEverythingIsReleased() {
    val ctx = context()
    val fn = assertIs<JSFunction>(eval(ctx, "()=>1", name = "<eval>"))
    assertEquals(1L, fn.invoke())
    fn.close()
    runBlocking { ctx.closeAndAssertNoLeak() }
  }

  /** dup / free 的计数语义：多持有一次就多归还一次。 */
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
      // 归零后重复 free 必须安全
      fn.free()
      fn.close()
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** JS 侧调用 Java 函数时，参数与 `thisVal` 都要原样送达。 */
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
        // 回调里交出来的普通对象同样是纯数据
        val thisVal = assertIs<Map<*, *>>(seenThis[0])
        assertEquals("this", thisVal["name"])
      } finally {
        call.close()
      }
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** Java 异常必须转成 JS 异常，不能被吞掉。 */
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 三种 Promise 全转成 Deferred：已 resolve、已 reject、永不 settle。 */
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
          ) as List<*>
        assertEquals(3, promises.size)
        assertTrue(promises.all { it is Deferred<*> }, "each promise should map to a Deferred")

        // 运行时类型是 Object[]，只能逐元素取用
        val rejected = assertIs<Deferred<Any?>>(promises[0])
        val resolved = assertIs<Deferred<Any?>>(promises[1])
        val pending = assertIs<Deferred<Any?>>(promises[2])
        assertEquals("resolve", resolved.await())
        // reject 的值不是 Throwable，桥接层包成 JSError
        val rejection = assertFailsWith<Throwable> { rejected.await() }
        assertTrue(
          rejection is JSError || rejection.message?.contains("reject") == true,
          "rejection should surface, got: $rejection",
        )
        // 永不 settle 的 promise 不完成
        assertEquals(null, withTimeoutOrNull(200) { pending.await() })
      } finally {
        runBlocking { ctx.closeAndAssertNoLeak() }
      }
    }

  /** Kotlin 的 Deferred 传进 JS 后应当是 Promise，可被 await。 */
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
        runBlocking { ctx.closeAndAssertNoLeak() }
      }
    }

  /** 数组元素逐个原样落位。 */
  @Test
  fun arrayElementsConvertOnce() {
    val ctx = context()
    try {
      // 数组分支用 ArrayList 承接，回来是 List
      val arr = eval(ctx, "[1, 'a', true, null, [2, 3]]", name = "<arr>") as List<*>
      assertEquals(5, arr.size)
      assertEquals(1L, arr[0])
      assertEquals("a", arr[1])
      assertEquals(true, arr[2])
      assertEquals(null, arr[3])
      assertEquals(listOf(2L, 3L), arr[4])
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** ArrayBuffer ↔ `ByteArray`。 */
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 多 tag 对象往返后各字段的类型与取值不变。 */
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
        // 普通对象整图展开成 Map，只有函数仍是包装
        val wrapped = assertIs<Map<*, *>>(callAsync(wrap, obj))
        assertEquals(42L, wrapped["n"])
        assertEquals(1.5, wrapped["d"])
        assertEquals("text", wrapped["s"])
        assertEquals(true, wrapped["b"])
        assertContentEquals(byteArrayOf(9, 8, 7), wrapped["bytes"] as ByteArray)
        assertEquals(listOf(1L, 2L, 3L), wrapped["list"])
      } finally {
        wrap.close()
      }
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 模块加载：[QuickJS] 构造时给的 handler 返回的源码应能被 import。 */
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** handler 返回 null 时 import 必须抛错，而不是挂起或读到过期异常。 */
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 整图展开出来的普通对象就是 `Map`，两端各转一圈后读法不变。 */
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 未显式释放的值在 close 时被逐一报告，且 close 不崩。 */
  @Test
  fun unclosedValuesAreSweptOnClose() {
    val ctx = context()
    // 故意不 close()：函数是整图展开之后唯一还持票的东西
    repeat(64) {
      eval(ctx, "(() => 1)", name = "<leak>")
    }
    // 64 个函数都没归还 → close 时逐一报告
    val out = ctx.closeCapturingOut()
    assertTrue(
      "reference leak:" in out,
      "expected a reference leak report, got: $out",
    )
    assertEquals(
      64,
      out.split("\n").count { it.contains("JSFunction") },
      "every registered function should be listed in the report",
    )
    // 已关闭时再调一次不重复打印
    assertEquals("", ctx.closeCapturingOut())
  }

  /** close 之后再用：抛 [IllegalStateException]。 */
  @Test
  fun closedContextIsInert() {
    val ctx = context()
    assertEquals(2L, eval(ctx, "1 + 1"))
    ctx.close()
    assertFailsWith<IllegalStateException> { eval(ctx, "1 + 1") }
  }

  /** 关闭幂等：`close()` 与 `closeDeferred()` 穿插调用也只销毁一次。 */
  @Test
  fun closeIsIdempotentAcrossEntryPoints() {
    val ctx = context()
    runBlocking { ctx.closeDeferred().join() }
    ctx.close()
    runBlocking { ctx.closeAndAssertNoLeak() }
  }

  /** `timeout` 必须把死循环变成可捕获的 JS 错误。 */
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
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** `memoryLimit` 触发 out of memory。 */
  @Test
  fun memoryLimitIsEnforced() {
    QuickJS(memoryLimit = 1_000_000).use { ctx ->
      val err = assertFailsWith<JSError> { eval(ctx, "new Array(1000000).fill(0)") }
      assertTrue(
        err.message?.startsWith("InternalError: out of memory") == true,
        "expected an out-of-memory error, got: ${err.message}",
      )
    }
  }
}
