package soko.ekibun.acg.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 自研 WebView2 原生宿主（`cxx/webview/webview.cpp`）的 JNI 面。
 *
 * 两种用法共用**同一个 environment**（同一份 user data folder → 同一个浏览器进程 →
 * **同一套 cookie**）：
 *
 * - **可见视图**：[nativeCreateView] 建一个隐藏的顶层窗口，再由 [attachView] 经 JAWT
 *   从 AWT 组件（`SwingPanel` 里的 Canvas）取到父 HWND，`SetParent` 成子窗口挂进去，
 *   尺寸由 [fitViewToParent] 按父窗口客户区对齐 —— 不依赖任何 UI 框架的原生互操作层。
 * - **后台任务**：[nativeRun] 建一个隐藏窗口，全量拦截请求并注入脚本取结果。
 *
 * 所有 native 函数都会在 WebView 线程上执行，JVM 侧只管发号施令。
 */
public object NativeWebView {
  // ---- 和 webview.cpp 的 ViewEvent 一一对应 ----
  public const val EVENT_READY: Int = 1
  public const val EVENT_LOADING: Int = 2
  public const val EVENT_URL: Int = 3
  public const val EVENT_TITLE: Int = 4
  public const val EVENT_HISTORY: Int = 5
  public const val EVENT_FAILED: Int = 6
  public const val EVENT_DESTROYED: Int = 7

  // ---- 和 webview.cpp 的 nativeViewAction 编号一一对应 ----
  public const val ACTION_BACK: Int = 0
  public const val ACTION_FORWARD: Int = 1
  public const val ACTION_RELOAD: Int = 2
  public const val ACTION_STOP: Int = 3
  public const val ACTION_DEVTOOLS: Int = 4

  /**
   * user data folder。
   *
   * **可见页和后台页必须共用这一份** —— WebView2 的 cookie 存储是按
   * user data folder 分的，目录不同就是两套登录态。
   *
   * 位置还必须**稳定**：以前 `LOCALAPPDATA` 取不到就直接退到 `java.io.tmpdir`，
   * 而在 MSYS2 / Gradle 起的 JVM 里那可能是 `C:\msys64\tmp` —— 每跑一次都是新的
   * cookie 罐子，「可见页与后台页共享登录态」这件事在测试里根本验不出来。
   * 所以改成逐级往下找可写的目录，tmp 只当最后兜底（并且会吭一声）。
   */
  public val userDataDir: File by lazy {
    val home = System.getProperty("user.home").orEmpty()
    val base =
      listOfNotNull(
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() },
        home.takeIf { it.isNotBlank() }?.let { File(it, "AppData/Local").path },
        home.takeIf { it.isNotBlank() },
      ).map(::File).firstOrNull { it.isDirectory && it.canWrite() }
        ?: File(System.getProperty("java.io.tmpdir")).also {
          System.err.println(
            "[acg-webview] LOCALAPPDATA 与 user.home 都不可用，user data folder 退到 " +
              "${it.path} —— cookie / 登录态不会跨进程保留",
          )
        }

    File(base, "ACG/webview2").also { dir ->
      if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
        System.err.println("[acg-webview] 建不出 user data folder：${dir.path}")
      }
    }
  }

  internal val callbacks = Callbacks()

  private val nextToken = AtomicLong(1)

  /**
   * 还活着的视图句柄：可见视图（[createView]）与后台任务（[run]）共用。
   *
   * [destroyView] 与 [cancel] 都先从这里摘句柄，摘不到就直接返回 —— 重复下发
   * （组合层 `onDispose`、测试、取消路径都可能各来一次）时不会重复销毁。
   * native 侧的销毁本身幂等，但句柄在窗口销毁后可能被系统回收成别的窗口，
   * 所以这里拦一道。
   */
  private val liveViews: MutableSet<Long> = ConcurrentHashMap.newKeySet()

  /**
   * 可见视图 `evaluate` 的等待者：`token -> CompletableDeferred`。
   *
   * 只放**正在等**的那些 —— 结果一到就 [remove] 并 complete，超时也清掉，
   * 所以不会攒下对已销毁视图的引用。
   */
  private val scriptResults = ConcurrentHashMap<Long, CompletableDeferred<String?>>()

  private var startResult: Result<Unit>? = null

  /** 申请一个后台任务 token（必须在 [nativeRun] 之前拿到，结果回调按它派发）。 */
  internal fun newToken(): Long = nextToken.getAndIncrement()

  /**
   * 确保 WebView 线程与环境起来了。幂等；失败原因会缓存下来。
   *
   * 会阻塞（环境创建是异步的），所以别在组合线程上直接调。
   */
  @Synchronized
  internal fun ensureStarted(): Result<Unit> {
    startResult?.let { return it }
    val result =
      runCatching {
        loadNativeLibraries()
        registerShutdownHook()
        val error = nativeStart(callbacks, userDataDir.absolutePath, START_TIMEOUT_MS)
        if (error != null) throw IllegalStateException(error)
      }.recoverCatching { cause ->
        // 起不来时把「系统到底有没有 WebView2 运行时」一起报出来，省得去猜。
        val version = runtimeVersion()
        val hint =
          if (version == null) {
            "系统里没有 WebView2 运行时（Win11 自带，Win10 需要装 Evergreen Runtime）"
          } else {
            "已检测到 WebView2 运行时 $version"
          }
        throw IllegalStateException("${cause.message ?: cause} —— $hint", cause)
      }
    startResult = result
    return result
  }

  /** 系统装的 WebView2 运行时版本；没装或还没解出 DLL 就是 null。 */
  internal fun runtimeVersion(): String? =
    runCatching { nativeRuntimeVersion() }.getOrNull()?.takeIf { it.isNotBlank() }

  /**
   * 探针：在**另一条** STA + 消息泵的线程上，用当前这个 environment 建一个控制器，
   * 返回创建回调的 HRESULT（0 = S_OK）。
   *
   * 只为回答一个架构问题：可见视图迁到 AWT 线程、后台视图留在 WebView 线程，
   * 两者还得共用同一个 environment（否则 cookie 就是两套），这样到底行不行。
   * 结论出来之后这个入口连同 native 侧那段可以一起删掉。
   */
  internal fun probeCrossThreadController(): Int = nativeProbeCrossThreadController()

  /**
   * 进程退出时收摊。
   *
   * WebView 线程是个**可 join 的 `std::thread`**（native 侧静态变量），它到退出时
   * 还没 join/detach 的话会走 `std::terminate`；而且这个线程一直活着，不关就把
   * 进程吊在那儿。`nativeStop` 会 `WM_CLOSE` 掉消息泵再 join。
   */
  @Synchronized
  private fun registerShutdownHook() {
    if (shutdownHookRegistered) return
    shutdownHookRegistered = true
    Runtime.getRuntime().addShutdownHook(Thread({ runCatching { nativeStop() } }, "acg-webview-stop"))
  }

  private var shutdownHookRegistered = false

  /**
   * 把 webview.dll 和 WebView2Loader.dll 解到**同一个目录**再加载。
   *
   * 不能直接用 `jniLoadLibrary`：它把每个 .dll 各解成 `%TEMP%` 里的独立随机文件名
   * （`native_XXXX_webview.dll`），而 webview.dll 是按「自己所在目录」去找
   * WebView2Loader.dll 的 —— 两个文件必须挨着。
   *
   * 解压目录**按库内容的哈希分桶**，理由见 [nativeDir]。
   */
  private fun loadNativeLibraries() {
    val loader = NativeWebView::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
    val payload =
      NATIVE_FILES.associateWith { name ->
        loader.getResourceAsStream(name)?.use { it.readBytes() }
          ?: throw IllegalStateException("classpath 里找不到 $name（检查 cxx/build/bin 是否打进了资源）")
      }
    val dir = nativeDir(payload.values)
    for (name in NATIVE_FILES) {
      val target = File(dir, name)
      val bytes = payload.getValue(name)
      if (target.isFile && target.length() == bytes.size.toLong()) continue
      // 写不进去就让它抛：静默失败会让人以为自己跑的是新编的库。
      FileOutputStream(target).use { it.write(bytes) }
    }
    System.load(File(dir, NATIVE_FILES[0]).absolutePath)
  }

  /**
   * 解压目录：`<base>/<内容哈希>`。
   *
   * **不能固定一个路径**：DLL 一旦被 `System.load` 就锁住、覆盖写会失败，而 Gradle
   * 会复用测试 worker，上一个进程加载过的库还占着文件 —— 固定路径下「改了 C++、
   * 重编、重跑」拿到的还是旧库（实测踩过：日志里的新代码根本没生效）。
   * 按内容哈希分目录后，新库天然落在新目录里，旧目录锁着也不影响。
   *
   * 顺手把*别的*哈希目录清掉（只清名字长这样的，别的一律不碰）；清不掉的多半是
   * 还被别的进程加载着，忽略了就行。
   */
  private fun nativeDir(payloads: Collection<ByteArray>): File {
    val digest = MessageDigest.getInstance("SHA-256")
    payloads.forEach(digest::update)
    val tag = digest.digest().take(6).joinToString("") { "%02x".format(it) }

    val base = File(userDataDir.parentFile ?: userDataDir, "native")
    val dir = File(base, tag)
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
      throw IllegalStateException("无法创建原生库解压目录：$dir")
    }
    base.listFiles()?.forEach { sibling ->
      if (sibling.name != tag && sibling.isDirectory && HASH_DIR.matches(sibling.name)) {
        runCatching { sibling.deleteRecursively() }
      }
    }
    return dir
  }

  // ---------------------------------------------------------------------
  // 可见视图
  // ---------------------------------------------------------------------

  /**
   * 建一个可见视图，返回它的窗口句柄；失败返回 0。
   *
   * 此时窗口是**隐藏的顶层窗口**，什么都没显示 —— 还要 [attachView] 把它挂到
   * AWT 组件上才会出现。
   */
  internal fun createView(
    parentHwnd: Long,
    url: String?,
    userAgent: String?,
    enableDevtools: Boolean,
    zoom: Double,
  ): Long {
    val handle = nativeCreateView(parentHwnd, userAgent, url, enableDevtools, zoom)
    if (handle != 0L) liveViews.add(handle)
    return handle
  }

  /**
   * 把视图挂到 AWT 组件上（`SwingPanel { Canvas() }` 里那个 Canvas）。
   *
   * [component] **必须已经 `isDisplayable`** —— 没有 peer 就没有 HWND，native 侧
   * 取不到父窗口，attach 会被静默跳过。组件尺寸还没定也没关系，attach 之后
   * native 会按当时的客户区贴一次，之后由 [fitViewToParent] 跟随。
   *
   * 幂等：native 侧重复 attach 只是再 SetParent 一次同样的父窗口。
   */
  internal fun attachView(
    handle: Long,
    component: Component,
  ) = nativeViewAttach(handle, component)

  /**
   * AWT 组件尺寸变化时调 —— 让 WebView2 窗口和控制器都重新贴合组件客户区。
   *
   * 尺寸**只在 native 侧算**（`GetClientRect(父窗口)`），JVM 侧不传宽高：DPI 缩放
   * 下 AWT 报的逻辑尺寸和 Win32 的物理像素本来就对不齐，两边各算一次只会互相打架。
   */
  internal fun fitViewToParent(handle: Long) = nativeViewFitToParent(handle)

  /**
   * 只更新尺寸（兼容入口；正常路径是 [fitViewToParent]）。
   *
   * 还没 attach 时这份尺寸会当缓存用，attach 之后一律以父窗口客户区为准。
   */
  internal fun setViewBounds(
    handle: Long,
    width: Int,
    height: Int,
  ) = nativeSetViewBounds(handle, width, height)

  internal fun setViewVisible(
    handle: Long,
    visible: Boolean,
  ) = nativeSetViewVisible(handle, visible)

  internal fun destroyView(handle: Long) {
    if (handle == 0L || !liveViews.remove(handle)) return
    callbacks.removeViewListener(handle)
    nativeDestroyView(handle)
  }

  internal fun loadUrl(
    handle: Long,
    url: String,
    headers: Map<String, String> = emptyMap(),
  ) = nativeViewLoadUrl(handle, url, headers.toFlatArray())

  internal fun loadHtml(
    handle: Long,
    html: String,
  ) = nativeViewLoadHtml(handle, html)

  internal fun viewAction(
    handle: Long,
    action: Int,
  ) = nativeViewAction(handle, action)

  internal fun focusView(handle: Long) = nativeViewFocus(handle)

  /**
   * 在**可见视图**当前页面里执行脚本，挂起直到结果回来。
   *
   * 和后台任务用的是同一个 `ExecuteScript`（`cxx/webview/webview.cpp` 的
   * `ScriptHandler`），只是结果的去向不同：后台那支走 `nativeFinished`（收件人是
   * [BackgroundSink]），可见视图这支走 `nativeScriptResult` —— **这条管道以前没有
   * 收件人**（那个回调曾经是空的），所以这里就是给每个 token 挂一个等待者。
   *
   * 失败（视图没了、引擎没起、脚本抛错）时 native 回的是 `null`，跟「脚本没有
   * 返回值」没法区分 —— 调用方要分清的话自己去判 `failure` / 日志。
   *
   * @return 结果的 JSON 字符串；失败或超时返回 `null`。
   */
  internal suspend fun evaluateScript(
    handle: Long,
    script: String,
    timeoutMs: Long = SCRIPT_TIMEOUT_MS,
  ): String? {
    val token = newToken()
    val deferred = CompletableDeferred<String?>()
    scriptResults[token] = deferred
    try {
      nativeViewEvaluate(handle, script, token)
      return withTimeoutOrNull(timeoutMs) { deferred.await() }
    } finally {
      // 超时也要清掉：不然这次的 token 永远占着一张表，结果晚到也无处可送。
      scriptResults.remove(token)
    }
  }

  /** 在视图里跑一段脚本；结果按 [token] 回给 [Callbacks.nativeScriptResult]。 */
  internal fun evaluate(
    handle: Long,
    script: String,
    token: Long,
  ) = nativeViewEvaluate(handle, script, token)

  /** 注册一个视图事件监听；注册前的早期事件会被补发。 */
  internal fun addViewListener(
    handle: Long,
    listener: (what: Int, a: String?, b: String?) -> Unit,
  ) = callbacks.addViewListener(handle, listener)

  internal fun removeViewListener(handle: Long) = callbacks.removeViewListener(handle)

  // ---------------------------------------------------------------------
  // 后台任务
  // ---------------------------------------------------------------------

  /**
   * 跑一个后台任务。
   *
   * @return 视图句柄（>0）；-1 表示立不起来（原因会走 [Callbacks.nativeFailed]）。
   */
  internal fun run(
    url: String,
    headers: Map<String, String>,
    script: String?,
    token: Long,
  ): Long {
    val handle = nativeRun(url, headers.toFlatArray(), script, token)
    if (handle > 0L) liveViews.add(handle)
    return handle
  }

  /** 中止后台加载。和 [destroyView] 一样只认自己的句柄，重复调用无副作用。 */
  internal fun cancel(handle: Long) {
    if (handle <= 0L || !liveViews.remove(handle)) return
    nativeCancel(handle)
  }

  internal fun registerBackground(
    token: Long,
    sink: BackgroundSink,
  ) = callbacks.addBackground(token, sink)

  internal fun unregisterBackground(token: Long) = callbacks.removeBackground(token)

  // ---------------------------------------------------------------------

  private fun Map<String, String>.toFlatArray(): Array<String> {
    if (isEmpty()) return EMPTY_STRINGS
    val out = ArrayList<String>(size * 2)
    for ((k, v) in this) {
      out += k
      out += v
    }
    return out.toTypedArray()
  }

  private const val START_TIMEOUT_MS = 30_000

  /**
   * 一次 `evaluate` 的上限。
   *
   * 卡住的来源不是脚本本身慢，而是「视图已经没了 / 控制器没建起来」—— 那时
   * native 侧要么回 null，要么**什么都不回**（`postTask` 到已经拆掉的视图上会被
   * 直接丢掉）。给个上限把「永远挂着」变成一次明确的 null。
   */
  private const val SCRIPT_TIMEOUT_MS = 15_000L
  private val NATIVE_FILES = arrayOf("webview.dll", "WebView2Loader.dll")
  private val EMPTY_STRINGS = emptyArray<String>()

  /** 我们自己生成的解压目录名（12 位十六进制），清理时只认这个形状。 */
  private val HASH_DIR = Regex("^[0-9a-f]{12}$")

  /** 后台任务的一次结果收件人。 */
  internal interface BackgroundSink {
    /** 返回 true 表示已命中并收工。 */
    fun onIntercept(
      url: String,
      method: String,
      isForMainFrame: Boolean,
      headers: Map<String, String>,
    ): Boolean

    fun onFinished(json: String?)

    fun onFailed(message: String)
  }

  /**
   * native 回调的落点。
   *
   * **下面那几个 `nativeXxx` 方法的名字和签名是 native 侧按字符串查的，不能改。**
   * （Kotlin 会给 `internal` 成员的名字加模块后缀，所以它们必须是 public。）
   */
  public class Callbacks {
    private val background = ConcurrentHashMap<Long, BackgroundSink>()
    private val listeners = ConcurrentHashMap<Long, (Int, String?, String?) -> Unit>()

    /** 监听器注册之前到的事件先攒着，注册时补发 —— 建视图和注册之间有个很窄的窗口。 */
    private val pending = ConcurrentHashMap<Long, MutableList<Triple<Int, String?, String?>>>()

    internal fun addBackground(
      token: Long,
      sink: BackgroundSink,
    ) {
      background[token] = sink
    }

    internal fun removeBackground(token: Long) {
      background.remove(token)
    }

    internal fun addViewListener(
      handle: Long,
      listener: (Int, String?, String?) -> Unit,
    ) {
      listeners[handle] = listener
      pending.remove(handle)?.forEach { (what, a, b) -> listener(what, a, b) }
    }

    internal fun removeViewListener(handle: Long) {
      listeners.remove(handle)
      pending.remove(handle)
    }

    // ---- 以下由 native 调用 ----

    public fun nativeInterceptRequest(
      token: Long,
      url: String,
      method: String,
      isForMainFrame: Boolean,
      headers: Array<String>,
    ): Boolean {
      val sink = background[token] ?: return false
      return sink.onIntercept(url, method, isForMainFrame, headers.toHeaderMap())
    }

    public fun nativeFinished(
      token: Long,
      json: String?,
    ) {
      background[token]?.onFinished(json)
    }

    public fun nativeFailed(
      token: Long,
      message: String,
    ) {
      background[token]?.onFailed(message)
    }

    public fun nativeViewEvent(
      handle: Long,
      what: Int,
      a: String?,
      b: String?,
    ) {
      val listener = listeners[handle]
      if (listener == null) {
        pending.getOrPut(handle) { mutableListOf() }.add(Triple(what, a, b))
        return
      }
      listener(what, a, b)
    }

    /** 可见视图的 `evaluate` 结果：按 token 找到 [evaluateScript] 挂的等待者。 */
    public fun nativeScriptResult(
      handle: Long,
      token: Long,
      json: String?,
    ) {
      scriptResults.remove(token)?.complete(json)
    }

    private fun Array<String>.toHeaderMap(): Map<String, String> {
      if (isEmpty()) return emptyMap()
      val out = LinkedHashMap<String, String>(size / 2 + 1)
      var i = 0
      while (i + 1 < size) {
        out[this[i]] = this[i + 1]
        i += 2
      }
      return out
    }
  }

  // ---- native 方法（JNI 导出）----
  private external fun nativeStart(
    callback: Any,
    userDataDir: String,
    timeoutMs: Int,
  ): String?

  private external fun nativeStop()

  private external fun nativeRuntimeVersion(): String?

  private external fun nativeProbeCrossThreadController(): Int

  private external fun nativeCreateView(
    parentHwnd: Long,
    userAgent: String?,
    url: String?,
    enableDevtools: Boolean,
    zoom: Double,
  ): Long

  private external fun nativeDestroyView(handle: Long)

  private external fun nativeViewAttach(
    handle: Long,
    component: Component,
  )

  private external fun nativeViewFitToParent(handle: Long)

  private external fun nativeSetViewBounds(
    handle: Long,
    width: Int,
    height: Int,
  )

  private external fun nativeSetViewVisible(
    handle: Long,
    visible: Boolean,
  )

  private external fun nativeViewLoadUrl(
    handle: Long,
    url: String,
    headers: Array<String>,
  )

  private external fun nativeViewLoadHtml(
    handle: Long,
    html: String,
  )

  private external fun nativeViewAction(
    handle: Long,
    action: Int,
  )

  private external fun nativeViewEvaluate(
    handle: Long,
    script: String,
    token: Long,
  )

  private external fun nativeViewFocus(handle: Long)

  private external fun nativeRun(
    url: String,
    headers: Array<String>,
    script: String?,
    token: Long,
  ): Long

  private external fun nativeCancel(handle: Long)
}
