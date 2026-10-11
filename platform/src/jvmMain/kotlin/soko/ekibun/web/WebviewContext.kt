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
 * [attach] 到 AWT 组件上、以及视口给多大。
 *
 * 所有 `external` 调用都投到 [dispatcher] 上跑：宿主窗口、消息泵线程、以及
 * `ICoreWebView2Controller` 都归属那条线程，别的线程只能投递不能直碰 ——
 * 所以对外的每个方法都是「投递」形态，调用方不必自己切线程。
 *
 * native 侧 environment 是**进程内共享**的（同一 user data folder 上不允许并存
 * 多个），每个上下文只在这个 environment 下开自己的窗口与 controller；
 * cookie 因此天然在可见页与后台页之间共享。详见 `cxx/webview/webview.cmake`。
 */
class WebviewContext : Pointer(dispatcher = dispatcher) {
  companion object {
    private val dispatcher = ThreadDispatcher("webview")

    init {
      // 顺序有讲究：`webview.dll` 的导入表里有 `jawt.dll`，而后者只在 JDK 里
      // （不在应用资源、也不在默认搜索路径上）—— 先 `System.load` 进来，
      // loader 解析导入表时「已加载模块」排在搜索路径之前。
      loadLibrary("WebView2Loader")
      loadLibrary("jawt")
      loadLibrary("webview")
      prepareEnvironment("", defaultUserDataDir())
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
    private external fun initContext(ctx: WebviewContext): Long

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

    @JvmStatic
    private external fun focus(ptr: Long)

    private const val SCRIPT_TIMEOUT_MS = 15_000L
  }

  override fun initPtr(): Long = initContext(this)

  override suspend fun releaseImpl(ptr: Long) = releaseContext(ptr)

  /** `ExecuteScript` 的结果收件人 —— 由 native 线程直接调进来，故 [Keep]。 */
  abstract class ScriptCallback {
    @Keep
    abstract fun onResult(
      ok: Boolean,
      script: String,
    )
  }

  interface Listener {
    /**
     * 请求拦截；返回 `true` = 命中（native 会给一个空 204 并把整轮加载停掉）。
     *
     * 已经把结果交给调用方之后再返回 `true` —— 命中之后加载就被中止了，
     * 不会再有第二次机会。
     */
    fun onWebResourceRequested(
      url: String,
      method: String,
      isForMainFrame: Boolean,
      headers: Map<String, String>,
    ): Boolean = false

    /**
     * 宿主事件。事件名与 data 的口径由 native 定：
     *
     * | 事件 | data | 时机 |
     * | --- | --- | --- |
     * | `urlChanged` | 目标地址 | 开始导航（失败那轮也给，完成时取不到） |
     * | `navigationStarting` | 空 | 紧接着 `urlChanged` |
     * | `documentLoaded` | 文档地址 | 导航**成功** |
     * | `historyChanged` | `"canGoBack canGoForward"`，各为 `1` / `0` | 导航成功 |
     * | `navigationCompleted` | 空 | 导航成功 |
     * | `navigationFailed` | 空 | 导航失败 |
     * | `documentTitleChanged` | 标题 | 标题变了 |
     * | `processFailed` | `"kind reason=… exit=…"` | 浏览器 / 渲染进程挂了 |
     *
     * `processFailed` 的 `kind` / `reason` / `exit` 都是 native 侧的枚举**数值**，
     * 没做名字映射 —— 要对上含义得查 `COREWEBVIEW2_PROCESS_FAILED_*`。
     */
    fun onEvent(
      event: String,
      data: String,
    ) {}
  }

  var listener: Listener? = null

  @Keep
  private fun onWebResourceRequested(
    url: String,
    method: String,
    isForMainFrame: Boolean,
    headers: Array<String>,
  ): Boolean = listener?.onWebResourceRequested(url, method, isForMainFrame, headers.toHeaderMap()) ?: false

  @Keep
  private fun onEvent(
    event: String,
    data: String,
  ) {
    listener?.onEvent(event, data)
  }

  suspend fun navigate(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
  ) {
    withPtr { ptr ->
      navigate(ptr, url, method, headers.toFlatArray())
    }
  }

  suspend fun executeScript(script: String): String? =
    withPtr { ptr ->
      val result = CompletableDeferred<String>()
      executeScript(
        ptr,
        script,
        object : ScriptCallback() {
          override fun onResult(
            ok: Boolean,
            script: String,
          ) {
            if (!result.isActive) return
            if (ok) result.complete(script) else result.cancel()
          }
        },
      )
      withTimeoutOrNull(SCRIPT_TIMEOUT_MS.milliseconds) { result.await() }
    }

  fun setUserAgent(ua: String) {
    submit { setUserAgent(ptr, ua) }
  }

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

  fun loadUrl(
    url: String,
    headers: Map<String, String>,
  ) {
    submit { navigate(ptr, url, "GET", headers.toFlatArray()) }
  }

  fun reload() {
    submit { reload(ptr) }
  }

  fun stop() {
    submit { stop(ptr) }
  }

  fun goBack() {
    submit { goBack(ptr) }
  }

  fun goForward() {
    submit { goForward(ptr) }
  }

  fun openDevTools() {
    submit { openDevTools(ptr) }
  }

  fun dispose() {
    submit { close() }
  }

  fun focus() {
    submit { focus(ptr) }
  }
}
