package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.getValue
import kotlin.time.Duration.Companion.milliseconds

expect fun createHttpClient(followRedirects: Boolean): HttpClient

/**
 * 「两次数据包之间」的最大不活动时间（毫秒），**闲置超时** —— [Response.read] 内那支
 * `select onTimeout` 的**默认**时长。取值照 ExoPlayer 的 `DEFAULT_READ_TIMEOUT_MILLIS = 8000`。
 *
 * **不是**「请求总时长」：整条流可以播几小时，只要每 8 s 内出过字节就不触发。也不装进两端的
 * `HttpTimeout`（语义对不上，详见 `http-streaming.md` 第一节）。
 */
const val SOCKET_TIMEOUT_MS = 8_000L

object Http {
  private val clientWithRedirect by lazy {
    createHttpClient(followRedirects = true)
  }
  private val clientWithoutRedirect by lazy {
    createHttpClient(followRedirects = false)
  }

  /**
   * 发一次请求，**响应体已整包缓冲**后再交给调用方。走 ktor 的便捷入口，内部有一句 `call.save()`。
   * 「边下边用」的场景必须用 [requestStreaming]（实测见 `silent-failures.md` 的「整包缓冲」一节）。
   */
  suspend fun request(options: Map<Any, Any?>): HttpResponse {
    val url = options["url"] as String
    return clientFor(options).request(url) { applyOptions(options) }
  }

  /**
   * 与 [request] 同参数，但**流式**：body 不缓冲，返回一条**活的** [Response]，读多少到多少。
   *
   * **生命周期**：`execute` 的块返回后 ktor 立刻 `cleanup()` 掉响应，所以这里让块**挂住**
   * （`done.await()`）直到对方 [Response.close] —— 一条连接能**跨多次读**慢慢用。
   * 会话 scope 为什么不能挂在调用方 job 上（会死锁），见 `http-streaming.md` 第四节。
   */
  suspend fun requestStreaming(options: Map<Any, Any?>): Response {
    val url = options["url"] as String
    // 放行块（= [Response.close] 做的事）：完成它 ktor 随即 cleanup 掉这条响应。
    val done = CompletableDeferred<Unit>()
    // 响应就绪的信号。[requestStreaming] 等它拿到响应就返回；建立期的失败也从它这里出去。
    val ready = CompletableDeferred<Response>()
    // 会话开在无父 scope 上：**不能**挂调用方的 job —— `runBlocking` 等所有子协程，而会话等
    // [Response.close]、close 又等 `runBlocking` 返回，直接死锁（推导见 http-streaming.md 第四节）。
    // 块**不**在这里返回（返回后 ktor 会 cleanup），靠 `done.await()` 挂住直到 close，故也不能直接 await。
    val session =
      CoroutineScope(Dispatchers.IO).launch {
        try {
          clientFor(options).prepareRequest(url) { applyOptions(options) }.execute { rsp ->
            ready.complete(Response(delegate = rsp, done = done))
            done.await()
          }
        } catch (t: Throwable) {
          // [ready] 还没完成 = 失败在**建立期**（建连 / TLS / 等响应头）：必须从这里传出去，
          // 否则上面 `ready.await()` 永远挂着。已经完成 = 响应已交出去、这次读自己会以
          // EOF / 异常收场，没有人会 await 这条 job，不用传。
          if (!ready.isCompleted) ready.completeExceptionally(t)
        }
      }
    try {
      // 建立期没有 abort 支（见 KDoc）：作废走**取消这条协程**，`ready.await()` 会被
      // 一起掀掉。所以这里就一句 await —— 建连 / TLS 握手 / 等响应头多长都无所谓，
      // 取消随时能到。
      return ready.await()
    } finally {
      // 只在**没交出响应**时收会话：判据是 [ready]（响应有没有交出去）。成功那一路
      // [session] 正挂在 `done.await()` 上、活得好好的，按别的判据（比如这条 job 自己的
      // 完成状态）判断会把调用方马上要用的连接一刀切掉。
      if (!ready.isCompleted) session.cancel()
    }
  }

  /**
   * 一条**活着的**流式响应（= 一条连接）。只包一层 [delegate]，**不转发** ktor 的成员 —— 取元数据
   * 一律 `rsp.delegate.status` 这样写，需要什么取什么。
   *
   * [delegate] 必须是 `execute` 块内捕获的那个 `HttpResponse`（块返回后 ktor 会 cleanup 它，所以
   * [requestStreaming] 把块挂住直到 [close]）。
   *
   * **关会话 ≠ 作废本轮**：作废是**一次性事件**（`HttpIO` 的哨兵取消整条读，会话**保留**）；
   * [close] **不可逆**，掀通道 + 放块回去 + ktor cleanup。
   */
  class Response internal constructor(
    /** ktor 响应本体。元数据（`status` / `headers` / `call` …）全部从这里取。 */
    val delegate: HttpResponse,
    /** 完成它 = 放会话的 `block` 回去（ktor 随即 cleanup）。幂等。 */
    private val done: CompletableDeferred<Unit>,
  ) : AutoCloseable {
    /**
     * 流总长度；**未知时 -1**，不是 0（0 会被 ffmpeg 当成「长度为零的流」，
     * 把后续 seek 全判成越界）。
     *
     * 从 [delegate] 的 header 推 —— 200 给的是 `Content-Length`；206 给的是
     * `Content-Range: bytes start-end/total`，其中 `/total` 才是流总长，两种都要看。
     */
    val contentLength: Long =
      delegate.headers[HttpHeaders.ContentRange]
        ?.substringAfterLast('/')
        ?.trim()
        ?.toLongOrNull()
        ?: delegate.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        ?: -1L

    /**
     * **网络游标**：本条响应在流里的绝对位置。起点**从 [delegate] 的 header 推**
     * （206 的 `Content-Range` 给出起点；没这个头 —— 也就是 200 从头给 —— 则是 0），
     * 之后由 [read] 自己推进（白读丢弃一样算）。
     *
     * **不**从调用方灌进来 —— 「从哪开始」是服务端在 header 里**亲口说的**。
     * 解析为什么走字符串而不是 ktor 的 `parseRangesSpecifier`，见 `http-streaming.md` 第五节。
     */
    var offset: Int =
      delegate.headers[HttpHeaders.ContentRange]
        ?.substringBefore('-')
        ?.removePrefix("bytes")
        ?.trim()
        ?.toIntOrNull()
        ?: 0
      private set

    /**
     * 响应体的解码通道，**首次访问时**挂起取来（`bodyAsChannel()` 是 suspend，`lazy` 装不下）。
     * 那次访问一定发生在 `execute` 块**还没返回**的时候（[read] / [close] 都在块挂住期间被
     * 调用），此时 [delegate] 仍是活的。
     *
     * 别用 `delegate.rawContent` 替代（**raw 未解码**、每次访问都新建一条）。本类**不含任何
     * `runBlocking`**，阻塞交给调用方。
     */
    private var channelOrNull: ByteReadChannel? = null

    private suspend fun channel(): ByteReadChannel =
      channelOrNull ?: delegate.bodyAsChannel().also { channelOrNull = it }

    /**
     * **等数据到、再读一段** —— 「下一批数据到」与「闲置超时」两支竞速（全是事件驱动），前者赢就把
     * 最多 [len] 字节读进 `buf[off, off+len)`，后者赢就抛 [IdleTimeoutException]，读到的字节数推进 [offset]。
     * **「等」和「读」必须成对出现**（所以合成一个方法）：ktor 的 `readAvailable` 在通道空时内部是**裸的
     * `awaitContent()`** —— 没有超时、也不在调用方自己的取消点上，直接调它就卡死在那里。
     *
     * 超时用**异常**而不是返回值（调用方要么往上抛、要么 catch 住换会话重试，返回值得让它当场分叉）。
     * **没有「作废」这一支** —— 作废由 `HttpIO.read` 的哨兵**取消整条读协程**表达，`finally` 里必须
     * **显式**收掉落选的 `data`。返回**原始** [Int]、不做 EOF 翻译（翻译留在 `HttpIO.read`；返回 0
     * 会被 aviobuf 当「读空，继续要数据」）。推导见 `http-streaming.md`。
     */
    @OptIn(ExperimentalCoroutinesApi::class) // `select` 的 `onTimeout` 子句目前仍是 experimental API
    suspend fun read(
      buf: ByteArray,
      off: Int = 0,
      len: Int = buf.size,
      timeoutMs: Long = SOCKET_TIMEOUT_MS,
    ): Int {
      val chan = channel()
      return coroutineScope {
        // 必须先建出来、再进 `select`：`select` 里现写 `async { }` 会被 kotlinc 报
        // «async can not be called without the corresponding coroutine scope»（它要显式接收者）。
        val data = async { chan.awaitContent(1) }
        try {
          select {
            data.onAwait { chan.readAvailable(buf, off, len).also { n -> if (n > 0) offset += n } }
            onTimeout(timeoutMs.milliseconds) { throw IdleTimeoutException() }
          }
        } finally {
          // **必须**取消落选那支：`coroutineScope` 会等**所有**子协程 —— 超时那支赢下来
          // 时 `data` 还挂在新数据上，不取消的话 `coroutineScope` 直接挂死。
          // `select` 只挑一支、**不**替你取消其余。
          data.cancel()
        }
      }
    }

    /**
     * 关掉这条响应。**两件都要**：cancel 通道掀掉可能正卡在 `readAvailable` 里的那次读，
     * 完成 [done] 让会话的 `block` 回去 —— 只 cancel 通道的话 block 还在等 [done]，
     * 连接与响应都不会被 ktor cleanup。
     *
     * 这里的 cancel 走 [channelOrNull]：**只掀已经建起来的那条**。本条响应从没被读过时通道压根
     * 没建，别为了「有东西可 cancel」去把 body 抽出来（那是 suspend，而且白建一条没人读的通道）。
     */
    override fun close() {
      channelOrNull?.cancel(null)
      done.complete(Unit)
    }
  }

  /**
   * 闲置超时（[SOCKET_TIMEOUT_MS]）用尽重试后的收场信号：`HttpIO.read` 把它当普通故障
   * （打印栈 + 返回 -1），与「作废」区分开 —— 作废是调用方要求的、静默；这个是服务端真死了。
   */
  internal class IdleTimeoutException : RuntimeException("HTTP idle timeout: 服务端 ${SOCKET_TIMEOUT_MS}ms 未出字节")

  private fun clientFor(options: Map<Any, Any?>): HttpClient =
    if (options["redirect"] == "follow") clientWithRedirect else clientWithoutRedirect

  /**
   * 把 [options] 摊到请求上。两个入口**共用这一份** —— 抄两遍迟早只改一处，
   * 而「少带一个 header」是难查的静默失效。
   */
  private fun HttpRequestBuilder.applyOptions(options: Map<Any, Any?>) {
    if (options["credentials"] == "omit") {
      headers.remove(HttpHeaders.Cookie) // 移除自动带上的 Cookie
    }

    (options["headers"] as? Map<*, *>)?.forEach { (key, value) ->
      val k = key.toString()
      if (value is Iterable<*>) {
        value.forEach {
          headers.append(k, it.toString())
        }
      } else {
        value?.let {
          headers.append(k, value.toString())
        }
      }
    }
    val body = options["body"]
    if (body != null) {
      if (body is Map<*, *> && body["__js_proto__"] == "FormData") {
        setBody(
          MultiPartFormDataContent(
            formData {
              (body["__items__"] as? Iterable<*>)?.forEach { item ->
                val formItem = item as? Map<*, *> ?: return@forEach
                val name = formItem["name"] as? String ?: return@forEach
                val value = formItem["value"]
                val type = formItem["type"]

                if (type is String && value is ByteArray) {
                  // 键名必须与 `init.js` 的 `_formItem` 逐字一致：那边写的是
                  // 小写 l 的 `filename`。写成 `fileName` 的话这里恒为 null，
                  // 文件名永远送不到 multipart 头（且不报错，只是静默丢掉）。
                  val fileName = formItem["filename"] as? String
                  append(
                    key = name,
                    value = value,
                    headers =
                      Headers.build {
                        append(HttpHeaders.ContentType, type)
                        fileName?.let {
                          append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"$it\"",
                          )
                        }
                      },
                  )
                } else {
                  append(name, value.toString())
                }
              }
            },
          ),
        )
      } else if (body is ByteArray) {
        setBody(body)
      } else {
        setBody(body.toString())
      }
    }
  }
}
