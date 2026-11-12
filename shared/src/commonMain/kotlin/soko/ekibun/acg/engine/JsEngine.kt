package soko.ekibun.acg.engine

import acg.shared.generated.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.common.DEFAULT_USER_AGENT
import soko.ekibun.acg.common.requestWithDefaultUserAgent
import soko.ekibun.browser.createCallbackSession
import soko.ekibun.browser.launchExternalBrowser
import soko.ekibun.common.Http
import soko.ekibun.common.decodeBytesToText
import soko.ekibun.common.encodeTextToBytes
import soko.ekibun.common.pluginSettingsStore
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.QuickJS
import soko.ekibun.quickjs.dupRecursive
import soko.ekibun.quickjs.freeRecursive
import soko.ekibun.web.WebViewEvent
import soko.ekibun.web.createWebviewContext
import soko.ekibun.web.getWebviewCookieManager

/**
 * 插件的 JS runtime：全进程一个 `QuickJS` context，模块在两次调用之间因此是同一个实例。
 */
object JsEngine {
  private var quickjsDelegate: QuickJS? = null

  /** 建 runtime 与 [reset] 换新时用的互斥锁。 */
  private val quickjsLock = Any()

  private val quickjs: QuickJS
    get() =
      synchronized(quickjsLock) {
        quickjsDelegate ?: run {
          val moduleHandler = { module: String ->
            val modulePath = "files/js/module/" + module.replaceFirst(".js$".toRegex(), "") + ".js"
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
          (
            runBlocking {
              ctx1.evaluate(Res.readBytes("files/js/init.js").decodeToString(), "<init>")
            } as JSFunction
          ).use { init ->
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
                      val rsp = Http.requestWithDefaultUserAgent(args[0] as Map<Any, Any?>)
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

                  "webview" -> {
                    val options = args[1] as Map<*, *>
                    CoroutineScope(Dispatchers.IO).asyncReleasing(args) {
                      webviewAsync(
                        args[0] as String,
                        options["headers"] as Map<Any, Any?>?,
                        options["script"] as String?,
                        (options["onInterceptRequest"] as JSFunction?)?.let { fn ->
                          { url, headers, method, isForMainFrame ->
                            fn.invoke(
                              mapOf(
                                "url" to url,
                                "headers" to headers,
                                "method" to method,
                                "isForMainFrame" to isForMainFrame,
                              ),
                            )
                          }
                        },
                        options["show"] == true,
                        // 返回值归 webviewAsync 定夺，票也跟着走，这里不还
                        (options["onNavigate"] as JSFunction?)?.let { fn ->
                          { url -> fn.invoke(url) }
                        },
                        options["userAgent"] as String?,
                      )
                    }
                  }

                  "openBrowser" ->
                    CoroutineScope(Dispatchers.IO).async {
                      launchExternalBrowser(args[0] as String)
                      null
                    }

                  "oauth" ->
                    CoroutineScope(Dispatchers.IO).async {
                      oauthAsync(args[0] as String)
                    }

                  // 读设置，交回 `Deferred<String?>`
                  "Settings.get" ->
                    CoroutineScope(Dispatchers.IO).async {
                      pluginSettingsStore.get(args[0] as String)
                    }

                  "Settings.set" ->
                    CoroutineScope(Dispatchers.IO).async {
                      pluginSettingsStore.set(args[0] as String, args[1] as String?)
                    }

                  "delay" ->
                    CoroutineScope(Dispatchers.IO).async {
                      delay((args[0] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L)
                      null
                    }

                  else -> throw JSError("unknown method '${argv[0]}'")
                }
              },
            )
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

  /** 异步期间持有 [values] 里的 JS 引用：先加一票，收场时还一票。 */
  private fun <T> CoroutineScope.asyncReleasing(
    vararg values: Any?,
    block: suspend CoroutineScope.() -> T,
  ): Deferred<T> {
    values.dupRecursive()
    val job = async(block = block)
    job.invokeOnCompletion { values.freeRecursive() }
    return job
  }

  /**
   * WebView 桥：导航 [url]（附 [header]），加载完后执行 [script]；失败抛 [JSError]。
   *
   * 每次调用登记成一个 [WebviewTask]，默认后台跑、可提到前台（同一实例，不重建页面）；
   * [show] 为真时创建即前台。只对后台计时：放到后台满 30 秒强制关闭（[WebviewTasks.toBackground]）。
   *
   * [onNavigate] 同步回调导航去向：布尔值就是判断（`true` 取消这次导航），JSON 字符串
   * （`init.js` 序列化过的返回值）是「这次导航就是要的结果」—— 取消导航并以它结算。
   *
   * UA 取 [userAgent] → [header] 里的 `User-Agent` → [DEFAULT_USER_AGENT]；header 里那条会
   * 摘掉再交给页面，否则同一个头会发两个值。
   *
   * 页面加载完成不是终态：没有 [script] 的任务只由 [onNavigate] / 命中拦截 / 加载失败 / 用户关闭
   * 结束；无脚本的 webview 必须能自己收场，否则后台任务满 30 秒被强制关闭（见 shared-layer §5.9）。
   */
  internal suspend fun webviewAsync(
    url: String,
    header: Map<Any, Any?>?,
    script: String?,
    interceptor: ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Any?)?,
    show: Boolean = false,
    onNavigate: ((url: String) -> Any?)? = null,
    userAgent: String? = null,
  ): Any? {
    // header 展开成纯数据 Map；UA 可以写在 header 里，那条单独取出来设到 WebView 上
    val normalized =
      header?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
    val headerUserAgent = normalized.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
    // 导航头里摘掉 UA（已经单独设到页面上，留着会发两个值）；Referer 没给就补成目标地址
    val baseHeaders =
      normalized.filterKeys { !it.equals("User-Agent", ignoreCase = true) }.let { headers ->
        if (headers.keys.any { it.equals("Referer", ignoreCase = true) }) headers else headers + ("Referer" to url)
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
    val outcome = task.outcome
    // 平台只认布尔：布尔就是判断；JSON 字符串是结束值（init.js 序列化过），先结算再取消导航
    ctx.onNavigate =
      onNavigate?.let { decide ->
        { url ->
          when (val decision = decide(url)) {
            is Boolean -> decision
            is String -> {
              outcome.complete(decision)
              true
            }
            else -> false
          }
        }
      }
    WebviewTasks.register(task)
    // 默认后台：开始倒计时；要前台的立刻转正，倒计时随之取消
    WebviewTasks.toBackground(task)
    if (show && !WebviewTasks.toForeground(task)) {
      // 前台已有页面时直接失败，不开看不见的窗口
      WebviewTasks.unregister(task)
      ctx.close()
      throw JSError("webview: a foreground page is already open, close it first")
    }

    try {
      // 创建期配置，必须在首屏导航之前：没有显式 UA 就用全应用那份（与 fetch 同一个串）
      ctx.setUserAgent(userAgent ?: headerUserAgent ?: DEFAULT_USER_AGENT)
      ctx.onEvent = { event ->
        // 标题栏要的地址/标题/进度，与终止判定无关，先喂给任务
        task.onEvent(event)
        when (event) {
          is WebViewEvent.PageFinished ->
            // 加载完成不是终态：没有脚本就什么也不结算（页面还要等人操作），有脚本且脚本给出了值才收工
            if (!outcome.isCompleted && script != null) {
              CoroutineScope(Dispatchers.IO).launch {
                // 平台没给结果（null / 空串 / undefined 的 `"null"`）就等下一次加载完成
                val json = runCatching { ctx.evaluate(script) }.getOrNull()
                if (!json.isNullOrEmpty() && json != "null") outcome.complete(json)
              }
            }

          is WebViewEvent.LoadFailed ->
            outcome.completeExceptionally(JSError("webview run failed：${event.message}"))

          else -> {}
        }
      }
      interceptor?.let { callback ->
        // 命中即中止整轮加载，返回值（init.js 序列化过的字符串）直接当结果
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

  /**
   * 外部浏览器桥：把 [templateUrl] 的 `{{callback}}` 换成回调地址原文，打开浏览器并等回调。
   *
   * 不做任何转义 —— 模板把占位符摆在哪是插件的事（见 `docs/design/browser-login.md`）。
   */
  internal suspend fun oauthAsync(templateUrl: String): String {
    if (!templateUrl.contains("{{callback}}")) {
      throw JSError("oauth template has no {{callback}} placeholder: $templateUrl")
    }
    val session = createCallbackSession()
    try {
      launchExternalBrowser(templateUrl.replace("{{callback}}", session.callbackUrl))
      return session.awaitCallback()
    } finally {
      session.close()
    }
  }
}
