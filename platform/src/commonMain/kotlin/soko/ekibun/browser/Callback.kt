package soko.ekibun.browser

// 外部浏览器登录的两个平台原语：一个只属于本次登录的回环回调地址，以及「用系统
// 默认浏览器打开它」。编排（占位符替换、返回值形状）在 :shared 的 JsEngine 里。
//
// 为什么要内建服务器：几种回流方式里只有 loopback 重定向不需要服务商预注册回调地址、
// 也不需要自定义 scheme —— 自定义 scheme 在桌面上会另起一个进程，照样得靠通信
// 把结果送回本进程；而 loopback 就是本进程自己的 socket。实测（Bangumi OAuth 全流程）
// 服务商接受任意端口 + 任意路径的回调地址，不要求预先登记。

/**
 * 回环回调会话。
 *
 * 进程内只有一个常驻的 HTTP 服务器（绑 `127.0.0.1` 上一个 OS 分配的临时端口，
 * 进程活多久它活多久）。端口被所有会话共用，所以**会话靠 URL 路径区分**：
 * [callbackUrl] 的路径段就是本次会话的 id，其余路径一律 404。插件在授权链接里
 * 放一次占位符就能拿到只属于这次登录的地址，多个登录并行也互不串扰。
 *
 * 没有超时：用户可能把浏览器标签挂在后台很久再回来点登录。[close] 撤掉会话 ——
 * 没兑现的会话不会自己消失，收尾必须显式关。
 */
interface CallbackSession : AutoCloseable {
  /**
   * 本次会话的地址，形如 `http://127.0.0.1:51987/9f3a4b6c2d1e0f8a7b5c4d3e`。
   *
   * **无查询串、无尾斜杠。** OAuth 换 token 时 `redirect_uri` 必须与授权请求里
   * 逐字相同，多带一个 `?code=…` 就不是它了 —— 所以这个值是干净的地址，
   * 浏览器带回的结果由 [awaitCallback] 另给。
   */
  val callbackUrl: String

  /**
   * 等浏览器打中 [callbackUrl]，返回**它实际请求的那个完整 URL**（含查询串）。
   *
   * 返回的是原样的 request-target 接上本服务器的 origin，**不做百分号解码** ——
   * 与 `location.href` 同一语义。解码归调用方按段做：整串解会把值里的 `%26`
   * 解成 `&`、把参数切开。不超时。
   */
  suspend fun awaitCallback(): String

  /** 撤掉会话；之后再打中这个地址按 404 处理。幂等。 */
  override fun close()
}

/** 在进程内建的回环服务器上开一个会话。 */
expect fun createCallbackSession(): CallbackSession

/**
 * 用系统默认浏览器打开 [url]。
 *
 * Android = Chrome Custom Tabs（没装支持 Custom Tabs 的浏览器时，那条 Intent 就是
 * 普通 `ACTION_VIEW`，系统自己兜到别的浏览器）；桌面 = `java.awt.Desktop`。
 *
 * 起不来时抛 [IllegalStateException]（Android 缺宿主注入的 Context、桌面无可用的
 * 浏览器或无头环境），消息给人看。
 */
expect suspend fun launchExternalBrowser(url: String)
