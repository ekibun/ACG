package soko.ekibun.web

import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import soko.ekibun.Pointer
import soko.ekibun.ThreadDispatcher
import soko.ekibun.common.Paths
import soko.ekibun.loadLibrary
import java.awt.Component
import kotlin.time.Duration.Companion.milliseconds

/**
 * 一个 WebView2 上下文：可见视图与后台页共用，差别在是否 [attach] 到 AWT 组件及视口大小。
 * 所有 `external` 调用都投到 [dispatcher] 上执行。
 * [attach] / [fitToParent] / [setBounds] 是桌面私有能力，不上接口、不挂起。
 */
class Webview2Context(
  isHeadless: Boolean = false,
) : Pointer(dispatcher = dispatcher),
  WebviewContext {
  init {
    // 后台页固定 1×1 视口
    if (isHeadless) submit { setBounds(ptr, 1, 1) }
  }

  override var onEvent: ((WebViewEvent) -> Unit)? = null

  override var onInterceptor:
    ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)? = null

  override suspend fun setUserAgent(ua: String) {
    withPtr { ptr -> setUserAgent(ptr, ua) }
  }

  /** 桌面端的 devtools 由 [openDevTools] 打开，这里无操作。 */
  override suspend fun setDevtoolsEnabled(enabled: Boolean) = Unit

  // 桌面上"不可见"由 WebViewCanvas 销毁时 detach 保证，这里不用改尺寸：
  // 一旦缩成 1×1，再挂回去若尺寸没变就拿不到新的 bounds，页面是一片黑
  override suspend fun setVisible(visible: Boolean) = Unit

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

  /** 读 [url] 下的 cookie，返回 native 摊平的 `"n1=v1; n2=v2"`；没有则空串。 */
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

  /** 写一条 cookie。参数为名称、值、域、路径、过期秒数、Secure、HttpOnly、SameSite。 */
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

  // ---- 桌面私有能力：不上接口、不挂起 ----

  fun attach(view: Component) {
    submit { attach(ptr, view) }
  }

  /**
   * 摘窗口（异步投递），调用点只有 AWT 的 `removeNotify`：任务注销与窗口关闭，晚到都无所谓。
   *
   * 若哪天改成「卸载宿主再挂回来」，必须换回同步——父窗口一销毁会连带销毁子窗口，
   * 之后再 `attach` 只能对着死句柄 `SetParent`。
   */
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

  // ---- native 回调入口，由宿主线程直接调用 ----

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
          // data 形如 "1 0"
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

  /** `ExecuteScript` 的结果收件人。 */
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

    /** 传给 WebView2 的浏览器参数（`AdditionalBrowserArguments`）。 */
    private const val BROWSER_ARGUMENTS = "--disable-gpu-process-crash-limit"

    init {
      // 加载顺序：WebView2Loader、jawt、webview
      loadLibrary("WebView2Loader")
      loadLibrary("jawt")
      loadLibrary("webview")
      prepareEnvironment(BROWSER_ARGUMENTS, Paths.dataDir + "/webview2")
      // 进程退出时释放 environment
      Runtime.getRuntime().addShutdownHook(Thread { releaseEnvironment() })
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

    /** 读 [url] 下的 cookie，摊平成 `"n1=v1; n2=v2"` 经回调返回；没有 cookie 给空串。 */
    @JvmStatic
    private external fun getCookie(
      ptr: Long,
      url: String,
      callback: CookieCallback,
    )

    /** 写一条 cookie；`expiresEpochSeconds > 0` 才带过期时间，`sameSite`：0=NONE, 1=LAX, 2=STRICT。 */
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

    /** `getCookie` 的结果收件人。 */
    @Keep
    abstract class CookieCallback {
      @Keep
      abstract fun onResult(cookies: String)
    }

    private const val SCRIPT_TIMEOUT_MS = 15_000L

    /** cookie 读写超时（毫秒）。 */
    internal const val COOKIE_TIMEOUT_MS = 5_000L
  }
}
