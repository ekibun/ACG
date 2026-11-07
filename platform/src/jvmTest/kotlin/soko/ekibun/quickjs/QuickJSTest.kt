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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** quickjs 桥语义：求值、类型往返、异常回流、Promise、模块、泄漏报告、finalizer 只认 `AutoCloseable`。 */
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

  /** 求值的返回值，以及三种失败面：JS 抛错、栈溢出都要变成可捕获的 [JSError]。 */
  @Test(timeout = 60000)
  fun evaluateRunsScriptsAndReportsErrors() {
    val ctx = context()
    try {
      assertEquals(1L, eval(ctx, "1"))
      assertEquals("str", eval(ctx, "'str'"))
      assertEquals(true, eval(ctx, "true"))
      assertEquals(0.5, eval(ctx, "0.5"))
      assertEquals(null, eval(ctx, "null"))

      val thrown = assertFailsWith<JSError> { eval(ctx, "throw new Error('boom')") }
      assertEquals(
        true,
        thrown.message?.contains("boom"),
        "message should carry the JS text, got: ${thrown.message}",
      )

      val overflow = assertFailsWith<JSError> { eval(ctx, "a=()=>a();a();") }
      assertEquals(
        true,
        overflow.message?.contains("stack overflow", ignoreCase = true),
        "expected a stack overflow error, got: ${overflow.message}",
      )
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 原始类型、窄整数（`Char` / `Byte` / `Short`）、数组、`ArrayBuffer`。 */
  @Test(timeout = 60000)
  fun primitivesArraysAndBuffersRoundTrip() {
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
          // 整数回来是 Long，小数回来是 Double
          if (expected is Int) assertEquals(expected.toLong(), wrapped[i]) else assertEquals(expected, wrapped[i])
        }
        val n = primitives.size
        assertEquals('z'.code.toLong(), wrapped[n], "Char converts to Int by code point")
        assertEquals(7L, wrapped[n + 1], "Byte converts to Int")
        assertEquals(9L, wrapped[n + 2], "Short converts to Int")
      }

      // 数组分支用 ArrayList 承接，回来是 List；元素逐个原样落位
      val arr = eval(ctx, "[1, 'a', true, null, [2, 3]]", name = "<arr>") as List<*>
      assertEquals(5, arr.size)
      assertEquals(1L, arr[0])
      assertEquals("a", arr[1])
      assertEquals(true, arr[2])
      assertEquals(null, arr[3])
      assertEquals(listOf(2L, 3L), arr[4])

      // ArrayBuffer ↔ ByteArray
      val bytes = byteArrayOf(1, 2, 3, 4)
      assertIs<JSFunction>(eval(ctx, "async (b) => b", name = "<bufferWrap>")).use { wrap ->
        assertContentEquals(bytes, callAsync(wrap, bytes) as ByteArray)
      }
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 普通对象整图展开成 `Map`：含循环引用与 JS 侧对象往回转。 */
  @Test(timeout = 60000)
  fun objectsExpandToPlainData() {
    val ctx = context()
    try {
      assertIs<JSFunction>(eval(ctx, "async (a) => a", name = "<testWrap>")).use { wrap ->
        val obj = HashMap<String, Any?>()
        obj["n"] = 42
        obj["d"] = 1.5
        obj["s"] = "text"
        obj["b"] = true
        obj["bytes"] = byteArrayOf(9, 8, 7)
        obj["list"] = listOf(1, 2, 3)
        // 只有函数仍是包装，普通对象整图展开
        val wrapped = assertIs<Map<*, *>>(callAsync(wrap, obj))
        assertEquals(42L, wrapped["n"])
        assertEquals(1.5, wrapped["d"])
        assertEquals("text", wrapped["s"])
        assertEquals(true, wrapped["b"])
        assertContentEquals(byteArrayOf(9, 8, 7), wrapped["bytes"] as ByteArray)
        assertEquals(listOf(1L, 2L, 3L), wrapped["list"])

        // 循环引用：a['a'] = a
        val self = HashMap<String, Any?>()
        self["a"] = self
        val cyclic = assertIs<Map<*, *>>(callAsync(wrap, self))
        assertTrue(
          assertIs<Map<*, *>>(cyclic["a"]) === cyclic,
          "recursive reference must map back to the same object",
        )
      }

      // JS 侧的对象往回转也一样
      assertIs<JSFunction>(eval(ctx, "async (o) => o", name = "<roundTrip>")).use { wrap ->
        val fromJs = assertIs<Map<*, *>>(eval(ctx, "({a: 1, b: 'two'})"))
        val back = assertIs<Map<*, *>>(callAsync(wrap, fromJs))
        assertEquals(setOf("a", "b"), back.keys)
        assertEquals(1L, back["a"])
        assertEquals("two", back["b"])
      }
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** 引用计数：多持有一次就多归还一次；归零后重复 `free` 必须安全。 */
  @Test(timeout = 60000)
  fun refCountingFreesOnlyAtZero() {
    context().let { ctx ->
      try {
        val fn = assertIs<JSFunction>(eval(ctx, "()=>1", name = "<eval>"))
        assertEquals(1L, fn.invoke())
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
  }

  /** 故意不归还：close 时逐一报告，再 close 一次不重复打印。 */
  @Test(timeout = 60000)
  fun unreturnedReferencesAreReportedOnClose() {
    val leaky = context()
    repeat(64) {
      eval(leaky, "(() => 1)", name = "<leak>")
    }
    val out = leaky.closeCapturingOut()
    assertTrue("reference leak:" in out, "expected a reference leak report, got: $out")
    assertEquals(
      64,
      out.split("\n").count { it.contains("JSFunction") },
      "every registered function should be listed in the report",
    )
    assertEquals("", leaky.closeCapturingOut(), "a second close must not report again")
  }

  /** JS 调 Java：`thisVal` 与实参原样送达；Java 抛出的异常变成 JS 异常，不被吞掉。 */
  @Test(timeout = 60000)
  fun invokableCarriesCallAndSurfacesJavaErrors() {
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
      assertIs<JSFunction>(
        eval(ctx, "(function (func, arg) { return func.call(this, arg) })", name = "<testThis>"),
      ).use { call ->
        assertEquals("ok", call.invoke(func, "arg", thisVal = mapOf("name" to "this")))
        assertEquals("arg", seenArgs[0][0])
        // 回调里交出来的普通对象同样是纯数据
        assertEquals("this", assertIs<Map<*, *>>(seenThis[0])["name"])
      }

      val boom =
        object : JSInvokable {
          override fun invoke(
            vararg argv: Any?,
            thisVal: Any?,
          ): Any? = throw IllegalStateException("kaboom")
        }
      assertIs<JSFunction>(eval(ctx, "(f) => { this.__f = f }", name = "<set>")).use { setter ->
        setter.invoke(boom)
        val err = assertFailsWith<JSError> { eval(ctx, "__f()") }
        assertTrue(
          err.message?.contains("kaboom") == true,
          "expected java message to surface, got: ${err.message}",
        )
      }
    } finally {
      runBlocking { ctx.closeAndAssertNoLeak() }
    }
  }

  /** Promise ↔ Deferred 双向：三种 settle 状态都要转，Kotlin 的 Deferred 进 JS 也要能 await。 */
  @Test(timeout = 60000)
  fun promisesAndDeferredsMapBothWays() =
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

        // 反向：Kotlin 的 Deferred 进 JS 后是 Promise
        val deferred = CompletableDeferred<Any?>()
        assertIs<JSFunction>(eval(ctx, "async (p) => await p", name = "<await>")).use { consume ->
          val job =
            CoroutineScope(Dispatchers.Default).launch {
              delay(50)
              deferred.complete("done")
            }
          assertEquals("done", callAsync(consume, deferred))
          job.join()
        }
      } finally {
        runBlocking { ctx.closeAndAssertNoLeak() }
      }
    }

  /** 模块加载：handler 给的源码能 import；handler 返回 null 时必须抛错，而不是挂起或读到过期异常。 */
  @Test(timeout = 60000)
  fun modulesLoadAndMissingOnesThrow() {
    context { name -> if (name == "test") "export default 'test module';" else null }.let { ctx ->
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
        val eventual = assertIs<Deferred<Any?>>(eval(ctx, "import('evalModule')", name = "<import>"))
        val awaited = assertIs<Map<*, *>>(runBlocking { eventual.await() })
        assertEquals("test module", assertIs<Map<*, *>>(awaited["default"])["data"])
      } finally {
        runBlocking { ctx.closeAndAssertNoLeak() }
      }
    }

    context { null }.let { ctx ->
      try {
        val err =
          assertFailsWith<JSError> {
            runBlocking {
              assertIs<Deferred<Any?>>(eval(ctx, "import('nope')", name = "<import>")).await()
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
  }

  /** 关闭之后再用要拒；`close()` 与 `closeDeferred()` 穿插调用也只销毁一次。 */
  @Test(timeout = 60000)
  fun closedContextIsInertAndCloseIsIdempotent() {
    context().let { ctx ->
      assertEquals(2L, eval(ctx, "1 + 1"))
      ctx.close()
      assertFailsWith<IllegalStateException> { eval(ctx, "1 + 1") }
    }

    val idempotent = context()
    runBlocking { idempotent.closeDeferred().join() }
    idempotent.close()
    runBlocking { idempotent.closeAndAssertNoLeak() }
  }

  /** 跑飞的脚本要能被停掉：`timeout` 打断死循环，`memoryLimit` 触发 out of memory。 */
  @Test(timeout = 60000)
  fun runawayScriptsAreInterruptedOrCapped() {
    QuickJS(timeout = 1000).let { ctx ->
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

    QuickJS(memoryLimit = 1_000_000).use { ctx ->
      val err = assertFailsWith<JSError> { eval(ctx, "new Array(1000000).fill(0)") }
      assertTrue(
        err.message?.startsWith("InternalError: out of memory") == true,
        "expected an out-of-memory error, got: ${err.message}",
      )
    }
  }

  /** finalizer 探针：被 JS 丢掉之后应当收到一次 `close()`。 */
  private class Counted : AutoCloseable {
    val closes = AtomicInteger(0)

    override fun close() {
      closes.incrementAndGet()
    }
  }

  /** 有 `close()` 方法但**没实现** [AutoCloseable]：接口判据要看穿它。 */
  private class Plain {
    val closes = AtomicInteger(0)

    fun close() {
      closes.incrementAndGet()
    }
  }

  /** JS 侧丢掉的 Java 对象，销毁时只对实现了 `AutoCloseable` 的调一次 `close()`；同名方法没实现接口的必须是 0。 */
  @Suppress("UNCHECKED_CAST")
  @Test(timeout = 60_000)
  fun runtimeDestroyClosesAutoCloseablesOnly() {
    val counted = Counted()
    val plain = Plain()
    val ctx = QuickJS()
    runBlocking {
      // 只当实参传一次：jsCallImpl 的 finally 归还这张票
      val fn = ctx.evaluate("(function(x) { return x; })", "<probe>") as JSFunction
      fn.use {
        it.invoke(counted)
        it.invoke(plain)
      }
      // 关 runtime 后 finalizer 逐个跑一遍
      ctx.close()
    }
    assertEquals(
      1,
      counted.closes.get(),
      "the finalizer should call close once for JS when the runtime is destroyed, got ${counted.closes.get()} call(s)",
    )
    assertEquals(
      0,
      plain.closes.get(),
      "a close() method without the AutoCloseable interface must be left alone, " +
        "got ${plain.closes.get()} call(s) -- the finalizer is duck-typing",
    )
  }
}
