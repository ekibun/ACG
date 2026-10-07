package soko.ekibun.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.toByteArray
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

expect fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * 「两次数据包之间」的最大不活动时间（毫秒），**闲置超时** —— [Response.read] 内那支
 * `select onTimeout` 的**默认**时长。取值照 ExoPlayer 的 `DEFAULT_READ_TIMEOUT_MILLIS = 8000`。
 *
 * **不是**「请求总时长」：整条流可以播几小时，只要每 8 s 内出过字节就不触发。也不装进两端的
 * `HttpTimeout`（语义对不上，详见 `http-streaming.md` 第一节）。
 */
const val SOCKET_TIMEOUT_MS = 8_000L

object Http {
  /**
   * 建客户端：通用配置收在这里（`Http` 一处），平台侧只补自己的引擎与插件。
   *
   * 显式把引擎层的「闲置超时」设成无限：这条判据**唯一**交给 [Response.read] 内那支
   * `select onTimeout`（见 [SOCKET_TIMEOUT_MS]），两平台行为才一致。不装的话 OkHttp 自带的
   * 10 s read timeout 会照旧生效，而那走的不是我们这支（成因见 `http-streaming.md` 第一节）。
   *
   * 装在**这里**而不是各平台侧：装两遍虽然安全（ktor 对同一 plugin 的重复 `install`
   * 会作用到已有配置上），但会让读者误以为平台侧那处才是权威，而 JVM 侧并不需要。
   */
  private fun createHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    createHttpClientImpl {
      this.block()
      install(HttpTimeout) {
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
      }
    }

  private val clientWithRedirect by lazy {
    createHttpClient {
      followRedirects = true
    }
  }
  private val clientWithoutRedirect by lazy {
    createHttpClient {
      followRedirects = false
    }
  }

  /**
   * 建一条**流式**会话，等它把响应交出来：body 不缓冲，返回一条**活的** [Response]，读多少到多少。
   * 元数据一律从 [Response.delegate] 取；**必须** `close()` 掉它，否则那条连接不会放掉。
   *
   * 「整包消费」（下完整个 body 再用）的场景也走这里，然后 [Response.readAll] ——
   * ktor 的便捷入口内部有一句 `call.save()`，本函数没有，**别指望它替你缓冲**
   * （实测见 `silent-failures.md` 的「整包缓冲」一节）。
   *
   * 拿到 [Response] 的两条消费路径：**Kotlin 侧**（`HttpIO`，显式 `close`）与
   * **JS 侧**（`fetch` 把它当 `_opaque` 挂进 JS，body 由 `Response.arrayBuffer` 桥取，
   * 关闭交给 native 的类析构回调代调）。JS 那条能成立靠的就是「会话开在无父 scope 上」——
   * 推导见 `http-streaming.md` 第四节。
   */
  suspend fun request(options: Map<Any, Any?>): Response {
    // 放行块（= [Response.close] 做的事）：完成它 ktor 随即 cleanup 掉这条响应。
    val done = CompletableDeferred<Unit>()
    // 响应就绪的信号。本函数等它拿到响应就返回；建立期的失败也从它这里出去。
    val ready = CompletableDeferred<Response>()
    // 会话开在**无父** scope 上：挂调用方的 job 会死锁（推导见 `http-streaming.md`
    // 第四节）。块也**不能**在这里返回（返回后 ktor 会 cleanup），靠 `done.await()`
    // 挂住直到 close。
    val session =
      CoroutineScope(Dispatchers.IO).launch {
        try {
          (if (options["redirect"] == "follow") clientWithRedirect else clientWithoutRedirect)
            .prepareRequest(options["url"] as String) { applyOptions(options) }
            .execute { rsp ->
              ready.complete(Response(delegate = rsp, done = done))
              done.await()
            }
        } catch (t: Throwable) {
          // [ready] 没完成 = 失败在**建立期**（建连 / TLS / 等响应头）：必须传出去，否则上面
          // `ready.await()` 永远挂着。已完成 = 响应已交出去、这次读自己会以 EOF / 异常收场，不用传。
          if (!ready.isCompleted) ready.completeExceptionally(t)
        }
      }
    try {
      // 建立期没有 abort 支：作废走**取消这条协程**，`ready.await()` 会被一起掀掉。
      // 所以这里就一句 await —— 建连 / TLS 握手 / 等响应头多长都无所谓，取消随时能到。
      return ready.await()
    } finally {
      // 只在**没交出响应**时收会话：判据是 [ready] —— 成功那一路 [session] 正挂在 `done.await()`
      // 上、活得好好的，按别的判据（比如 job 的完成状态）判断会把调用方马上要用的连接切掉。
      if (!ready.isCompleted) session.cancel()
    }
  }

  /**
   * 一条**活着的**流式响应（= 一条连接）。只包一层 [delegate]，**不转发** ktor 的成员 —— 取元数据
   * 一律 `rsp.delegate.status` 这样写，需要什么取什么。
   *
   * **关会话 ≠ 作废本轮**：作废是**一次性事件**（`HttpIO` 的哨兵取消整条读，会话**保留**）；
   * [close] **不可逆**，掀通道 + 放块回去 + ktor cleanup。
   *
   * 两条读法：[read] 读多少到多少（ffmpeg 边下边用那条）、[readAll] 一次读完
   * （插件 JS 的 `Response.arrayBuffer()` 走那条）。**混用会串** —— 见各自 KDoc。
   *
   * 本类**不含任何 `runBlocking`**，阻塞交给调用方。
   *
   * 本类实现 [AutoCloseable]，而它会经 `jsWrapObject` 原样挂到 JS 对象上 ——
   * JS 侧把它丢掉时，native 的类析构回调会**替 JS 调一次 [close]**
   * （`cxx/quickjs/quickjs.cpp` 的 `JavaObject` finalizer）。所以 [close] 必须
   * 能在任意线程上可调、且幂等（实测两条关闭路径并发到达是常态）。
   */
  class Response internal constructor(
    /** ktor 响应本体。元数据（`status` / `headers` / `call` …）全部从这里取。 */
    val delegate: HttpResponse,
    /** 完成它 = 放会话的 `block` 回去（ktor 随即 cleanup）。幂等。 */
    private val done: CompletableDeferred<Unit>,
  ) : AutoCloseable {
    /**
     * 本条响应是否已经 [close] 过 —— 直接问会话本身，不另设标志：
     * [close] 做的第一件事就是 `done.complete(Unit)`，而 `done` 完成后不会再变回未完成。
     * 两条关闭路径（调用方显式 close、JS 侧 GC 时的 `JavaObject` finalizer）并发到达时，
     * `complete` 只有一个赢家，另一路读到的是「已完成」—— 语义正好对。
     */
    val isClosed: Boolean get() = done.isCompleted

    /**
     * 流总长度；**未知时 -1**，不是 0（0 会被 ffmpeg 当成「长度为零的流」，把后续 seek
     * 全判成越界）。
     */
    val contentLength: Long =
      delegate.headers[HttpHeaders.ContentRange]
        ?.substringAfterLast('/')
        ?.trim()
        ?.toLongOrNull()
        ?: delegate.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        ?: -1L

    /**
     * **网络游标**：本条响应在流里的绝对位置，由 [read] 自己推进（白读丢弃一样算）。
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

    /** 响应体的解码通道；**别用 `delegate.rawContent` 替代**（raw 未解码、每次访问都新建一条）。 */
    private var channelOrNull: ByteReadChannel? = null

    private suspend fun channel(): ByteReadChannel =
      channelOrNull ?: delegate.bodyAsChannel().also { channelOrNull = it }

    /**
     * 把余下的 body **整包**读完，**读完即 [close]** —— 「不流式、只要一次性拿到全部」的那条路。
     *
     * 与 [read] 的分工：[read] 读多少到多少（ffmpeg 边下边用那条），本方法一次读完。
     * 走同一条 [channel]，所以**混用会串** —— 读完再 `read` 只会拿到流尾。
     *
     * 供插件 JS 的 `Response.arrayBuffer()` 用（`init.js` 那边 await 之后才调），
     * 跑在 IO 线程上。它**不**复用 [read] 的闲置超时，所以**没有**超时：
     * 引擎层的 socket 超时已被本对象 `createHttpClient` 设成无限，而这里也没有
     * `select` 那一支 —— 读完立刻关掉，不给「卡住的服务端」留窗口。
     *
     * 关掉的理由：这条路径的消费者（插件 JS）拿到字节数组就不再需要这条连接，
     * 而 [close] 顺带把会话的 `block` 放回给 ktor cleanup。
     * 用 [use] 而不是「读完再手动 close」：连接被服务端掐断时 `toByteArray()` 会抛，
     * 那条路上连接与响应同样要放掉 —— `use` 覆盖成功与异常两条出口。
     */
    suspend fun readAll(): ByteArray = use { channel().toByteArray() }

    /**
     * 通道里**已经到达、但还没被 [read] 取走**的字节数。
     *
     * 两条反直觉的性质（机制与实测见 `http-streaming.md` 第六节）：
     * - **通道没建时返回的 0 是假的** —— 只说明本对象还没拿到通道引用，不说明网络上下过什么；
     * - **存量不会自己涨** —— 搬运只发生在读侧，想让它变多只能靠读，而读会同时推进 [offset]。
     *
     * 不含引擎侧尚未交给 ktor 通道的字节（桌面路径的 JDK 块、Android 路径的 okio 段）⇒ 本值
     * **偏小**，拿它当「已缓冲」是保守的。
     */
    val availableForRead: Int
      get() = channelOrNull?.availableForRead ?: 0

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
      // 已关闭的响应上再读是**用错**，不是 EOF：通道已被 cancel，readAvailable 会给出一个
      // 语义不明的失败（而不是 0）。当场拒绝，别让它伪装成「读空了」。
      if (isClosed) throw IllegalStateException("Response already closed")
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
     *
     * **幂等，且两条路径并发到达也安全** —— 不另设标志，两句话各自已经幂等：
     * 通道 `cancel` 在已关闭时立刻返回（ktor `ByteChannelImpl.cancel` 的
     * `_closedCause != null` 早退），[done] 的 `complete` 第二次调用返回 false、不重复触发
     * （实测 8 线程并发 complete 50 轮，每轮都只有一个赢家）。所以这里可以**直接调两次**。
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
  public class IdleTimeoutException : RuntimeException("HTTP idle timeout: 服务端 ${SOCKET_TIMEOUT_MS}ms 未出字节")

  /**
   * **整包 / 流式两种执行共用这一份** —— 抄两遍迟早只改一处，而「少带一个 header」是难查的静默失效。
   */
  private fun HttpRequestBuilder.applyOptions(options: Map<Any, Any?>) {
    if (options["credentials"] == "omit") {
      headers.remove(HttpHeaders.Cookie)
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
