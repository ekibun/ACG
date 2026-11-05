package soko.ekibun.acg.engine

import acg.shared.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import soko.ekibun.browser.createCallbackSession
import soko.ekibun.browser.launchExternalBrowser
import soko.ekibun.common.Http
import soko.ekibun.common.decodeBytesToText
import soko.ekibun.common.encodeTextToBytes
import soko.ekibun.common.settingsStore
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSRef
import soko.ekibun.quickjs.QuickJS
import soko.ekibun.web.WebViewEvent
import soko.ekibun.web.createWebviewContext
import soko.ekibun.web.getWebviewCookieManager

/** 授权链接里标出回调地址位置的占位符，见 [JsEngine.openBrowserAsync]。 */
private const val CALLBACK_PLACEHOLDER = "{{callback}}"

/** 百分号编码时不转义的字符（`encodeURIComponent` 的保留集）。 */
private const val UNRESERVED_CHARS = "-_.!~*'()"

private const val HEX_DIGITS = "0123456789ABCDEF"

class JsEngine {
  companion object {
    val instance by lazy { JsEngine() }
  }

  private var quickjsDelegate: QuickJS? = null

/** 惰性创建 [QuickJS] 时用的互斥锁。 */
  private val quickjsLock = Any()

  private val quickjs: QuickJS
    get() =
      synchronized(quickjsLock) {
        quickjsDelegate ?: run {
          val moduleHandler = { module: String ->
            val modulePath =
              if (module == "@init") {
                "files/js/init.js"
              } else {
                "files/js/module/" + module.replaceFirst(".js$".toRegex(), "") + ".js"
              }
            runBlocking {
              try {
                Res.readBytes(modulePath).decodeToString()
              } catch (_: Exception) {
                null
              }
            }
          }
          val ctx1 = QuickJS(moduleHandler = moduleHandler)
          quickjsDelegate = ctx1
          // init 是工厂函数，取回后调用一次并归还
          val init =
            runBlocking { ctx1.evaluate(moduleHandler("@init")!!, "<init>") } as JSFunction
          try {
            init(
              // `_binding(method, args)` 的实现：按方法名分派到各能力，
              // 见 `docs/design/quickjs-ability-bridge.md`
              JSInvokable { argv ->
                val args = argv[1] as List<*>
                when (argv[0] as String) {
                  "encode" -> encode(args[0] as String, args[1] as String?)
                  "decode" -> decode(args[0] as ByteArray, args[1] as String?)
                  // args[1] 是 console.log(...) 的实参数组，整体送达
                  "console" -> console(args[0] as String, args[1] as List<Any?>)
                  // fetch 只建会话、不读 body，body 由 `Response.arrayBuffer` 按需取；
                  // 交出去的 `_opaque` 是 Response 本体。
                  "fetch" ->
                    CoroutineScope(Dispatchers.IO).async {
                      val rsp = Http.request(args[0] as Map<Any, Any?>)
                      mapOf(
                        "url" to rsp.url,
                        "headers" to rsp.headers,
                        "ok" to (rsp.status in 200..299),
                        "redirected" to (rsp.status in 300..399),
                        "status" to rsp.status,
                        "_opaque" to rsp,
                      )
                    }

                  "Response.arrayBuffer" ->
                    CoroutineScope(Dispatchers.IO).async {
                      (args[0] as Http.Response).readAll()
                    }

                  "Cookies.get" ->
                    CoroutineScope(Dispatchers.IO).async {
                      getWebviewCookieManager().get(args[0] as String)
                    }

                  "Cookies.set" ->
                    CoroutineScope(Dispatchers.IO).async {
                      getWebviewCookieManager().set(args[0] as String, args[1] as String)
                    }
                  // webview 的实参打进 args：args[3] 是拦截回调，args[4] 是「创建即前台」
                  "webview" ->
                    CoroutineScope(Dispatchers.IO).asyncReleasing(args[3] as JSFunction?) {
                      webviewAsync(
                        args[0] as String,
                        args[1] as Map<Any, Any?>?,
                        args[2] as String?,
                        (args[3] as JSFunction?)?.let { fn ->
                          { url, headers, method, isForMainFrame ->
                            try {
                              fn.invoke(
                                mapOf(
                                  "url" to url,
                                  "headers" to headers,
                                  "method" to method,
                                  "isForMainFrame" to isForMainFrame,
                                ),
                              )
                            } catch (e: Throwable) {
                              e.printStackTrace()
                              false
                            }
                          }
                        },
                        args[4] == true,
                      )
                    }

                  // openBrowser 的实参：带 `{{callback}}` 占位符的授权链接
                  "openBrowser" ->
                    CoroutineScope(Dispatchers.IO).async {
                      openBrowserAsync(args[0] as String)
                    }

                  // 读设置，交回 `Deferred<String?>`，没有该键即 null
                  "Settings.get" ->
                    CoroutineScope(Dispatchers.IO).async {
                      settingsStore.get(args[0] as String)
                    }

                  // 写设置，值为 null 即删该键
                  "Settings.set" ->
                    CoroutineScope(Dispatchers.IO).async {
                      settingsStore.set(args[0] as String, args[1] as String?)
                    }

                  else -> throw JSError("unknown method '${argv[0]}'")
                }
              },
            )
          } finally {
            init.close()
          }
          ctx1
        }
      }

  /** 在插件 runtime 上求值 [cmd]。 */
  suspend fun evaluate(
    cmd: String,
    name: String = "<eval>",
  ): Any? = quickjs.evaluate(cmd, name)

  /** 调插件模块 [module] 导出的函数 [function]，参数为 [args]；模块名不带 `.js`。 */
  suspend fun callPlugin(
    module: String,
    function: String,
    vararg args: Any?,
  ): Any? {
    val exported =
      evaluate("(async () => (await import(${jsStringLiteral(module)}))[${jsStringLiteral(function)}])()")
    val pluginFunction = (exported as? Deferred<*>)?.await()
    if (pluginFunction !is JSFunction) {
      throw JSError("module '$module' exports no function '$function'")
    }
    val result =
      try {
        pluginFunction.invokeAsync(*args)
      } finally {
        // 归还 evaluate 交出的导出函数引用
        pluginFunction.close()
      }
    return if (result is Deferred<*>) result.await() else result
  }

  fun reset() {
    // 销毁 runtime 并清空引用
    synchronized(quickjsLock) {
      quickjsDelegate?.close()
      quickjsDelegate = null
    }
  }

  private fun console(
    type: String,
    data: List<Any?>,
  ) {
    println("$type\n$data")
  }

  /** 把 [value] 包成 JS 字符串字面量（含转义）。 */
  private fun jsStringLiteral(value: String): String =
    "'" +
      value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029") +
      "'"

  /** 把 [input] 按 [to] 编码成字节 —— `TextEncoder` 的实现；[to] 为 null 时用 utf-8。 */
  private fun encode(
    input: String,
    to: String?,
  ): ByteArray = encodeTextToBytes(input, to ?: "utf-8")

  /** 把 [input] 按 [from] 解码成字符串 —— `TextDecoder` 的实现；非法字节替换成 U+FFFD。 */
  private fun decode(
    input: ByteArray,
    from: String?,
  ): String = decodeBytesToText(input, from ?: "utf-8")

  /** 起一个 [Deferred]，收场时关闭 [values] 里的 JS 引用。 */
  private fun <T> CoroutineScope.asyncReleasing(
    vararg values: JSRef?,
    block: suspend CoroutineScope.() -> T,
  ): Deferred<T> {
    val job = async(block = block)
    job.invokeOnCompletion { values.forEach { it?.close() } }
    return job
  }

  /**
   * WebView 桥：导航 [url]（附 [header]），加载完后执行 [script]；失败抛 [JSError]。
   *
   * 每次调用登记成一个 [WebviewTask]，默认后台跑、可提到前台（同一实例，不重建页面）；
   * [show] 为真时创建即前台。只对后台计时：放到后台满 30 秒强制关闭（[WebviewTasks.toBackground]）。
   */
  internal suspend fun webviewAsync(
    url: String,
    header: Map<Any, Any?>?,
    script: String?,
    interceptor: ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Any?)?,
    show: Boolean = false,
  ): Any? {
    // header 展开成纯数据 Map，并补齐后台加载默认带的 Referer
    val normalized =
      header?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
    val baseHeaders =
      if (normalized.keys.any { it.equals("Referer", ignoreCase = true) }) {
        normalized
      } else {
        normalized + ("Referer" to url)
      }

    // 建 WebView 上下文：后台任务按 headless 建，创建后仍可切前台
    val ctx =
      try {
        createWebviewContext(isHeadless = true)
      } catch (t: Throwable) {
        throw JSError("Create webview failed: ${t.message ?: t}")
      }

    val task = WebviewTask(url, foregroundOnly = show)
    task.controller = ctx
    WebviewTasks.register(task)
    // 默认后台：开始倒计时；要前台的立刻转正，倒计时随之取消
    WebviewTasks.toBackground(task)
    if (show) WebviewTasks.toForeground(task)
    val outcome = task.outcome

    try {
      ctx.onEvent = { event ->
        // 标题栏要的地址/标题/进度，与终止判定无关，先喂给任务
        task.onEvent(event)
        when (event) {
          is WebViewEvent.PageFinished ->
            if (!outcome.isCompleted) {
              if (script == null) {
                // 无脚本：加载完即终态，结果 null
                outcome.complete(null)
              } else {
                CoroutineScope(Dispatchers.IO).launch {
                  // 脚本返回值是 JSON 字符串；给出 null 表示「还没到位」，等下一次加载完成
                  val json = runCatching { ctx.evaluate(script) }.getOrNull()
                  if (!json.hasNoScriptResult()) outcome.complete(json)
                }
              }
            }

          is WebViewEvent.LoadFailed ->
            outcome.completeExceptionally(JSError("webview run failed：${event.message}"))

          else -> {}
        }
      }
      interceptor?.let { callback ->
        ctx.onInterceptor = { u, headers, method, isForMainFrame ->
          val ret = callback(u, headers, method, isForMainFrame)
          if (ret != null && ret != false && !outcome.isCompleted) {
            outcome.complete(ret)
            true
          } else {
            false
          }
        }
      }
      ctx.navigate(url, baseHeaders)
      return outcome.await()
    } finally {
      WebviewTasks.unregister(task)
      // 销毁上下文（close 非挂起）
      runCatching { ctx.close() }
    }
  }

  /** 外部浏览器桥：把 [templateUrl] 的 `{{callback}}` 换成编码后的回调地址，打开浏览器并等回调。 */
  internal suspend fun openBrowserAsync(
    templateUrl: String,
    launch: suspend (String) -> Unit = ::launchExternalBrowser,
  ): String {
    if (!templateUrl.contains(CALLBACK_PLACEHOLDER)) {
      throw JSError("openBrowser template has no $CALLBACK_PLACEHOLDER placeholder: $templateUrl")
    }
    val session = createCallbackSession()
    try {
      val authorizeUrl =
        templateUrl.replace(CALLBACK_PLACEHOLDER, percentEncodeComponent(session.callbackUrl))
      try {
        launch(authorizeUrl)
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        throw JSError("Launch external browser failed: ${t.message ?: t}")
      }
      return session.awaitCallback()
    } finally {
      session.close()
    }
  }

  /** 按 `encodeURIComponent` 的保留集做百分号编码（非 ASCII 取 UTF-8 字节）。 */
  private fun percentEncodeComponent(value: String): String =
    buildString {
      value.encodeToByteArray().forEach { raw ->
        val code = raw.toInt() and 0xFF
        val char = code.toChar()
        val keep =
          code < 0x80 &&
            (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || UNRESERVED_CHARS.contains(char))
        if (keep) {
          append(char)
        } else {
          append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0xF])
        }
      }
    }
}

/**
 * 脚本这次没给出结果：`null`、字符串 `"null"`、空串。
 *
 * 平台把返回值按 JSON 编码交回，所以脚本 `return null` 会变成 `"null"`，
 * `JSON.stringify(null)` 则是带引号的 `"\"null\""`，两种都算「还没到位」。
 */
internal fun String?.hasNoScriptResult(): Boolean =
  this == null || this == "null" || this == "\"null\"" || this == "\"\""
