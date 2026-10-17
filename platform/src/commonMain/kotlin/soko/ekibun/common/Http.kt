package soko.ekibun.common

/**
 * HTTP 会话的公共面：**只声明调用方用得到的成员**，实现全在平台的 JVM 族（`jvmShared` 的 OkHttp）。
 * 所以本节不出现任何传输库类型 —— 换传输（比如将来 iOS 的 NSURLSession）只换 actual，
 * `:shared` 一行不动。
 *
 * 流式语义：[request] 交出的是一条**活着的**响应，body 不缓冲，读多少到多少；
 * **必须** `close()` 掉它，否则那条连接不会放掉。
 */
object Http {
  /**
   * 「两次数据包之间」的最大不活动时间（毫秒），**闲置超时** —— [Response.read] 的判据。
   * 取值照 ExoPlayer 的 `DEFAULT_READ_TIMEOUT_MILLIS = 8000`。
   *
   * **不是**「请求总时长」：整条流可以播几小时，只要每 8 s 内出过字节就不触发。
   */
  const val SOCKET_TIMEOUT_MS = 8_000L

  /**
   * 闲置超时用尽：`HttpIO.read` 把它当普通故障（打印栈 + 返回 -1），与「作废」区分开 ——
   * 作废是调用方要求的、静默；这个是服务端真死了。
   */
  class IdleTimeoutException : RuntimeException("HTTP idle timeout: 服务端 ${SOCKET_TIMEOUT_MS}ms 未出字节")

  /**
   * 一条**活着的**流式响应（= 一条连接）。
   *
   * **关会话 ≠ 作废本轮**：作废是**一次性事件**（`HttpIO` 的哨兵取消整条读，会话**保留**）；
   * [close] **不可逆**，放掉连接。
   *
   * 两条读法：[read] 读多少到多少（ffmpeg 边下边用那条）、[readAll] 一次读完
   * （插件 JS 的 `Response.arrayBuffer()` 走那条）。**混用会串** —— 读完再 [read] 只剩流尾。
   *
   * 本接口实现 [AutoCloseable]，而它会经 `jsWrapObject` 原样挂到 JS 对象上 —— JS 侧把它丢掉时，
   * native 的类析构回调会**替 JS 调一次** [close]（`cxx/quickjs/quickjs.cpp` 的 `JavaObject`
   * finalizer）。所以 [close] 必须能在任意线程上可调、且幂等。
   */
  interface Response : AutoCloseable {
    /** 最终 URL（跟随重定向之后）。插件 JS 的 `fetch` 拿它当 `meta.url`。 */
    val url: String

    /** 状态码，如 200 / 206 / 302。 */
    val status: Int

    /** 响应头；同名头保留全部值。 */
    val headers: Map<String, List<String>>

    /**
     * 流总长度；**未知时 -1**，不是 0（0 会被 ffmpeg 当成「长度为零的流」，把后续 seek
     * 全判成越界）。
     */
    val contentLength: Long

    /**
     * **网络游标**：本条响应在流里的绝对位置，由 [read] 自己推进（白读丢弃一样算）。
     *
     * **不**从调用方灌进来 —— 「从哪开始」是服务端在 header 里**亲口说的**（`Content-Range`）。
     */
    val offset: Int

    /**
     * 已经到达、但还没被 [read] 取走的字节数。
     *
     * **存量不会自己涨** —— 想让它变多只能靠读，而读会同时推进 [offset]。不含传输层尚未交付的
     * 字节 ⇒ 本值**偏小**，拿它当「已缓冲」是保守的。
     */
    val availableForRead: Int

    /**
     * 这条响应还能不能读。**除了 [close]，读被取消也算数** —— 叫醒一次阻塞读的手段只有关掉连接，
     * 于是「本轮作废」掀掉的那条会话当场作废（`HttpIO` 据此丢掉它、下一条读重发）。
     */
    val isClosed: Boolean

    /**
     * **等数据到、再读一段**：最多把 [len] 字节读进 `buf[off, off+len)`，读到的字节数推进
     * [offset]；闲置超时（[SOCKET_TIMEOUT_MS]）抛 [IdleTimeoutException]。
     *
     * 返回**原始** [Int]、不做 EOF 翻译（翻译留在 `HttpIO.read`；返回 0 会被 aviobuf 当
     * 「读空，继续要数据」）。阻塞发生在实现里的 IO 线程上，**协程取消会掀掉连接**、把那次阻塞
     * 读一起叫醒 —— `HttpIO` 的作废就靠这条，代价是那条会话当场作废（见 [isClosed]）。
     *
     * 已作废（[isClosed]）的响应上再读是**用错**，不是 EOF：抛 [IllegalStateException]。
     */
    suspend fun read(
      buf: ByteArray,
      off: Int = 0,
      len: Int = buf.size,
    ): Int

    /**
     * 把余下的 body **整包**读完，**读完即 [close]** —— 「不流式、只要一次性拿到全部」的那条路。
     * 供插件 JS 的 `Response.arrayBuffer()` 用。
     */
    suspend fun readAll(): ByteArray

    /** 关掉这条响应：叫醒可能正卡在 [read] 里的那次阻塞读，放掉连接。幂等，任意线程可调。 */
    override fun close()
  }

  /**
   * 建一条**流式**会话，等它把响应交出来。元数据一律从 [Response] 取；**必须** `close()` 掉它。
   *
   * `options` 的键由 JS 侧 `init.js` 决定：`url`、`headers`、`method`、`body`、
   * `redirect`（`"follow"` 才跟随）。cookie 也只是 `headers` 里的一项，本层不增不减。
   * 整包消费的场景也走这里，再 [Response.readAll] —— 它**不替你缓冲**。
   */
  suspend fun request(options: Map<Any, Any?>): Response = openResponse(options)
}

/** 传输层的最小原语：建连、等响应头、交出一条能阻塞读的会话。平台实现在 `jvmShared`。 */
internal expect suspend fun openResponse(options: Map<Any, Any?>): Http.Response
