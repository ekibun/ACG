package soko.ekibun.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * HTTP 传输层：桌面 JVM 与 Android 共用这一份 OkHttp 实现。
 *
 * **拉取式**，没有预读：okhttp 的 body 就是一条按需读的流，读到哪下到哪，本地存量只有 okio
 * 缓冲那一个段（见 `playback-invariants.md` 5.6）。
 * 闲置超时交给 okhttp 的 `readTimeout`：它按**两次读之间**计时，与 `Http.SOCKET_TIMEOUT_MS`
 * 的语义一致；超时即这条连接作废，`HttpIO` 的「超时 ⇒ 换一条会话」正好接住。
 *
 * 这里**不装 cookieJar**：本层没有隐式 cookie 仓库，cookie 完全由 JS 侧决定 ——
 * 带哪些 cookie 就是 `headers` 里写哪些，本层不增不减。
 */
private val plainClient: OkHttpClient by lazy {
  OkHttpClient
    .Builder()
    .readTimeout(Http.SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
}

/** 与 [plainClient] 共享连接池，只差一条跟随重定向。 */
private val redirectClient: OkHttpClient by lazy {
  plainClient
    .newBuilder()
    .followRedirects(true)
    .followSslRedirects(true)
    .build()
}

actual suspend fun openResponse(options: Map<Any, Any?>): Http.Response {
  val client = if (options["redirect"] == "follow") redirectClient else plainClient
  val call = client.newCall(buildRequest(options))
  val rsp =
    try {
      call.executeOnIo()
    } catch (e: SocketTimeoutException) {
      // 等响应头就超时了：与读超时同等对待，让 HttpIO 的重开那条路接住。
      throw Http.IdleTimeoutException()
    }
  return OkHttpResponse(call, rsp)
}

/**
 * 阻塞的 [Call.execute] 搬上 IO 线程。协程取消时 `cancel()` 掉这次 call ——
 * 阻塞中的 socket 读不看取消点，只能靠关连接叫醒它。
 */
private suspend fun Call.executeOnIo(): okhttp3.Response =
  withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { cancel() }
      cont.resumeWith(runCatching { execute() })
    }
  }

/** 一条活着的流式响应：okhttp 的 [okhttp3.Response] 外面只包这一层。 */
private class OkHttpResponse(
  private val call: Call,
  private val rsp: okhttp3.Response,
) : Http.Response {
  private val stream: InputStream = rsp.body.byteStream()

  /**
   * 这条响应还能不能读。**取消也算作废**：叫醒一次阻塞读的唯一手段是 `cancel()` 掉这次 call，
   * 而那等于关掉连接 —— 之后这条响应再也给不出字节（见 [onIo]）。
   */
  @Volatile
  var closed = false
    private set

  /** `Content-Range: bytes 1024-2047/8192` 是唯一能说明「这条流从哪儿起」的头。 */
  private val contentRange: String? = rsp.header("Content-Range")

  private var networkOffset: Int =
    contentRange
      ?.substringBefore('-')
      ?.removePrefix("bytes")
      ?.trim()
      ?.toIntOrNull()
      ?: 0

  override val url: String = rsp.request.url.toString()

  override val status: Int = rsp.code

  override val headers: Map<String, List<String>> = rsp.headers.toMultimap()

  override val contentLength: Long =
    contentRange
      ?.substringAfterLast('/')
      ?.trim()
      ?.toLongOrNull()
      ?: rsp.header("Content-Length")?.toLongOrNull()
      ?: -1L

  override val offset: Int get() = networkOffset

  override val availableForRead: Int
    get() =
      try {
        stream.available()
      } catch (_: IOException) {
        0
      }

  override val isClosed: Boolean get() = closed

  override suspend fun read(
    buf: ByteArray,
    off: Int,
    len: Int,
  ): Int {
    if (closed) throw IllegalStateException("Response already closed")
    val count =
      onIo {
        try {
          stream.read(buf, off, len)
        } catch (e: SocketTimeoutException) {
          throw Http.IdleTimeoutException()
        }
      }
    // 白读丢弃一样算：网络游标推进的判据是「从流里取走了多少」，不是「调用方用上了多少」。
    if (count > 0) networkOffset += count
    return count
  }

  override suspend fun readAll(): ByteArray =
    try {
      onIo { stream.readBytes() }
    } finally {
      close()
    }

  /**
   * 两件都要：[Call.cancel] 叫醒可能正卡在 [read] 里的那次阻塞读，[okhttp3.Response.close]
   * 把 body 放掉。两条关闭路径（调用方显式 close、JS 侧 `JavaObject` finalizer）并发到达时，
   * [closed] 只有一个赢家。
   */
  override fun close() {
    if (closed) return
    closed = true
    call.cancel()
    rsp.close()
  }

  /**
   * 阻塞 I/O 搬上 IO 线程；协程取消时掀掉这次 call，把阻塞读一起叫醒。
   *
   * 掀掉之后**这条响应就废了**（连接已关），所以顺手置 [closed]：撤作者（`HttpIO` 的作废信号）
   * 据此丢掉这条会话、下一条读重发。
   */
  private suspend fun <T> onIo(block: () -> T): T =
    withContext(Dispatchers.IO) {
      suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
          closed = true
          call.cancel()
        }
        cont.resumeWith(runCatching(block))
      }
    }
}

/**
 * `options` 的键由 JS 侧 `init.js` 决定（`url` / `headers` / `method` / `body` / `redirect`）。
 * 拼请求的规则与旧实现逐条对应，**少带一个 header 是难查的静默失效**。
 */
private fun buildRequest(options: Map<Any, Any?>): Request {
  val url = options["url"] as? String ?: throw IllegalArgumentException("Http.request: options has no 'url'")
  val headerBuilder = Headers.Builder()
  (options["headers"] as? Map<*, *>)?.forEach { (key, value) ->
    val name = key.toString()
    if (value is Iterable<*>) {
      value.forEach { headerBuilder.add(name, it.toString()) }
    } else {
      value?.let { headerBuilder.add(name, it.toString()) }
    }
  }

  val body = buildBody(options)
  // JS 侧 `Request` 已把方法归一成字符串；Kotlin 侧的调用只带 url，缺省 GET。
  val method = options["method"] as? String ?: if (body == null) "GET" else "POST"
  // okhttp 对「方法 × body」是硬要求：GET/HEAD 不许带，POST/PUT/PATCH 必须带一个。
  val finalBody =
    when {
      method == "GET" || method == "HEAD" -> null
      body != null -> body
      else -> ByteArray(0).toRequestBody(null)
    }
  return Request
    .Builder()
    .url(url)
    .headers(headerBuilder.build())
    .method(method, finalBody)
    .build()
}

private fun buildBody(options: Map<Any, Any?>): RequestBody? {
  val body = options["body"] ?: return null
  if (body is Map<*, *> && body["__js_proto__"] == "FormData") {
    val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
    (body["__items__"] as? Iterable<*>)?.forEach { item ->
      val formItem = item as? Map<*, *> ?: return@forEach
      val name = formItem["name"] as? String ?: return@forEach
      val value = formItem["value"]
      val type = formItem["type"]
      if (type is String && value is ByteArray) {
        // 键名必须与 `init.js` 的 `_formItem` 逐字一致：那边写的是小写 l 的 `filename`。
        // 解析不出的内容类型留空：不该让一个坏 MediaType 把整个请求炸掉。
        multipart.addFormDataPart(name, formItem["filename"] as? String, value.toRequestBody(type.toMediaTypeOrNull()))
      } else {
        multipart.addFormDataPart(name, value.toString())
      }
    }
    return multipart.build()
  }
  return when (body) {
    is ByteArray -> body.toRequestBody(null)
    else -> body.toString().toRequestBody(null)
  }
}
