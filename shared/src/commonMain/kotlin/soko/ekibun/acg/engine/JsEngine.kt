package soko.ekibun.acg.engine

import acg.shared.generated.resources.Res
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import io.ktor.util.toMap
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.encodeToByteArray
import io.ktor.utils.io.charsets.forName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import soko.ekibun.common.Http
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSRef
import soko.ekibun.quickjs.QuickJS
import soko.ekibun.quickjs.freeRecursive
import soko.ekibun.web.WebViewEvent
import soko.ekibun.web.createWebviewContext

class JsEngine {
  companion object {
    val instance by lazy { JsEngine() }

    /** [webviewAsync] 与 `init.js` 里内联的 `webview` wrapper 约定的结果标记键。 */
    private const val WEBVIEW_KIND_KEY = "__webview_kind__"
  }

  private var quickjsDelegate: QuickJS? = null

  /**
   * 惰性初始化的锁：首建是一段 check-then-act（建 runtime、求值 init、赋值），
   * 两个协程并发首访会各建一个 [QuickJS]，后赋值者覆盖先前 —— 那一个永不
   * [close][QuickJS.close]（runtime + 归属线程泄漏）。锁里那些 `runBlocking`
   * 没有死锁风险：等的是 QuickJS 的归属线程，而归属线程上的回调
   * （`handleJSInvokable` / `init` 那个桥）都不进这把锁。
   */
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
          // `init` 是**工厂函数**，JS 侧没别的引用持有它，调用完即可归还 —— 但必须 `as JSFunction`：
          // 声明成 `as JSInvokable` 会丢掉 `AutoCloseable`，每个引擎实例固定漏 1 票（见 [ability-bridge]）。
          // 这里在属性 getter 里、没有协程上下文，只能阻塞等（`evaluate` 本身是挂起的）。
          val init =
            runBlocking { ctx1.evaluate(moduleHandler("@init")!!, "<init>") } as JSFunction
          try {
            init(
              // `_binding(method, args)` 的落地：`argv` 是 init.js 那一**份**实参（`vararg` 打的
              // `Array<Any?>`），`thisVal` 是隐式接收者、不占位。`when` **直接调**各能力、不按名字找 ⇒
              // `private` 天然不受影响、写错名字是编译错误（反射走不通，依据见 [ability-bridge]）。
              JSInvokable { argv ->
                val args = argv[1] as List<*>
                when (argv[0] as String) {
                  "encode" -> encode(args[0] as String, args[1] as String?)
                  "decode" -> decode(args[0] as ByteArray, args[1] as String?)
                  // args[1] 是 console.log(...) 的**实参数组**，作为 args 的元素整体送达，
                  // 不是摊平成位置参数。
                  "console" -> console(args[0] as String, args[1] as List<Any?>)
                  // `fetch` 只建会话、**不读 body**：body 交给 `Response.arrayBuffer`
                  // 按需取（`init.js` 那边 await 之后才调）。所以 `_opaque` 交的是
                  // [Http.Response] 本体，JS 侧那个 `JavaObject` 包装就是 finalizer
                  // 能回收它的抓手（`close()` 由析构回调代调，见 cxx/quickjs/quickjs.cpp）。
                  "fetch" ->
                    CoroutineScope(Dispatchers.IO).async {
                      val rsp = Http.request(args[0] as Map<Any, Any?>)
                      val meta = rsp.delegate
                      mapOf(
                        "url" to meta.request.url.toString(),
                        "headers" to meta.headers.toMap(),
                        "ok" to meta.status.isSuccess(),
                        "redirected" to (meta.status.value in 300..399),
                        "status" to meta.status.value,
                        "_opaque" to rsp,
                      )
                    }
                  "Response.arrayBuffer" ->
                    CoroutineScope(Dispatchers.IO).async {
                      (args[0] as Http.Response).readAll()
                    }
                  // webview 的 4 个实参打进 args，不是位置参数。args[3] 是回调，
                  // 它的归属语义见 [webviewAsync]。
                  "webview" ->
                    webviewAsync(
                      args[0] as String,
                      args[1] as Map<Any, Any?>?,
                      args[2] as String?,
                      args[3] as JSFunction?,
                    )
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

  /**
   * 求值。**挂起**：[QuickJS.evaluate] 会把求值搬到 runtime 的归属线程上。
   *
   * 这里不做 `runBlocking` 包一层 —— 门面一旦阻塞，UI 侧（[soko.ekibun.acg.ui.screen
   * .CodeScreen]）就得跟着卡。调用方本来就在协程里。
   */
  suspend fun evaluate(
    cmd: String,
    name: String = "<eval>",
  ): Any? = quickjs.evaluate(cmd, name)

  fun reset() {
    // 主动销毁 runtime，而不是只把引用置空等 GC：JS 侧的 Java 对象持有
    // global ref，只有 destroyContext 触发的析构才会把它们还回去。
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

  /**
   * [input] 按 [to] 编码成字节 —— `TextEncoder` 那侧的实现。
   *
   * [to] 是 JS 侧 `new TextEncoder(label)` 的 `label`，**原样交给 charset 表**：大小写、
   * 别名（`utf8` / `UTF-8`）、非 UTF 编码都认，**不认识就抛**（`UnsupportedCharsetException`
   * / `IllegalCharsetNameException`），JS 侧 `catch` 接得住。
   * **别兜底成 UTF-8**：静默换编码会让请求体以另一种编码发出去，而对端按声明的编码解，
   * 服务端收到的是乱码却不报错。
   *
   * 走 ktor 的 [io.ktor.utils.io.charsets]：它那套是 **commonMain** 声明（`forName` 是
   * `expect`，JVM 上直接转发 `java.nio.charset.Charset.forName`），所以 `commonMain` 里
   * 不出现平台符号。
   */
  private fun encode(
    input: String,
    to: String?,
  ): ByteArray = Charsets.forName(to ?: "utf-8").newEncoder().encodeToByteArray(input)

  /**
   * [input] 按 [from] 解码成字符串 —— `TextDecoder` 那侧的实现。
   *
   * [from] 的来由与判据与 [encode] 完全一致（同一个 `label` 参数、同一种「不认识就抛」）。
   * 非法字节按 charset 的默认策略**替换**（U+FFFD），不抛。
   *
   * 经 [ByteString] 而不是 decoder：ktor 的 `decode` 收的是 `Source`，而 `ByteString` 是
   * `Source` 的一种 —— ktor 自己在 JVM 上实现那条时走的也是 `ByteString.decodeToString`。
   */
  private fun decode(
    input: ByteArray,
    from: String?,
  ): String = ByteString(input).decodeToString(Charsets.forName(from ?: "utf-8"))

  /**
   * 起一个 [Deferred]，并在它收场时（成功 / 失败 / 取消）归还接手的 JS 实参。
   *
   * 不能用 `async` 体里的 `finally`：Deferred 被取消、协程体根本没跑起来时，
   * `finally` 不会执行，那一票就永远挂在 `QuickJS` 的登记表上了（关闭时才由
   * `collectLeaks` 兜掉并报一条 `reference leak`）。`invokeOnCompletion`
   * 三种收场都覆盖。
   */
  private fun <T> CoroutineScope.asyncReleasing(
    // 形参刻意收窄成 JSRef 而不是 AutoCloseable：QuickJS 自己也实现了 AutoCloseable，
    // 若这里接受 AutoCloseable，把 runtime 传进来就会被 invokeOnCompletion 当场销毁。
    vararg values: JSRef?,
    block: suspend CoroutineScope.() -> T,
  ): Deferred<T> {
    val job = async(block = block)
    job.invokeOnCompletion { values.forEach { it?.close() } }
    return job
  }

  /**
   * 后台 WebView 桥 —— 插件 JS 里的 `webview(url, header, script, onInterceptRequest)`。
   *
   * 两处有意的差异：
   *
   * 1. 返回 [Deferred]（JS 侧是 Promise），不阻塞 —— QuickJS 只有一个事件循环，
   *    照参照实现那样 `Thread.sleep` 等加载会把整个引擎锁死；
   * 2. 结果多包一层 [WEBVIEW_KIND_KEY]，好让 JS wrapper 区分「命中拦截」与
   *    「脚本返回值」—— 两者都可能是任意对象，不加标记无从判别。
   *
   * `onInterceptRequest` 是全图里**唯一**要归还的东西：函数仍以 [JSFunction]
   * 包装存在、持着一票，而且它要活到 WebView 任务结束（早还就会让回调打在已经
   * 释放的包装上），所以交给 [asyncReleasing]。
   *
   * `header` 不用归还 —— 它和 `options` 一样，已经被整图展开成纯数据的 `Map`。
   */
  private fun webviewAsync(
    url: String,
    header: Map<Any, Any?>?,
    script: String?,
    onInterceptRequest: JSFunction?,
  ): Deferred<Any?> =
    CoroutineScope(Dispatchers.IO).asyncReleasing(onInterceptRequest) {
      val task =
        WebViewTask(
          url = url,
          headers =
            header?.entries?.associate { it.key.toString() to it.value.toString() }
              ?: emptyMap(),
          script = script,
          onInterceptRequest =
            onInterceptRequest?.let { fn ->
              { url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean ->
                invokeInterceptor(fn, url, headers, method, isForMainFrame)
              }
            },
        )

      val payload: Any? =
        when (val result = loadBackgroundWebView(task)) {
          is WebViewTaskResult.Intercepted ->
            mapOf(
              WEBVIEW_KIND_KEY to "intercept",
              "value" to
                mapOf(
                  "url" to result.url,
                  "headers" to result.headers,
                ),
            )

          is WebViewTaskResult.Scripted ->
            mapOf(
              WEBVIEW_KIND_KEY to "script",
              "value" to result.json,
            )

          // 失败要让 JS 侧 reject，而不是回一个空值让脚本去猜哪一步没生效。
          is WebViewTaskResult.Failed -> throw JSError(result.message)
        }
      payload
    }

  /**
   * 把 WebView 的拦截回调转给 JS 侧的 `onInterceptRequest`，返回它的**真值** ——
   * `true` = 命中。
   *
   * JS 回调抛错按放行处理 —— 一次回调出错不该把整次加载搞崩，但会把错误打到
   * console 上，别让它无声无息。
   *
   * 回调收到的是 `{ url, headers, method, isForMainFrame }` 四个字段（Android 独有的
   * `isRedirect` 两端对不齐，没上进接口）；老脚本 `return { url, headers }` 仍然命中
   * （对象是真值），只是那份改写后的 url / headers 不再被采用 —— 回传给脚本的是
   * **被拦下请求自己**的那一份。
   *
   * 它的返回值在 Kotlin 侧已经被 `jsToJava` 整图展开成 `Map` / `Array` 那样的纯数据，
   * 数据本身没有票可还，但展开出来的图里可能嵌着函数包装（它们各持一票），所以收尾用
   * `freeRecursive()` 穿透容器去还 —— 不还的话每拦一次请求就在 `QuickJS` 的登记表上
   * 挂一笔。
   *
   * 调用时机：本函数跑在 WebView 的回调线程上，此时脚本正挂起等 `webviewAsync`
   * 的结果，QuickJS 派发线程是空闲的，所以这里的 `fn.invoke` / `close`
   * 都能安全地借道 `Pointer.withPtrSync` 落到 JS 线程（`fn.invoke` 内部就是它）。
   */
  private fun invokeInterceptor(
    fn: JSFunction,
    url: String,
    headers: Map<String, String>,
    method: String,
    isForMainFrame: Boolean,
  ): Boolean {
    val ret =
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
        console("error", listOf("onInterceptRequest 回调出错，按放行处理: $e"))
        return false
      }
    try {
      // 按 JS 真值判定：`null` / `undefined` / `false` / `0` / `""` 是放行，其余命中
      // （对象也是真值，老脚本那种 `return { url, headers }` 因此仍然命中）。
      return when (ret) {
        null, false -> false
        is Number -> ret.toDouble() != 0.0 && !ret.toDouble().isNaN()
        is String -> ret.isNotEmpty()
        else -> true
      }
    } finally {
      ret.freeRecursive()
    }
  }
}

/** 一次后台 WebView 任务。字段与 `webview(url, header, script, onInterceptRequest)` 一一对应。 */
data class WebViewTask(
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  /** 页面加载完成后注入并取回其返回值的脚本；null 表示不注入。 */
  val script: String? = null,
  /**
   * 请求拦截：命中即中止加载，被拦下的 url + headers 经 [WebViewTaskResult.Intercepted]
   * 回到脚本。入参是 `(url, headers, method, isForMainFrame)`，与平台侧
   * `soko.ekibun.web.WebviewContext.onInterceptor` 同一个口径。
   * **会在 WebView 的回调线程上被调用**，实现里不要假设自己在哪条线程。
   *
   * 硬约束：**不能从 JS 调用内部同步回调它** —— 脚本调 `webviewAsync` 时 `_binding`
   * 仍在 native `evaluate` 里没返回，此时反向调 JS 是对引擎的重入，本桥会让整轮
   * Promise 停摆（实测挂死不返回）。拦截回调必须来自平台侧
   * （`shouldInterceptRequest` / `WebResourceRequested`），那一刻脚本正挂在 `await` 上。
   */
  val onInterceptRequest:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)? = null,
) {
  /**
   * 补齐后台加载默认要带的头，对齐 `http.js`：`header.referer = header.referer || url`。
   *
   * 纯函数：不写传入的 [headers]，缺 `Referer` 时返回加了它的**新** Map —— 同一个
   * [WebViewTask] 可能被跑不止一轮，改原对象会把上一轮的 `Referer` 带进这一轮。
   */
  fun effectiveHeaders(): Map<String, String> =
    if (headers.keys.any { it.equals("Referer", ignoreCase = true) }) {
      headers
    } else {
      headers + ("Referer" to url)
    }
}

/** 一次后台 WebView 任务的结果。 */
sealed interface WebViewTaskResult {
  /** 命中了 [WebViewTask.onInterceptRequest]：回传**被拦下那个请求自己**的 url + headers。 */
  data class Intercepted(
    val url: String,
    val headers: Map<String, String>,
  ) : WebViewTaskResult

  /** [WebViewTask.script] 的返回值，已由平台侧序列化成 JSON 字符串（可能为 null）。 */
  data class Scripted(
    val json: String?,
  ) : WebViewTaskResult

  /**
   * 没跑起来或者超时：WebView 环境不可用（比如 Windows 没装 WebView2 运行时）、
   * 导航失败、脚本执行失败。
   *
   * 之所以要单独一个分支而不是简单返回 null：这些情况很容易被**静默降级成
   * 空壳**（既不加载也不报错），脚本侧只会看到一个永远为空的结果。给一条明确的
   * 失败信息，比让人去猜哪一步没生效强。
   */
  data class Failed(
    val message: String,
  ) : WebViewTaskResult
}

/** 后台任务的默认超时。`http.js` 是「页面加载完再等 10s」，这里给整轮一个上限。 */
const val BACKGROUND_WEBVIEW_TIMEOUT_MS: Long = 30_000

/**
 * 跑一次后台 WebView 任务；调用方挂起直到页面出结果、命中拦截或超时。
 *
 * 骨架只有这一份：建 headless 上下文（失败即 [WebViewTaskResult.Failed]）→ 配拦截
 * 回调 → 导航 → 等第一个终态（拦截命中 / 脚本结果 / 加载失败）→ 超时兜底 →
 * `finally` 销毁。终态只取第一个 —— `CompletableDeferred.complete` 幂等，之后的重复
 * 事件自然被忽略（Android 拦截命中后 `onPageFinished` 仍会到；桌面命中后的 Stop 会
 * 引出一个被 native 吞掉的 OperationCanceled）。
 *
 * 命中之后不再跑脚本由 `outcome.isCompleted` 兜住：脚本结果要等页面加载完才可能来，
 * 而命中那一刻结果就已经定了。
 */
suspend fun loadBackgroundWebView(task: WebViewTask): WebViewTaskResult {
  val outcome = CompletableDeferred<WebViewTaskResult>()
  // evaluate 要挂起等结果：平台回调线程（桌面是宿主线程，挂着消息泵）不能钉住，
  // 脚本执行丢到这条独立 scope 上。
  val evalScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  val ctx =
    try {
      createWebviewContext(isHeadless = true)
    } catch (t: Throwable) {
      evalScope.cancel()
      return WebViewTaskResult.Failed("后台 WebView 起不来：${t.message ?: t}")
    }

  try {
    ctx.onEvent = { event ->
      when (event) {
        is WebViewEvent.PageFinished ->
          if (!outcome.isCompleted) {
            val script = task.script
            if (script == null) {
              outcome.complete(WebViewTaskResult.Scripted(null))
            } else {
              evalScope.launch {
                val json = runCatching { ctx.evaluate(script) }.getOrNull()
                outcome.complete(WebViewTaskResult.Scripted(json))
              }
            }
          }

        is WebViewEvent.LoadFailed ->
          outcome.complete(WebViewTaskResult.Failed("后台 WebView 失败：${event.message}"))

        else -> {}
      }
    }
    task.onInterceptRequest?.let { callback ->
      ctx.onInterceptor = { url, headers, method, isForMainFrame ->
        if (callback(url, headers, method, isForMainFrame) && !outcome.isCompleted) {
          outcome.complete(WebViewTaskResult.Intercepted(url, headers))
          true
        } else {
          false
        }
      }
    }
    ctx.navigate(task.url, task.effectiveHeaders())
    return withTimeoutOrNull(BACKGROUND_WEBVIEW_TIMEOUT_MS) { outcome.await() }
      ?: WebViewTaskResult.Failed("后台 WebView 超时（${BACKGROUND_WEBVIEW_TIMEOUT_MS}ms）：${task.url}")
  } finally {
    evalScope.cancel()
    // 超时那条路进这里时协程已被 withTimeoutOrNull 取消，suspend 的 dispose 会立刻抛
    // CancellationException —— 不包 NonCancellable 就是「不报错、只失效」的上下文泄漏。
    withContext(NonCancellable) { runCatching { ctx.dispose() } }
  }
}
