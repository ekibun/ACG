package soko.ekibun.web

import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.loadLibrary
import java.awt.Component
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 一个 WebView2 上下文 —— **可见视图与后台页共用同一个类型**，差别只在是否
 * [attach] 到 AWT 组件上、以及视口给多大（[isHeadless] 固定 1×1）。
 *
 * 所有 `external` 调用都投到 [dispatcher] 上跑：宿主窗口、消息泵线程、以及
 * `ICoreWebView2Controller` 都归属那条线程，别的线程只能投递不能直碰 ——
 * 接口上的命令因此都是挂起的，落在归属线程上执行完才返回。
 *
 * native 侧 environment 是**进程内共享**的（同一 user data folder 上不允许并存
 * 多个），每个上下文只在这个 environment 下开自己的窗口与 controller；
 * cookie 因此天然在可见页与后台页之间共享。详见 `cxx/webview/webview.cmake`。
 *
 * 挂载与尺寸跟随（[attach] / [fitToParent] / [setBounds]）是**桌面私有**
 * 能力，不上 [WebviewContext] 接口、也不挂起 —— 调用点全在没有协程上下文的 AWT
 * 回调里，由 [WebViewSurface] 直接强转成本类型取用。
 */
class Webview2Context(
  isHeadless: Boolean = false,
) : Pointer(dispatcher = dispatcher),
  WebviewContext {
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

  /** 桌面端的 devtools 靠 [openDevTools] 直接开，没有可开关的全局位。 */
  override suspend fun setDevtoolsEnabled(enabled: Boolean) = Unit

  override suspend fun navigate(
    url: String,
    headers: Map<String, String>,
  ) {
    withPtr { ptr -> navigate(ptr, url, "GET", headers.toFlatArray()) }
  }

  override suspend fun evaluate(script: String): String? =
    withPtr { ptr ->
      val ret = CompletableDeferred<String>()
      executeScript(
        ptr,
        script,
        object : ScriptCallback() {
          override fun onResult(
            ok: Boolean,
            result: String,
          ) {
            if (!ret.isActive) return
            if (ok) ret.complete(result) else ret.completeExceptionally(RuntimeException(result))
          }
        },
      )
      withTimeoutOrNull(SCRIPT_TIMEOUT_MS.milliseconds) { ret.await() }
    }

  override suspend fun reload() {
    withPtr { ptr -> reload(ptr) }
  }

  override suspend fun stop() {
    withPtr { ptr -> stop(ptr) }
  }

  override suspend fun goBack() {
    withPtr { ptr -> goBack(ptr) }
  }

  override suspend fun goForward() {
    withPtr { ptr -> goForward(ptr) }
  }

  override suspend fun openDevTools() {
    withPtr { ptr -> openDevTools(ptr) }
  }

  /**
   * 读 url 下的 cookie，返回 native 摊平的
   * `"n1=v1; n2=v2"`（没有则空串）。
   */
  suspend fun getCookie(url: String): String =
    withPtr { ptr ->
      val result = CompletableDeferred<String>()
      getCookie(
        ptr,
        url,
        object : CookieCallback() {
          override fun onResult(cookies: String) {
            if (result.isActive) result.complete(cookies)
          }
        },
      )
      return@withPtr withTimeoutOrNull(COOKIE_TIMEOUT_MS.milliseconds) { result.await() } ?: ""
    }

  /**
   * 写一个 cookie 到本上下文自己的 manager。字段口径同 [setCookie]。
   */
  suspend fun setCookie(
    name: String,
    value: String,
    domain: String,
    path: String,
    expiresEpochSeconds: Double,
    isSecure: Boolean,
    isHttpOnly: Boolean,
    sameSite: Int,
  ) = withPtr { ptr ->
    setCookie(
      ptr,
      name,
      value,
      domain,
      path,
      expiresEpochSeconds,
      isSecure,
      isHttpOnly,
      sameSite,
    )
  }

  suspend fun clearAllCookies() = withPtr { ptr -> clearAllCookies(ptr) }

  // ---- 桌面私有能力：不上接口、不挂起（调用点在 AWT 回调里）----

  fun attach(view: Component) {
    submit { attach(ptr, view) }
  }

  fun detach() {
    submit { detach(ptr) }
  }

  fun fitToParent() {
    submit { fitToParent(ptr) }
  }

  fun setBounds(
    width: Int,
    height: Int,
  ) {
    submit { setBounds(ptr, width, height) }
  }

  // ---- native 回调入口：由宿主线程直接调进来，故 @Keep ----

  @Keep
  private fun onEvent(
    event: String,
    data: String,
  ) {
    val e =
      when (event) {
        "navigationStarting" -> WebViewEvent.PageStarted
        "urlChanged" -> data.takeIf { it.isNotBlank() }?.let { WebViewEvent.UrlChanged(it) }
        "documentLoaded" -> data.takeIf { it.isNotBlank() }?.let { WebViewEvent.DocumentLoaded(it) }
        "historyChanged" -> {
          // data 是 "canGoBack canGoForward"，两个都是 "1" / "0"。
          val parts = data.split(' ')
          WebViewEvent.HistoryChanged(
            canGoBack = parts.getOrNull(0) == "1",
            canGoForward = parts.getOrNull(1) == "1",
          )
        }
        "navigationCompleted" -> WebViewEvent.PageFinished
        "navigationFailed" -> {
          val detail = data.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
          WebViewEvent.LoadFailed(detail)
        }
        "documentTitleChanged" -> data.takeIf { it.isNotBlank() }?.let { WebViewEvent.TitleChanged(it) }
        "processFailed" -> {
          val kind =
            data.substringBefore(' ').toIntOrNull()
          if (kind in setOf(2, 4, 5, 6)) {
            null
          } else {
            WebViewEvent.LoadFailed(data)
          }
        }
        else -> null
      } ?: return
    onEvent?.invoke(e)
  }

  @Keep
  private fun onWebResourceRequested(
    url: String,
    method: String,
    isForMainFrame: Boolean,
    headers: Array<String>,
  ): Boolean = onInterceptor?.invoke(url, headers.toHeaderMap(), method, isForMainFrame) == true

  /** `ExecuteScript` 的结果收件人 —— 由 native 线程直接调进来，故 [Keep]。 */
  abstract class ScriptCallback {
    @Keep
    abstract fun onResult(
      ok: Boolean,
      result: String,
    )
  }

  override fun initPtr(): Long = initContext(this)

  override suspend fun releaseImpl(ptr: Long) = releaseContext(ptr)

  companion object {
    private val dispatcher = ThreadDispatcher("webview")

    /**
     * 传给 WebView2 的浏览器参数（`ICoreWebView2EnvironmentOptions::AdditionalBrowserArguments`）。
     *
     * `--disable-gpu-process-crash-limit`：本机这类 GPU 坏法下（`chrome_debug.log` 里
     * `Failed to open persistent cache files … 另一个程序正在使用此文件 (0x20)`），GPU 进程会
     * 反复崩；默认策略下 Chromium 崩够次数就判定「GPU 不可用」并 `CHECK` 掉**整个浏览器进程**，
     * 所有视图一起死。带上这条之后 GPU 依旧坏，但浏览器进程不再被它带走。
     *
     * ⚠️ 别往这里加 `--no-sandbox` / `--disable-gpu-sandbox` —— 那是安全降级，只在诊断时用环境
     * 变量 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` 按需开（它会**追加**到本参数之后）。
     * 依据与对照实测见 `docs/development/webview2-windows.md` §3。
     */
    private const val BROWSER_ARGUMENTS = "--disable-gpu-process-crash-limit"

    init {
      // 顺序有讲究：`webview.dll` 的导入表里有 `jawt.dll`，而后者只在 JDK 里
      // （不在应用资源、也不在默认搜索路径上）—— 先 `System.load` 进来，
      // loader 解析导入表时「已加载模块」排在搜索路径之前。
      loadLibrary("WebView2Loader")
      loadLibrary("jawt")
      loadLibrary("webview")
      prepareEnvironment(BROWSER_ARGUMENTS, defaultUserDataDir())
      // 收尾在**进程还健康**时做一次：`~WebviewEnviorment`（静态析构）跑在进程退出路径上 ——
      // 那时 STA 要么已被 `ExitProcess` 带走（`threadId_` 失效，线程走不到 `exit_thread`），
      // 要么冻在某个 COM 调用里，于是 `releaseEnvironment()` 里那条无界的 `thread.join()` 可能永远
      // 等不到，进程也就退不出去。放在 shutdown hook 上时泵还在转，线程能干净收场。
      // `releaseEnvironment()` 的**唯一**调用点是这里；产品层（`desktopApp/main.kt`）不要显式调它 ——
      // 收尾交给 hook（应用退出即 JVM 关闭、hook 自动跑），重复调用只会多一次无界 `join()` 的等待。
      Runtime.getRuntime().addShutdownHook(Thread { releaseEnvironment() })
    }

    private fun defaultUserDataDir(): String {
      val home = System.getProperty("user.home").orEmpty()
      val base =
        listOfNotNull(
          System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() },
          home.takeIf { it.isNotBlank() }?.let { File(it, "AppData/Local").path },
          home.takeIf { it.isNotBlank() },
        ).map(::File).firstOrNull { it.isDirectory && it.canWrite() }
          ?: File(System.getProperty("java.io.tmpdir")).also {
            System.err.println(
              "[webview] LOCALAPPDATA 与 user.home 都不可用，user data folder 退到 " +
                "${it.path} —— cookie / 登录态不会跨进程保留",
            )
          }
      val dir = File(base, "ACG/webview2")
      if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
        System.err.println("[webview] 建不出 user data folder：${dir.path}")
      }
      return dir.absolutePath
    }

    private fun Map<String, String>.toFlatArray(): Array<String> {
      if (isEmpty()) return emptyArray()
      val out = ArrayList<String>(size * 2)
      for ((k, v) in this) {
        out += k
        out += v
      }
      return out.toTypedArray()
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

    @JvmStatic
    external fun prepareEnvironment(
      args: String? = null,
      userDir: String? = null,
    )

    @JvmStatic
    external fun releaseEnvironment()

    @JvmStatic
    private external fun initContext(ctx: Webview2Context): Long

    @JvmStatic
    private external fun releaseContext(ptr: Long)

    @JvmStatic
    private external fun navigate(
      ptr: Long,
      url: String,
      method: String,
      headers: Array<String>,
    )

    @JvmStatic
    private external fun executeScript(
      ptr: Long,
      script: String,
      callback: ScriptCallback,
    )

    @JvmStatic
    private external fun setUserAgent(
      ptr: Long,
      ua: String,
    )

    @JvmStatic
    private external fun attach(
      ptr: Long,
      view: Component,
    )

    @JvmStatic
    private external fun detach(ptr: Long)

    @JvmStatic
    private external fun fitToParent(ptr: Long)

    @JvmStatic
    private external fun setBounds(
      ptr: Long,
      width: Int,
      height: Int,
    )

    @JvmStatic
    private external fun reload(ptr: Long)

    @JvmStatic
    private external fun stop(ptr: Long)

    @JvmStatic
    private external fun goBack(ptr: Long)

    @JvmStatic
    private external fun goForward(ptr: Long)

    @JvmStatic
    private external fun openDevTools(ptr: Long)

    /**
     * 读 url 下的 cookie，摊平成 `"n1=v1; n2=v2"` 经 [CookieCallback.onResult] 回来；
     * 没有 cookie 也给空串。回调只来一次。走**本上下文自己**的 cookie manager（native
     * 侧在 controller 就绪时一次取好缓存，随取随用）。
     */
    @JvmStatic
    private external fun getCookie(
      ptr: Long,
      url: String,
      callback: CookieCallback,
    )

    /**
     * 写一个 cookie（结构化字段）到**本上下文自己**的 manager。域 / 路径
     * 缺省由 Kotlin 端按 url 推好再传进来。`expiresEpochSeconds > 0` 表示带过期时间
     * （自 Unix 纪元起的秒），否则会话 cookie。`sameSite`：0=NONE, 1=LAX, 2=STRICT。
     */
    @JvmStatic
    private external fun setCookie(
      ptr: Long,
      name: String,
      value: String,
      domain: String,
      path: String,
      expiresEpochSeconds: Double,
      isSecure: Boolean,
      isHttpOnly: Boolean,
      sameSite: Int,
    )

    @JvmStatic
    private external fun clearAllCookies(ptr: Long)

    /** `getCookie` 的结果收件人 —— 由 native 线程直接调进来，故 [Keep]。 */
    @Keep
    abstract class CookieCallback {
      @Keep
      abstract fun onResult(cookies: String)
    }

    private const val SCRIPT_TIMEOUT_MS = 15_000L

    /** cookie 读写的超时（比脚本短，读不到就当没有，别卡住请求）。 */
    internal const val COOKIE_TIMEOUT_MS = 5_000L
  }
}
