# WebView 接口重设计：WebviewContext

> 状态：**已落地（2026-10-13）**。本行已按落地更新，  
> 口径以用户当日的补充裁定为准。**§9 测试迁移与 §10 连带文档尚未做。**
>
> 与 [architecture.md](../../../../docs/architecture.md) §4「前后台同实例两种可见态」的关系：那条**不变**，  
> 本文只重做它下面的平台契约层（接口形状、工厂签名、后台骨架的归属）。

## 1. 为什么重做

现状（写作时点）的三个接口问题：

1. **`WebViewController` 是壳，真身套了三层**：桌面真身在 native 绑定 `WebviewContext`  
   外罩着 `DesktopWebViewController`（`WebView.jvm.kt:153`），后台任务再罩一层  
   `by delegate` 匿名对象（`BackgroundWebView.jvm.kt:113`）—— 一个上下文三个对象。  
   `WebViewSurface` 的强转、research-2026-10-02.md §164 记的「委托对象  
   ClassCastException」都是这层套娃的副产品。
2. **`createWebViewController` 有 8 个参数**（`WebView.kt:331-339`），其中 `headless` /  
   `initialUrl` / `script` / `onRequest` 全是**调用方自己的任务状态**，却塞进平台工厂。
3. **`BackgroundWebViewHost()` 职责与宿主错位**：它存在的唯一理由是给 Android 喂  
   `Context`（`BackgroundWebView.android.kt:23-31`），却挂在 `App()` 的组合里  
   （`App.kt:40`）；后台任务跑在 JsEngine 里，没有组合上下文。

重做后：一个接口 `WebviewContext`、一个工厂 `createWebviewContext(isHeadless)`、  
后台任务骨架搬进 shared 由 JsEngine 驱动、每端只出一个实现类。

## 2. 决策记录（2026-10-13 用户裁定）

| #   | 决策点                                     | 裁定                                                                                                                                                                                                  | 依据                                                                                                                                                                                                                                         |
| --- | --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| D1  | headless 模式放哪                           | **工厂可选参 `createWebviewContext(isHeadless: Boolean = false)`**，接口不放 `setHeadless`                                                                                                                    | 模式是创建期决策，不是运行时旋钮                                                                                                                                                                                                                           |
| D2  | 后台驱动落点                                  | **并进 `shared/.../engine/JsEngine.kt`**（同文件 top-level）                                                                                                                                               | 驱动只被 JsEngine 消费                                                                                                                                                                                                                           |
| D3  | `WebViewConfig.allowNewWindow` / `zoom` | **删**                                                                                                                                                                                               | 无调用方改过值；`WebScreen` 显式设的 `false` 就是默认值                                                                                                                                                                                                     |
| D4  | 接口 `navigate` 带不带 `method`              | **不带**                                                                                                                                                                                              | Android 侧没有对应物：`android.webkit.WebView.loadUrl(url, headers)` 只有 GET（POST 走 `postUrl(url, bytes)`，没有 headers + method 的组合）；桌面 native 虽有 `method` 形参，全部调用方都传 `"GET"`（`WebView.jvm.kt:67`、`BackgroundWebView.jvm.kt:110`）。判据：只在两端都有对应物时才上进接口 |
| D5  | Android `Context` 来源                    | **`lateinit var webviewApplicationContext` + 宿主（`MainActivity.onCreate`）注入一次**                                                                                                                      | 供给点必须是进程级的（后台任务没有组合上下文）；漏注入是宿主编程错误 —— `lateinit` 当场炸，平台层不做兜底                                                                                                                                                                               |
| D6  | `evaluate` 默认实现                         | **不留**                                                                                                                                                                                              | 两端都真实现了，不为不存在的平台写兜底                                                                                                                                                                                                                        |
| D7  | 测试迁移                                    | **认可**：任务级用例随驱动进 `shared/jvmTest`，直构平台类的留 `platform/jvmTest`                                                                                                                                        | `internal` 跨模块不可见                                                                                                                                                                                                                          |
| D8  | 接口函数形态                                  | **接口上的函数全部 `suspend`**（`setUserAgent` / `setDevtoolsEnabled` / `navigate` / `evaluate` / `reload` / `stop` / `goBack` / `goForward` / `openDevTools` / `dispose`，无例外）                               | 每条命令都要落到归属线程（Android 主线程 / 桌面 WebView 宿主线程）并返回；`submit` 那种 fire-and-forget 报不出「已落实」。唯一两个调用方（驱动、可见壳的 `LaunchedEffect`）本来就在挂起上下文里                                                                                                            |
| D9  | 两个回调槽的形态                                | **`var onEvent` / `var onInterceptor` 两个可变属性**，不是 suspend 函数、也不是 setter                                                                                                                             | 回调是「每次任务各一份」的槽位，赋值是纯字段写，挂起没有意义                                                                                                                                                                                                             |
| D10 | 拦截回调签名                                  | **`var onInterceptor: ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)?`**（返回 `true` = 命中）；**删除 `WebViewInterception`**，连带删 `WebViewRequest`（§5.1） | 两端 native 回调本来就是「给不给命中」的布尔判断（`webview.cpp` 的 `onWebResourceRequested` 返回 bool、`shouldInterceptRequest` 用返回值决定）。**四个入参**：Android 独有的 `isRedirect`（桌面恒 false）不上接口；`method` / `isForMainFrame` 两端都给得到，留下 —— 脚本靠它们区分主框架与子资源                    |

D8 / D9 的实现连带（不能漏）：

- **jvm 侧的 `submit { xxx(ptr) }` 全部换成 `withPtr { ptr -> xxx(ptr) }`**  
  （`Pointer.withPtr` 在归属线程上执行、带句柄，语义正是「投递并等它落地」）。  
  例外：`attach` / `detach` / `fitToParent` / `setBounds` / `focus` **保持非 suspend** ——  
  它们不上接口，且调用点全在没有协程上下文的 AWT 回调里（`ComponentListener`）。
- **两个 `var` 不需要任何同步**：它们是「创建之后、首次导航之前一次性注入」的槽位  
  （驱动与可见壳都这么用），不是运行期反复改的状态 ⇒ 不加 `@Volatile`、不切线程。
- **`dispose()` 变 suspend 后，两处「没有协程上下文 / 已被取消」的收尾点要单独处理**：  
  驱动超时进 `finally` 时协程已被取消（必须 `withContext(NonCancellable)`），  
  组合的 `onDispose` 里没有协程上下文（必须丢到一个不被取消的进程级 scope 上）。详见 §5 / §7。

## 3. 新接口（platform commonMain，`soko.ekibun.web`）

```kotlin
/**
 * 平台 WebView 上下文 —— 可见页与后台任务共用同一个类型，差别只在调用方怎么用：
 * 可见壳配好 setter 后挂 [WebViewSurface]；后台驱动拿 `isHeadless = true` 的实例走
 * 「拦截 + evaluate」。
 *
 * 所有函数都是挂起的：每条命令落到**归属线程**上（桌面 = WebView 宿主线程，
 * Android = 主线程）执行完再返回，实现内部不再有 fire-and-forget 的投递。
 *
 * [onEvent] 与 [onInterceptor] 是从归属线程上调进来的：接收方自己保证线程安全，
 * 且**不要在回调里同步调回本接口的挂起方法**（桌面那会钉住宿主线程的消息泵，
 * 后台驱动里 `evaluate` 因此必须丢到独立 scope 上，见 §5）。
 * 两个槽位都是**创建之后、首次导航之前一次性设好**的（不是运行期反复改的状态），
 * 所以实现侧就是普通字段，不需要 `@Volatile` 之类的同步。
 */
interface WebviewContext {
  /**
   * 页面生命周期事件出口。创建后、导航前设好；null = 摘掉。
   */
  var onEvent: ((WebViewEvent) -> Unit)?

  /**
   * 请求拦截：返回 `true` = 命中（桌面：native 给空 204 并 Stop；Android：照常放行、
   * 只把结果交出去，见 §6.2）。null = 不拦。
   *
   * `method` 与 `isForMainFrame` 两端都给得到；Android 独有的 `isRedirect` 两边对不齐，不上回调。
   */
  var onInterceptor:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)?

  /** 自定义 User-Agent。 */
  suspend fun setUserAgent(ua: String)

  /** F12 / Chrome 远程调试开关。桌面无操作（`openDevTools` 不依赖它）。 */
  suspend fun setDevtoolsEnabled(enabled: Boolean)

  /** 导航到 [url]，附带 [headers]。 */
  suspend fun navigate(
    url: String,
    headers: Map<String, String> = emptyMap(),
  )

  /**
   * 在当前页执行脚本，返回结果的 JSON 字符串；没有返回值或失败都是 `null`
   * （不是 `"null"` —— 后者是 WebView2 对 `undefined` 的序列化，见
   * `Webview2Test` 里钉住的已知不一致）。
   */
  suspend fun evaluate(script: String): String?

  suspend fun reload()

  suspend fun stop()

  suspend fun goBack()

  suspend fun goForward()

  suspend fun openDevTools()

  /**
   * 收尾：可见页组合退出时、后台任务 `finally` 里。必须幂等。
   * 返回时销毁动作已在归属线程上发起（重复调用被静默忽略）。
   */
  suspend fun dispose()
}
```

`WebViewEvent` 同步瘦身 —— 删三个后台终态事件（它们的回传通道正是被拆掉的创建入参）：

| 删掉                   | 原用途               | 之后由谁产出                                         |
| -------------------- | ----------------- | ---------------------------------------------- |
| `RequestIntercepted` | 把拦截命中带回 common 骨架 | 驱动自己的 lambda 里直接 `outcome.complete`            |
| `ScriptFinished`     | 回传脚本结果            | 驱动调 `evaluate` 自己产出                            |
| `TaskFailed`         | 后台起不来 / 导航失败      | 驱动把 `LoadFailed` 折成 `WebViewTaskResult.Failed` |

剩下 8 个（`PageStarted` / `Progress` / `UrlChanged` / `DocumentLoaded` / `PageFinished` /  
`TitleChanged` / `HistoryChanged` / `LoadFailed`）全部是可见页语义，  
`WebViewState.onWebViewEvent` 的 `when` 成为全穷举（`WebView.kt:274` 的 `else -> Unit`  
分支删除）。

## 4. 创建入口

```kotlin
/**
 * 建一个平台 WebView 上下文。
 *
 * 无模式 / 地址 / 脚本参数：[isHeadless] 选后台任务模式，其余配置走接口上的 setter，
 * 首屏地址由调用方建好之后自己 [WebviewContext.navigate]。
 *
 * 失败（环境起不来）抛 [IllegalStateException]，消息给人看；Android 侧漏注入 Context 是宿主编程错误，由 `lateinit` 自己炸，平台层不做兜底。
 */
expect suspend fun createWebviewContext(
  isHeadless: Boolean = false,
): WebviewContext
```

⚠️ expect 的默认值只写在 expect 上，两个 actual **不要**重复默认值（KMP 规则）。  
⚠️ 它要从 `internal` 转 **public** —— shared 的驱动要调它。`WebViewSurface` 保持  
`internal expect`（只有 platform commonMain 的壳用）。


actual 形态：

- jvm：`actual suspend fun createWebviewContext(isHeadless: Boolean): WebviewContext = Webview2Context(isHeadless)`
- android：`withContext(Dispatchers.Main) { AndroidWebviewContext(WebView(webviewApplicationContext), isHeadless) }`

## 5. 后台驱动（并入 JsEngine.kt，D2）

落点：`shared/src/commonMain/kotlin/soko/ekibun/acg/engine/JsEngine.kt`，`class JsEngine`  
之后追加 top-level 的 `WebViewTask` / `WebViewTaskResult` / `BACKGROUND_WEBVIEW_TIMEOUT_MS` /  
`loadBackgroundWebView`。`webviewAsync` 改为调同文件的 `loadBackgroundWebView`，其余不动  
（它已经在 `Dispatchers.IO` 的 `asyncReleasing` 里，天然是挂起上下文）。

```kotlin
/** 一次后台 WebView 任务。字段与 `webview(url, header, script, onInterceptRequest)` 一一对应。 */
data class WebViewTask(
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  /** 页面加载完成后注入并取回其返回值的脚本；null 表示不注入。 */
  val script: String? = null,
  /**
   * 请求拦截：命中即中止加载，被拦下的 url + headers 经 [WebViewTaskResult.Intercepted]
   * 回到脚本。**会在 WebView 的回调线程上被调用**，实现里不要假设自己在哪条线程。
   *
   * 硬约束：**不能从 JS 调用内部同步回调它** —— 脚本调 `webviewAsync` 时 `_binding`
   * 仍在 native `evaluate` 里没返回，此时反向调 JS 是对引擎的重入，本桥会让整轮
   * Promise 停摆（实测挂死不返回）。拦截回调必须来自平台侧
   * （`shouldInterceptRequest` / `WebResourceRequested`），那一刻脚本正挂在 `await` 上。
   */
  val onInterceptRequest:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)? = null,
) {
  /** 补齐后台加载默认要带的头，对齐 `http.js`：`header.referer = header.referer || url`。 */
  fun effectiveHeaders(): Map<String, String> =
    if (headers.keys.any { it.equals("Referer", ignoreCase = true) }) headers else headers + ("Referer" to url)
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

  data class Failed(
    val message: String,
  ) : WebViewTaskResult
}

/** 后台任务的默认超时。`http.js` 是「页面加载完再等 10s」，这里给整轮一个上限。 */
const val BACKGROUND_WEBVIEW_TIMEOUT_MS: Long = 30_000

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
    // ⚠️ 超时那条路进这里时协程已被 withTimeoutOrNull 取消，suspend 的 dispose 会立刻抛
    // CancellationException —— 不包 NonCancellable 就是「不报错、只失效」的上下文泄漏。
    withContext(NonCancellable) { runCatching { ctx.dispose() } }
  }
}
```

与现状的行为对齐（逐条，改完这些不许漂）：

| 行为                             | 现状                                                  | 重设计后                                          |
| ------------------------------ | --------------------------------------------------- | --------------------------------------------- |
| 脚本执行时机                         | 桌面 `navigationCompleted` / Android `onPageFinished` | 统一 `PageFinished` → `evaluate`                |
| 命中后不再跑脚本                       | Android `settled` 标志 / 桌面 `AtomicBoolean`           | 驱动的 `outcome.isCompleted` 判断                  |
| 拦截命中回传                         | 平台发 `RequestIntercepted` 事件                         | 驱动 lambda 内直接 complete（结果在命中那一刻就定）            |
| 命中回传的 url / headers            | 脚本 `onInterceptRequest` 返回的那个对象                     | **被拦下请求自己的** url + headers（脚本返回值只当布尔用，见 §5.1） |
| Android 命中是否真的断加载              | 否（照常放行，只定结果）                                        | 不变                                            |
| 桌面命中                           | native 204 + `Stop()`                               | 不变                                            |
| 30s 超时与三条失败消息文案                | —                                                   | 原样保留（测试与 JS 侧依赖）                              |
| `effectiveHeaders()` 补 Referer | —                                                   | 原样                                            |
| 销毁时机                           | fire-and-forget（jvm 丢 IO scope / Android post 回主线程） | suspend：在归属线程上发起后返回；超时路径必须 `NonCancellable`   |

### 5.1 JS 侧连带（D10 的代价，必须一并改）

- **删 `WebViewInterception` 与 `WebViewRequest`**（`soko.ekibun.web`）：拦截回调不再携带  
  请求对象，`WebViewRequest` 随之没有消费者。JsEngine 只 import `WebViewEvent` 与  
  `createWebviewContext`。
- **`invokeInterceptor` 改签名**：`invokeInterceptor(fn, url, headers, method, isForMainFrame): Boolean` ——  
  `fn.invoke(mapOf("url" to url, "headers" to headers, "method" to method, "isForMainFrame" to isForMainFrame))`，返回值按 **JS 真值**判定  
  （对象也是真值 ⇒ 老脚本 `return { url, headers }` 仍然命中，只是不再采用它改写后的  
  url / headers）；抛错 → `console("error", …)` + 放行。收尾仍用 `freeRecursive()` 还票。
- **`webviewAsync` 的 payload 形状不变**：  
  `mapOf("__webview_kind__" to "intercept", "value" to mapOf("url" to …, "headers" to …))`  
  ⇒ `init.js` 的 `ret.value` 与脚本侧 `fetch(req.url, { headers: req.headers })` 照旧可用。
- **`init.js` 只改用法注释**（:184-185 那两行示例）：回调按布尔写，  
  `(request) => !!request.headers.Range`。四参签名 `webview(url, header, script, onInterceptRequest)`  
  与 `__webview_kind__` 协议**不动**（AGENTS.md §4 记的契约不变）。
- ⚠️ **能力削减（要知情）**：脚本侧 `onInterceptRequest` 收到的对象从 5 字段  
  （`url` / `headers` / `method` / `isForMainFrame` / `isRedirect`）变成 **4 字段**  
  （`url` / `headers` / `method` / `isForMainFrame`）—— 丢掉的只有 Android 独有的 `isRedirect`。  
  `method` / `isForMainFrame` 两端都给得到，按 D10 末态留在回调里 ⇒  
  `WebViewHostTest.subResourcesAreVisibleToInterceptor` 的两条断言（主框架  
  `isForMainFrame == true`、子资源 `method == "GET"`）**原样保留**，不必改成按 URL 判。

## 6. 平台实现

### 6.1 jvmMain：`Webview2Context`

`platform/src/jvmMain/kotlin/soko/ekibun/web/WebviewContext.kt` → 改名  
`Webview2Context.kt`，类声明合并为：

```kotlin
class Webview2Context(
  isHeadless: Boolean = false,
) : Pointer(dispatcher = dispatcher), WebviewContext {
  init {
    // 后台页固定 1×1 视口：只要不是 0×0，页面就会真的加载 / 跑脚本。
    if (isHeadless) submit { setBounds(ptr, 1, 1) }
  }

  override var onEvent: ((WebViewEvent) -> Unit)? = null
  override var onInterceptor:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)? = null

  override suspend fun setUserAgent(ua: String) {
    withPtr { ptr -> setUserAgent(ptr, ua) }
  }

  override suspend fun setDevtoolsEnabled(enabled: Boolean) = Unit

  override suspend fun navigate(url: String, headers: Map<String, String>) {
    withPtr { ptr -> navigate(ptr, url, "GET", headers.toFlatArray()) }
  }

  override suspend fun evaluate(script: String): String? = /* 原实现，withPtr + 15s 超时 */

  override suspend fun reload() { withPtr { ptr -> reload(ptr) } }
  override suspend fun stop() { withPtr { ptr -> stop(ptr) } }
  override suspend fun goBack() { withPtr { ptr -> goBack(ptr) } }
  override suspend fun goForward() { withPtr { ptr -> goForward(ptr) } }
  override suspend fun openDevTools() { withPtr { ptr -> openDevTools(ptr) } }

  override suspend fun dispose() {
    // close() 必须落在归属线程上（markClosed + 投递 releaseImpl）；投递本身不阻塞。
    // 第二次进来时 withPtr 因 isClosed 抛、被 runCatching 吞掉 ⇒ 幂等。
    runCatching { withPtr { close() } }
  }

  @Keep
  private fun onEvent(event: String, data: String) {
    val e = translate(event, data) ?: return
    onEvent?.invoke(e)
  }

  @Keep
  private fun onWebResourceRequested(
    url: String, method: String, isForMainFrame: Boolean, headers: Array<String>,
  ): Boolean = onInterceptor?.invoke(url, headers.toHeaderMap(), method, isForMainFrame) == true
}
```

其余合并 / 删除清单：

- **吸收 `DesktopWebViewController`**（`WebView.jvm.kt:153-193`）：命令直通上下文；  
  它那个给 `loadUrl` 用的 `CoroutineScope` 不再需要（`navigate` 本身就是 suspend）。
- **吸收 `DesktopEventListener`**（`WebView.jvm.kt:199-230`）：native 字符串事件翻译成  
  `WebViewEvent` 转给 `onEvent`，翻译表原样。
- **删 `closeContext` / `disposeScope`**（`WebView.jvm.kt:240-244`）。
- **桌面私有能力不上接口、且保持非 suspend**：`attach` / `detach` / `fitToParent` /  
  `setBounds` / `focus` / cookie 读写（cookie 读写本来就是 suspend）。`WebViewSurface`  
  强转 `Webview2Context` 取用（`WebViewCanvas` 改持 `Webview2Context`）。
- 顺带消失的死代码：`WebView.jvm.kt:57` 与 `BackgroundWebView.jvm.kt:102` 的  
  `// ctx.start()`（类里从来没有 `start` 方法）。
- ⚠️ **JNI 符号随类名改**，见 §8。

### 6.2 androidMain：`AndroidWebviewContext`

`WebView.android.kt` 的 `AndroidWebViewController`（:102）改名 `AndroidWebviewContext`，  
两个 `configureXxx`（:165 / :249）合成一套：

- **构造期 `init` 块**（跑在主线程，`createWebviewContext` 的 actual 已  
  `withContext(Dispatchers.Main)`）：
  - 公共 settings：`javaScriptEnabled` / `domStorageEnabled` / `useWideViewPort` /  
    `loadWithOverviewMode` / `mixedContentMode` / `cacheMode`；
  - `headless` 分支：`setSupportMultipleWindows(true)` + `blockNetworkImage = true`  
    （原 `configureHeadless` 的值，原样）；
  - 原 `configureVisible` 里跟 D3 有关的两行（`setSupportMultipleWindows(config.allowNewWindow)` /  
    `javaScriptCanOpenWindowsAutomatically`）与 zoom 块删除 —— 系统默认即 false。
- **统一的 `WebViewClient` / `WebChromeClient`**（原可见页那份 + 后台页的两点差异）：
  - 事件回调统一发标准 `WebViewEvent` —— **后台页从此也发 `PageFinished`**，驱动才有的等；
  - `shouldOverrideUrlLoading`（非 http 拦掉）只在 headless 生效；
  - `shouldInterceptRequest` 调 `onInterceptor`（`url` / `headers` / `method` / `isForMainFrame` 四个入参都取自 `WebResourceRequest`，headers 仍经 `headersWithCookie` 补 cookie）；  
    命中**不再发事件**（驱动自己知道），返回值语义不变（Android 侧照常放行，  
    `super.shouldInterceptRequest`）。
- **接口实现**：两个回调是普通 `override var`（创建后一次性注入，无需同步），  
  函数一律 `withContext(Dispatchers.Main)`  
  （原来的 `onMainThread` 私有包装随之删掉 —— 挂起签名已经把线程切换收回来了）：
  ```kotlin
  override var onEvent: ((WebViewEvent) -> Unit)? = null
  override var onInterceptor:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)? = null
  ```


override suspend fun setUserAgent(ua: String) =  
withContext(Dispatchers.Main) { view.settings.userAgentString = ua }

override suspend fun setDevtoolsEnabled(enabled: Boolean) =  
withContext(Dispatchers.Main) { WebView.setWebContentsDebuggingEnabled(enabled) }

override suspend fun navigate(url: String, headers: Map<String, String>) =  
withContext(Dispatchers.Main) { view.loadUrl(url, headers) }

override suspend fun reload() = withContext(Dispatchers.Main) { view.reload() }  
override suspend fun stop() = withContext(Dispatchers.Main) { view.stopLoading() }  
override suspend fun goBack() = withContext(Dispatchers.Main) { if (view.canGoBack()) view.goBack() }  
override suspend fun goForward() = withContext(Dispatchers.Main) { if (view.canGoForward()) view.goForward() }  
override suspend fun openDevTools() = Unit   // Android 是 Chrome 远程调试，没有面板可开

override suspend fun dispose() = withContext(Dispatchers.Main) { <u>* 原 dispose 体：幂等标志 + headless clearCache \*</u> }

````
- `evaluate` 保持原实现（`CompletableDeferred` + `evaluateJavascript` 回调）。
- Context 槽与 [WebviewContext] 的实现一起住 `WebView.android.kt`，`BackgroundWebView.android.kt` **整个文件删除**；  
`headersWithCookie` 并入 `WebView.android.kt`（唯一使用者是合并后的 client）。

### 6.3 Android Context 供给（D5）

进程级的一个槽，就住在 `WebView.android.kt` 顶部 —— 后台任务跑在 JsEngine 里、可见页由
common 的壳建，两边都没有 Compose 上下文，拿不到 `LocalContext`：

```kotlin
/**
* 建 `android.webkit.WebView` 要的 Context。由宿主（androidApp 的 `MainActivity.onCreate`）
* 在 `setContent` 之前调 [setWebviewApplicationContext] 注入一次。
*
* 只留 `applicationContext`（不是 Activity）：整个进程活着它就在，不存在泄漏。
* 漏注入是宿主编程错误 —— `lateinit` 会在第一次建 WebView 时自己炸，平台层不做兜底。
*/
lateinit var webviewApplicationContext: Context
private set

/** 注入 [webviewApplicationContext]；只收 `applicationContext`。 */
fun setWebviewApplicationContext(context: Context) {
webviewApplicationContext = context.applicationContext
}
````

android actual 直接读 `webviewApplicationContext`（构造 `WebView(...)` 就在  
`withContext(Dispatchers.Main)` 块里）—— **没有任何缺 Context 的报错文案**。

`BackgroundWebView.android.kt` **整个文件删除**（连它原来那个  
`@Volatile internal var applicationContext` 一起去掉）。

**不加 `@Volatile`**：写在主线程，读也在主线程（同一个 `Dispatchers.Main` 块内）⇒ 读写同线程。

**不用 ContentProvider**：它只是把同一次赋值藏进一个隐式钩子，另外多要一份 library manifest  
与 `${applicationId}` 占位符。供给点确实必须是进程级的，但**在哪儿赋值**该由宿主直接说了算。

## 7. 可见壳与配置瘦身

`WebView.kt` 的 `WebView` composable（:383-435）创建流：

```kotlin
private val disposeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

LaunchedEffect(state) {
  val c =
    try {
      createWebviewContext().also { ctx ->
        ctx.onEvent = state::onWebViewEvent
        state.config.userAgent?.let { ctx.setUserAgent(it) }
        ctx.setDevtoolsEnabled(state.config.enableDevtools)
        state.controller = ctx
        // 初始导航由壳自己发：创建入口不带地址
        ctx.navigate(state.initialUrl)
      }
    } catch (t: Throwable) {
      state.fail(t.message ?: t.toString())
      null
    }
  controller = c
}

DisposableEffect(c) {
  onDispose {
    if (state.controller === c) state.controller = null
    // onDispose 没有协程上下文，且组合已退出 —— 丢到进程级 scope 上（dispose 自己幂等）。
    disposeScope.launch { runCatching { c.dispose() } }
  }
}
```

- **`WebViewState` 的转发方法全部改 suspend**：`loadUrl` / `reload` / `stopLoading` /  
  `navigateBack` / `navigateForward` / `openDevTools` / `evaluate`（`evaluate` 本来就是）。
- **`WebScreen` 的点击回调需要 scope**：`val scope = rememberCoroutineScope()`，  
  `onClick = { scope.launch { state.reload() } }` 等四处（后退 / 前进 / stop / reload）。
- `WebViewConfig` 删 `allowNewWindow` / `zoom`（D3）；`WebScreen.kt:57-58` 两行 + 注释删除；  
  类 KDoc 里「后台控制器不读它」一句改写。
- 两端 `WebViewSurface` actual 的强转目标改成各自的实现类名。

## 8. native：JNI 符号重命名（硬联动）

Kotlin 类名是 JNI 符号的 mangling 来源。`cxx/webview/webview.cpp` 里 **15 处**  
`Java_soko_ekibun_web_WebviewContext_` 前缀要改成 `Java_soko_ekibun_web_Webview2Context_`，  
不改就是运行期 `UnsatisfiedLinkError`（且是「不报错、只失效」类问题）：

| 行    | 符号                                                                            |
| ---- | ----------------------------------------------------------------------------- |
| 452  | prepareEnvironment                                                            |
| 462  | releaseEnvironment                                                            |
| 970  | initContext                                                                   |
| 985  | releaseContext                                                                |
| 994  | navigate                                                                      |
| 1026 | executeScript                                                                 |
| 1059 | getCookie                                                                     |
| 1090 | setCookie                                                                     |
| 1140 | setUserAgent                                                                  |
| 1160 | attach                                                                        |
| 1177 | detach                                                                        |
| 1185 | focus                                                                         |
| 1194 | fitToParent                                                                   |
| 1201 | setBounds                                                                     |
| 1213 | `JNI_WEBVIEW_METHOD` 宏（展开出 reload / stop / goBack / goForward / openDevTools） |

C++ 侧的类名 `WebviewContext`（native 实现）**不动**，只改 JNI 入口符号。companion 里  
`initContext(ctx: WebviewContext)` 的形参类型随之改成 `Webview2Context`（descriptor 同步）。  
改完 `./gradlew :desktopApp:buildJni`，复核 dll 落位（`cxx/build/bin/webview.dll` 与  
`desktopApp/build/resources/main/webview.dll` 字节数一致）。

⚠️ **中间态不可运行**：Kotlin 与 native 必须同轮改完再构建。  
⚠️ 本仓另有 agent 在改 webview 相关文件（`git status` 有未提交改动），动手前先对表。

## 9. 测试迁移（D7）

| 测试                                            | 去向                                                                                                    | 改动                                                                                                                                                                                                                               |
| --------------------------------------------- | ----------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WebViewHostTest` 的 4 个任务级用例 + 体检             | **`shared/src/jvmTest/kotlin/soko/ekibun/acg/engine/WebViewHostTest.kt`**（包 `soko.ekibun.acg.engine`） | 驱动同包，`webviewAsync` 之外的 import 只留 `soko.ekibun.web.WebViewEvent`；其余原样                                                                                                                                                            |
| `subResourcesAreVisibleToInterceptor` 的断言     | 同上                                                                                                    | 回调是 `(url, headers, method, isForMainFrame)` ⇒ 两条断言**原样保留**（主框架 `isForMainFrame == true`、子资源 `method == "GET"`）；`headers.isNotEmpty()` 断言保留（证明头还是真的）                                                                             |
| `visibleViewEvaluateReturnsScriptResult`      | 并入 **`platform/jvmTest/.../web/Webview2Test.kt`**                                                     | `WebviewContext()` → `Webview2Context()`；`ctx.listener = …` → `ctx.onEvent = { if (it is WebViewEvent.PageFinished) loaded.complete(Unit) }`；`setBounds` / `navigate` / `executeScript` 不变                                       |
| `platform/jvmTest/.../web/WebViewHostTest.kt` | **删除**                                                                                                | 内容分流到上两行                                                                                                                                                                                                                         |
| `WebViewCookieManagerTest`                    | 留 platform                                                                                            | `probeHost` 改为直构 `Webview2Context`（建 → `setBounds(800, 600)` → `onEvent = …` → navigate `about:blank` → 等 `PageFinished` → `executeScript("1 + 1")` → close），不再依赖 shared 的驱动；`prepareEnvironment` / `releaseEnvironment` / 类引用改名 |
| `Webview2Test`                                | 留 platform                                                                                            | 类引用改名 + `listener` → `onEvent`                                                                                                                                                                                                   |
| `JsEngineDispatchTest`                        | 留 shared                                                                                              | 驱动同包，删 `soko.ekibun.web.*` import                                                                                                                                                                                                |

## 10. 文档连带

- `docs/architecture.md` §4：「后台控制器改成 `by` 委托（不再强转）」一句作废  
  （两类合并后没有委托对象）；投前台的落地缺口描述随之简化。
- `docs/research-2026-10-02.md`：§159「App.kt 已全局挂 `BackgroundWebViewHost()`」  
  前提消失；§164「后台控制器是 `by` 委托对象会 ClassCastException」的缺口不存在了。
- `.agents/skills/quickjs-ownership/references/ability-bridge.md` §92 / §130 提到  
  `WebViewHostTest` / `loadBackgroundWebView` —— 名字没变，补一句「已随驱动搬到  
  shared 的 engine 包」；§里若引用 `WebViewRequest` / `WebViewInterception`，改成  
  `(url, headers) -> Boolean` 的口径。
- `AGENTS.md` §4 的 JS 契约 `await webview(url, header, script, onInterceptRequest)`  
  四参签名**不动**；但 §2 提到的「后台 WebView」相关说明里若写了请求对象的 5 个字段，  
  要改成 2 个。
- `TODO.md` E8 落地顺序：第 6 条前插一步「WebView 接口重设计（本文）」（已改）。

## 11. 实现顺序与验证

顺序（native 与 Kotlin 同轮，中间态不可运行）：

1. `webview.cpp` JNI 符号重命名（§8 表）→ `./gradlew :desktopApp:buildJni` → 复核 dll 落位
2. platform commonMain：接口 + `WebViewEvent` 瘦身 + 删 `WebViewRequest` /  
   `WebViewInterception` + `WebViewConfig` 瘦身 + expect + 壳
3. platform jvmMain：`Webview2Context` 合并、actuals、删 `BackgroundWebView.jvm.kt`、  
   `WebViewCookieStorage` 由 class 改为 object 单例（与 Android 侧 `AndroidWebViewCookieStorage` 口径对齐；HTTP 层与 WebView 页面共享同一惰性缓存的 `Webview2Context`）
4. platform androidMain：`AndroidWebviewContext` 合并、`lateinit` 的 Context 槽 + androidApp 的 `MainActivity.onCreate` 注入
5. shared：`JsEngine.kt` 并入驱动 + `invokeInterceptor` 改布尔口径；`App.kt` 删 host 调用与注释
6. `shared/.../files/js/init.js`：改 §5.1 那两行用法注释
7. desktopApp：`main.kt` 不再显式调用 `releaseEnvironment` / `closeWebViewCookieStorage` —— 进程级收尾交给 `Webview2Context` 伴生 `init` 注册的 JVM shutdown hook（`releaseEnvironment()` 的唯一调用点就是这个 hook；测试进程没有 `main.kt` 正靠它收场）
8. 测试迁移（§9）
9. 文档连带（§10）

验证（按 AGENTS.md §5 只跑定点；提交前再跑四闸门 + 全量 + lint）：

- `./gradlew :shared:jvmTest --tests "soko.ekibun.acg.engine.WebViewHostTest" --tests "soko.ekibun.acg.engine.JsEngineDispatchTest" --console=plain`
- `./gradlew :platform:jvmTest --tests "soko.ekibun.web.Webview2Test" --tests "soko.ekibun.web.WebViewCookieManagerTest" --console=plain`
- 结果读 `shared/build/test-results/jvmTest/TEST-*.xml` 与 `platform/build/test-results/jvmTest/TEST-*.xml`
- 桌面可见路径（attach / fitToParent / 假进度插值）jvmTest 覆盖不到，需人工起桌面端  
  过一遍 WebScreen：加载、后退 / 前进 / 刷新、stop、F12（`enableDevtools = true`）

## 12. 证据索引（写作时点 2026-10-13，实现时可能漂移）

- 接口与八参工厂：`platform/src/commonMain/kotlin/soko/ekibun/web/WebView.kt:284-339`
- 后台骨架与请求类型：`platform/src/commonMain/kotlin/soko/ekibun/web/BackgroundWebView.kt:37-180`
- 桌面：`WebView.jvm.kt:41-69`（工厂）/ `:153-193`（DesktopWebViewController）/  
  `BackgroundWebView.jvm.kt:32-119`（headless 创建 + `by delegate`）
- Android：`WebView.android.kt:42-73`（工厂）/ `:102`（类）/ `:165` 与 `:249`（两个 configure）
- Android Context 供给：`platform/src/androidMain/kotlin/soko/ekibun/web/WebView.android.kt` 顶部的 `webviewApplicationContext` 槽 + `androidApp/src/main/kotlin/soko/ekibun/acg/MainActivity.kt` 的注入调用
- 消费方：`App.kt:19/40`、`WebScreen.kt:54-59`、`JsEngine.kt:223-316`、`main.kt:46`
- JS 侧：`shared/src/commonMain/composeResources/files/js/init.js:167-214`
- 测试：`platform/src/jvmTest/kotlin/soko/ekibun/web/{WebViewHostTest,WebViewCookieManagerTest,Webview2Test}.kt`、`shared/src/jvmTest/kotlin/soko/ekibun/acg/engine/JsEngineDispatchTest.kt`
- native 符号：`cxx/webview/webview.cpp` §8 表中的 15 处
