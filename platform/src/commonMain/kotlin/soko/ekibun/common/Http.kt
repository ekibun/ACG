package soko.ekibun.common

/**
 * HTTP 会话的公共面，只声明调用方用得到的成员。
 * [request] 交出的是一条活着的流式响应，body 不缓冲，用完必须 `close()`。
 */
object Http {
  /** 两次数据包之间的最大不活动时间（毫秒），[Response.read] 的闲置超时判据。 */
  const val SOCKET_TIMEOUT_MS = 8_000L

  /** 闲置超时。 */
  class IdleTimeoutException : RuntimeException("HTTP idle timeout: no bytes for ${SOCKET_TIMEOUT_MS}ms")

  /** 一条流式响应（= 一条连接）。 */
  interface Response : AutoCloseable {
    /** 最终 URL（跟随重定向之后）。 */
    val url: String

    /** 状态码。 */
    val status: Int

    /** 响应头；同名头保留全部值。 */
    val headers: Map<String, List<String>>

    /** 流总长度；未知为 -1。 */
    val contentLength: Long

    /** 网络游标：本条响应在流里的绝对位置，由 [read] 推进。 */
    val offset: Int

    /** 已到达但还没被 [read] 取走的字节数。 */
    val availableForRead: Int

    /** 还能不能读。 */
    val isClosed: Boolean

    /**
     * 最多把 [len] 字节读进 `buf[off, off+len)`，返回读到的字节数并推进 [offset]。
     * 闲置超时抛 [IdleTimeoutException]，已关闭时抛 [IllegalStateException]。
     */
    suspend fun read(
      buf: ByteArray,
      off: Int = 0,
      len: Int = buf.size,
    ): Int

    /** 读完余下的 body，读完即 [close]。 */
    suspend fun readAll(): ByteArray

    /** 放掉连接；幂等，任意线程可调。 */
    override fun close()
  }

  /**
   * 建一条流式会话并等它交出响应，用完必须 `close()`。
   * `options` 的键为 `url` / `headers` / `method` / `body` / `redirect`。
   */
  suspend fun request(options: Map<Any, Any?>): Response = openResponse(options)
}

/** 传输层原语：建连、等响应头、交出一条流式会话。 */
internal expect suspend fun openResponse(options: Map<Any, Any?>): Http.Response
