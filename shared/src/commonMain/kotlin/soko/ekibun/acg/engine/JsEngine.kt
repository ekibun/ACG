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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import soko.ekibun.acg.common.Http
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSRef
import soko.ekibun.quickjs.QuickJS
import soko.ekibun.web.WebViewEvent
import soko.ekibun.web.createWebviewContext
import soko.ekibun.web.getWebviewCookieManager
import kotlin.time.Duration.Companion.seconds

class JsEngine {
  companion object {
    val instance by lazy { JsEngine() }
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

                  "Cookies.get" ->
                    CoroutineScope(Dispatchers.IO).async {
                      getWebviewCookieManager().get(args[0] as String)
                    }

                  "Cookies.set" ->
                    CoroutineScope(Dispatchers.IO).async {
                      getWebviewCookieManager().set(args[0] as String, args[1] as String)
                    }
                  // webview 的 4 个实参打进 args，不是位置参数。args[3] 是回调，
                  // 它的归属语义见 [webviewAsync]：JS 函数引用由 asyncReleasing 收尾，
                  // 并在本地包一层拦截器转成本地回调。
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
                      )
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
   * 返回**原始值**，由 `init.js` 里的 `webview` wrapper 自己判断形状：
   *
   * - 命中拦截：回 `{ url, headers }` 对象；
   * - 脚本返回值：回 JSON 字符串（可能为空串 / null）；
   * - 无脚本：回 `null`。
   *
   * 失败（环境起不来 / 导航失败 / 超时）抛 [JSError]，让 JS 侧 Promise reject，
   * 而不是回一个让脚本误判的空值。
   *
   * [interceptor] 是 `onInterceptRequest` 的 Kotlin 落地：返回**非 null 且非 false** 的值即命中、
   * 中止加载，并用该值直接 `complete` 后台任务结果（脚本侧拿到什么就是什么）；返回 `null` / `false`
   * 放行。它**不是** [JSFunction] —— JS 侧来的函数引用由调用方（`_binding`）包一层转成本地回调，
   * 票也由调用方归还。
   *
   * 骨架只有这一份：建 headless 上下文（失败即抛 [JSError]）→ 配拦截回调 → 导航 →
   * 等第一个终态（拦截命中 / 脚本结果 / 加载失败）→ 超时兜底 → `finally` 销毁。
   * 终态只取第一个 —— `CompletableDeferred.complete` 幂等，之后的重复事件自然被忽略
   * （Android 拦截命中后 `onPageFinished` 仍会到；桌面命中后的 Stop 会引出一个被
   * native 吞掉的 OperationCanceled）。
   */
  internal suspend fun webviewAsync(
    url: String,
    header: Map<Any, Any?>?,
    script: String?,
    interceptor: ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Any?)?,
  ): Any? {
    // header 展开成纯数据 Map，并补齐后台加载默认要带的 Referer（对齐 http.js：
    // header.referer = header.referer || url）。
    val normalized =
      header?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
    val baseHeaders =
      if (normalized.keys.any { it.equals("Referer", ignoreCase = true) }) {
        normalized
      } else {
        normalized + ("Referer" to url)
      }

    val outcome = CompletableDeferred<Any?>()
    // evaluate 要挂起等结果：平台回调线程（桌面是宿主线程，挂着消息泵）不能钉住，
    // 脚本执行丢到 Dispatchers.IO 上（沿用 _binding 的 inline CoroutineScope(Dispatchers.IO) 派发，不另建 scope）。
    val ctx =
      try {
        createWebviewContext(isHeadless = true)
      } catch (t: Throwable) {
        throw JSError("Create webview failed：${t.message ?: t}")
      }

    try {
      ctx.onEvent = { event ->
        when (event) {
          is WebViewEvent.PageFinished ->
            if (!outcome.isCompleted) {
              if (script == null) {
                // 没传脚本：页面加载完即终态，结果就是 null（init.js 转成 undefined）。
                outcome.complete(null)
              } else {
                CoroutineScope(Dispatchers.IO).launch {
                  // 脚本返回值由平台侧序列化成 JSON 字符串；执行失败静默成 null，
                  // 不让一次脚本错误把整轮搞崩。
                  val json = runCatching { ctx.evaluate(script) }.getOrNull()
                  outcome.complete(json)
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
      return withTimeout(30.seconds) { outcome.await() }
    } catch (_: TimeoutCancellationException) {
      // 超时兜底：与上面的 try 共用 finally 销毁上下文。
      throw JSError("Background webView timeout：$url")
    } finally {
      // close() 非挂起：底层 Pointer 自己把销毁派发到归属线程，调用方不必包 NonCancellable。
      runCatching { ctx.close() }
    }
  }
}
