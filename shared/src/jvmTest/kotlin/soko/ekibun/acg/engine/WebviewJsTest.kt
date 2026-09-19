package soko.ekibun.acg.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.QuickJS
import java.io.File
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `init.js` 里那个 `webview(...)`（后台 WebView 的 JS wrapper）的回归测试。
 *
 * 用法与 [InitJsTest] 一样：把 init.js **原样**喂给真正的 QuickJS，用一个 Java 侧桩
 * 顶替 `_java` 里的 `webviewAsync`，验的是真实运行行为而不是字符串断言。
 *
 * 重点锁三件事：
 * 1. `__webview_kind__` 两种结果都要被正确拆包（命中拦截 → {url,headers}；
 *    脚本 → JSON.parse 后的值）；
 * 2. 「脚本没返回值」必须是 `undefined`，而不是 `null`/`"null"` 之类的旁枝
 *    （对应 http.js 的 `it = it && JSON.parse(it)`）；
 * 3. `onInterceptRequest` 回调真的能被 Kotlin 侧调用并拿回对象 —— 这段是
 *    `JsEngine.invokeInterceptor` 的另一半契约，不测就只在真机上才暴露。
 *
 * 桩对 JS 实参的处置与真实实现一致（见 `JsEngine.webviewAsync`）：收到的回调实参归
 * 被调用方所有，等它交回的那个 Deferred 收场时归还。两个方向都不能偏 —— 不还的话
 * 每个调过 `webview(...)` 的用例都会在 stderr 留一条 `QuickJS reference leak`，
 * 早还则让 `onInterceptRequest` 打在已经释放的包装上。
 */
class WebviewJsTest {
  /** 从源码目录读，保证验的就是正在编辑的那份文件。 */
  private fun initSource(): String {
    val candidates =
      listOf(
        File("src/commonMain/composeResources/files/js/init.js"),
        File("shared/src/commonMain/composeResources/files/js/init.js"),
      )
    return candidates.firstOrNull { it.exists() }?.readText()
      ?: error("init.js not found, cwd=${File(".").absolutePath}")
  }

  /**
   * `_java` 桩。`webviewAsync` 的应答由 [respond] 决定，参数与
   * [JsEngine.webviewAsync] 的签名一一对应。
   */
  private fun javaStub(respond: (url: String, script: String?, fn: JSFunction?) -> Any?) =
    object : JSInvokable {
      override fun invoke(
        vararg argv: Any?,
        thisVal: Any?,
      ): Any? {
        val method = argv.getOrNull(1) as? String ?: return null
        return when (method) {
          "encode" -> {
            val data = argv.getOrNull(2) as? String ?: ""
            val charset =
              (argv.getOrNull(3) as? String)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            data.toByteArray(charset)
          }

          "decode" -> {
            val data = argv.getOrNull(2) as? ByteArray ?: ByteArray(0)
            val charset =
              (argv.getOrNull(3) as? String)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            String(data, charset)
          }

          "console" -> null

          // argv[0]=obj(null) argv[1]=name argv[2]=url argv[3]=header
          // argv[4]=script argv[5]=onInterceptRequest
          "webviewAsync" -> {
            // header 已经是整图展开出来的纯数据 Map，没有引用要还；只剩回调要留着
            val callback = argv.getOrNull(5) as? JSFunction
            val ret =
              respond(argv.getOrNull(2) as? String ?: "", argv.getOrNull(4) as? String, callback)
            // 实参归被调用方所有：等它交回的那个 Deferred 收场（成功 / 失败 / 取消）
            // 再归还，与 `JsEngine.webviewAsync` 同一条规矩。
            val deferred = ret as? Deferred<*>
            if (deferred != null) {
              deferred.invokeOnCompletion { callback?.close() }
            } else {
              callback?.close()
            }
            ret
          }

          else -> null
        }
      }
    }

  /**
   * 按 [JsEngine] 的方式加载 init.js。
   *
   * **不传 moduleHandler**，与 [InitJsTest] 一致 —— init.js 里所有能力都是内联的，
   * 一旦有人再往里加动态 `import()`，这条用例会立刻变成进程 abort（见 init.js 的注释）。
   */
  private fun loadInit(
    ctx: QuickJS.Context,
    javaStub: JSInvokable,
  ) {
    val factory = assertIs<JSFunction>(ctx.evaluate(initSource(), name = "<init.js>"))
    try {
      runBlocking {
        // init.js 的箭头函数体没有 return，await 出来是 undefined —— 这里没有
        // 需要归还的包装（[JsEngine] 加载 init.js 的路径同理）。
        (factory.invoke(javaStub) as? Deferred<*>)?.await()
      }
    } finally {
      factory.close()
    }
  }

  /**
   * 求值一段 async 脚本并等它的 Promise。
   *
   * 加了 15s 上限是**护栏**：这个桥对「在 `_java` 调用内部反向去调 JS 函数」这种
   * 重入时序不保证有进展（见 [interceptCallbackCanBeInvokedFromKotlin] 的注释），
   * 超时至少把「卡死」变成一条失败，而不是让构建挂到天荒地老。
   */
  private fun evalAsync(
    ctx: QuickJS.Context,
    body: String,
  ): Any? =
    runBlocking {
      withTimeout(15_000) {
        val out = ctx.evaluate("(async () => { $body })()", name = "<webview>")
        assertIs<Deferred<Any?>>(out).await()
      }
    }

  @Test
  fun webviewIsExposedAsGlobal() {
    val ctx = QuickJS.Context()
    try {
      loadInit(ctx, javaStub { _, _, _ -> null })
      assertEquals("function", ctx.evaluate("typeof webview"))
    } finally {
      ctx.close()
    }
  }

  /** 命中拦截：结果就是 { url, headers }，脚本拿去自己 fetch。 */
  @Test
  fun interceptResultIsUnwrapped() {
    val ctx = QuickJS.Context()
    try {
      loadInit(
        ctx,
        javaStub { url, _, _ ->
          assertEquals("https://example.com/play", url, "url 必须原样传到 Kotlin 侧")
          CompletableDeferred<Any?>(
            mapOf(
              "__webview_kind__" to "intercept",
              "value" to
                mapOf(
                  "url" to "https://cdn.example.com/1.m3u8",
                  "headers" to mapOf("Range" to "bytes=0-"),
                ),
            ),
          )
        },
      )
      val out =
        evalAsync(
          ctx,
          """
          const ret = await webview("https://example.com/play", { "User-Agent": "ua" });
          return ret.url + "|" + ret.headers.Range;
          """.trimIndent(),
        )
      assertEquals("https://cdn.example.com/1.m3u8|bytes=0-", out)
    } finally {
      ctx.close()
    }
  }

  /** 脚本返回值是 JSON 字符串 → 解析成对象交回。 */
  @Test
  fun scriptResultIsParsed() {
    val ctx = QuickJS.Context()
    try {
      var seenScript: String? = null
      loadInit(
        ctx,
        javaStub { _, script, _ ->
          seenScript = script
          CompletableDeferred<Any?>(mapOf("__webview_kind__" to "script", "value" to """{"a":1}"""))
        },
      )
      val out = evalAsync(ctx, """return (await webview("https://a/", {}, "JSON.stringify({a:1})")).a;""")
      assertEquals(1L, out)
      assertEquals("JSON.stringify({a:1})", seenScript)
    } finally {
      ctx.close()
    }
  }

  /**
   * 脚本没返回值必须是 `undefined`。
   *
   * Android 的 `evaluateJavascript` 对「结果不是 JSON」回的是空串，桌面端也可能是
   * null；两者都不能被当成「拿到了一个叫 null 的值」。
   */
  @Test
  fun emptyScriptResultIsUndefined() {
    val ctx = QuickJS.Context()
    try {
      loadInit(
        ctx,
        javaStub { _, _, _ ->
          CompletableDeferred<Any?>(mapOf("__webview_kind__" to "script", "value" to ""))
        },
      )
      assertNull(
        evalAsync(ctx, """return (await webview("https://a/", {})) === undefined ? null : "not undefined";"""),
      )
    } finally {
      ctx.close()
    }
  }

  /** 不是 JSON 的返回值原样给出，别把裸文本吞掉。 */
  @Test
  fun nonJsonScriptResultIsReturnedAsIs() {
    val ctx = QuickJS.Context()
    try {
      loadInit(
        ctx,
        javaStub { _, _, _ ->
          CompletableDeferred<Any?>(mapOf("__webview_kind__" to "script", "value" to "plain text"))
        },
      )
      assertEquals("plain text", evalAsync(ctx, """return await webview("https://a/", {});"""))
    } finally {
      ctx.close()
    }
  }

  /** 失败（超时 / 没有后端）必须 reject，而不是回一个让脚本误判的空值。 */
  @Test
  fun failureRejects() {
    val ctx = QuickJS.Context()
    try {
      loadInit(
        ctx,
        javaStub { _, _, _ ->
          CompletableDeferred<Any?>().apply { completeExceptionally(JSError("后台 WebView 失败：超时")) }
        },
      )
      val out =
        evalAsync(
          ctx,
          """
          try { await webview("https://a/", {}); return "no throw"; }
          catch (e) { return "caught:" + e.message; }
          """.trimIndent(),
        )
      assertEquals("caught:后台 WebView 失败：超时", out)
    } finally {
      ctx.close()
    }
  }

  /**
   * `onInterceptRequest` 是**函数**时必须原样交给 Kotlin，并且能被 Kotlin 调用、拿回对象。
   *
   * 这一半（JS 函数被 Kotlin 调）与 `JsEngine.invokeInterceptor` 那半边合起来才是
   * 完整契约。不测的话，只有真跑起来才会发现问题。
   *
   * **时序刻意对齐真实实现**：拦截发生在 WebView 的回调线程上，且是在
   * `webviewAsync` 把 Deferred 交回脚本**之后**（那时脚本挂在 `await` 上，
   * QuickJS 派发线程空闲）。所以这里从测试线程去调回调。
   *
   * 反过来做——在 `_java` 调用内部（native `evaluate` 尚未返回）就去调 JS 函数——
   * 是对引擎的**重入**调用，本桥在这种时序下会让整轮 Promise 彻底停摆：实测该用例
   * 跑满 608s 也不结束，且派发线程全程空闲、没有任何线程停在 native 里，说明后续
   * job 根本没被驱动。真实实现不会走到这种时序，所以这里不复现它。
   */
  @Test
  fun interceptCallbackCanBeInvokedFromKotlin() {
    val ctx = QuickJS.Context()
    try {
      val started = CompletableDeferred<JSFunction>()
      val finish = CompletableDeferred<Any?>()
      loadInit(
        ctx,
        javaStub { _, _, fn ->
          // 不在回调里直接 assert：那会被 `handleJSInvokable` 转成 JS 异常，
          // 脚本侧只看到一个 rejection，看不出是哪一步出的问题。
          // 这里把「回调真的送过来了」变成测试线程可以直接 await 的结论。
          if (fn == null) {
            started.completeExceptionally(
              AssertionError("onInterceptRequest 是函数时必须作为 JSFunction 送到 Kotlin 侧"),
            )
          } else {
            started.complete(fn)
          }
          finish
        },
      )

      // 脚本会停在 `await webview(...)` 上，等 `finish` 有结论
      var scriptResult: Any? = null
      var scriptFailure: Throwable? = null
      val script =
        Thread {
          try {
            scriptResult =
              evalAsync(
                ctx,
                """
                const ret = await webview("https://example.com/play", {}, null, (request) =>
                  request.headers.Range ? { url: request.url, headers: request.headers } : null);
                return ret === undefined ? "undefined" : "not undefined";
                """.trimIndent(),
              )
          } catch (t: Throwable) {
            scriptFailure = t
          }
        }
      script.start()

      // —— 下面两行等价于「WebView 回调线程调用 onInterceptRequest」——
      val fn = runBlocking { withTimeout(15_000) { started.await() } }
      val hit =
        fn.invoke(
          mapOf(
            "url" to "https://cdn.example.com/seg.ts",
            "headers" to mapOf("Range" to "bytes=0-1023"),
            "method" to "GET",
            "isForMainFrame" to false,
            "isRedirect" to false,
          ),
        )
      val hitMap = assertIs<Map<*, *>>(hit, "回调返回对象时必须能拿回 Kotlin 侧可读的值")
      try {
        assertEquals("https://cdn.example.com/seg.ts", hitMap["url"])
        // 属性访问会为 `headers` 再交出一个包装，同样要还
        val hitHeaders = hitMap["headers"] as? Map<*, *>
        try {
          assertEquals("bytes=0-1023", hitHeaders?.get("Range"))
        } finally {
          (hitHeaders as? AutoCloseable)?.close()
        }
      } finally {
        (hit as? AutoCloseable)?.close()
      }
      // 没命中时回调返回 null，Kotlin 侧按放行处理（`as? Map` 得到 null）
      assertNull(
        fn.invoke(mapOf("url" to "https://a/", "headers" to emptyMap<String, String>())),
        "回调返回 null 必须原样变成 Kotlin null（放行）",
      )

      // 放行：让 webview() 有结论，等价于「什么都没拦到、加载正常跑完」
      finish.complete(null)

      script.join(10_000)
      assertTrue(!script.isAlive, "webview() 的 Promise 没有被解决，脚本线程还挂着")
      scriptFailure?.let { throw it }
      assertEquals("undefined", scriptResult, "没命中拦截时 webview() 应给出 undefined")

      // `started` 里那一票是 Kotlin 侧自己持有的回调包装，用完还给 runtime
      fn.close()
    } finally {
      ctx.close()
    }
  }
}
