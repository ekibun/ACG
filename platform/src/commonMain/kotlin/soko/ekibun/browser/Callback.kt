package soko.ekibun.browser

// 外部浏览器登录的两个平台原语：本次登录专用的回环回调地址，以及用系统默认浏览器打开它。

/**
 * 回环回调会话：一个会话对应回环服务器上的一条 URL 路径。
 * [close] 撤掉会话。
 */
interface CallbackSession : AutoCloseable {
  /** 本次会话的地址，形如 `http://127.0.0.1:51987/9f3a4b6c2d1e0f8a7b5c4d3e`。 */
  val callbackUrl: String

  /** 等浏览器打中 [callbackUrl]，返回它实际请求的完整 URL（含查询串，未解码）。 */
  suspend fun awaitCallback(): String

  /** 撤掉会话；之后的请求按 404 处理。幂等。 */
  override fun close()
}

/** 在进程内的回环服务器上开一个会话。 */
expect fun createCallbackSession(): CallbackSession

/** 用系统默认浏览器打开 [url]；起不来抛 [IllegalStateException]。 */
expect suspend fun launchExternalBrowser(url: String)
