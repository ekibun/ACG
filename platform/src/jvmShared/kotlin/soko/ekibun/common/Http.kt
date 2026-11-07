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
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

// HTTP 传输层：桌面 JVM 与 Android 共用的 OkHttp 实现，拉取式、不装 cookieJar。

private val plainClient: OkHttpClient by lazy {
  val logging =
    HttpLoggingInterceptor().apply {
      level = HttpLoggingInterceptor.Level.BASIC
    }
  OkHttpClient
    .Builder()
    .addInterceptor(logging)
    .readTimeout(Http.SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
}

/** 与 [plainClient] 共享连接池，跟随重定向。 */
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
      call.executeOnIO()
    } catch (e: SocketTimeoutException) {
      // 等响应头超时，按闲置超时处理
      throw Http.IdleTimeoutException()
    }
  return OkHttpResponse(call, rsp)
}

/** 把阻塞的 [Call.execute] 搬到 IO 线程；协程取消时取消这次 call。 */
private suspend fun Call.executeOnIO(): okhttp3.Response =
  withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { cancel() }
      cont.resumeWith(runCatching { execute() })
    }
  }

/** 基于 okhttp 的流式响应。 */
private class OkHttpResponse(
  private val call: Call,
  private val rsp: okhttp3.Response,
) : Http.Response {
  private val stream: InputStream = rsp.body.byteStream()

  /** 这条响应是否已关闭或作废。 */
  @Volatile
  var closed = false
    private set

  /** `Content-Range` 头。 */
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
      onIO {
        try {
          stream.read(buf, off, len)
        } catch (e: SocketTimeoutException) {
          throw Http.IdleTimeoutException()
        }
      }
    // 网络游标按从流里取走的字节数推进
    if (count > 0) networkOffset += count
    return count
  }

  override suspend fun readAll(): ByteArray =
    try {
      onIO { stream.readBytes() }
    } finally {
      close()
    }

  /** 取消 call 并关闭 body；并发调用只有一次生效。 */
  override fun close() {
    if (closed) return
    closed = true
    call.cancel()
    rsp.close()
  }

  /** 把阻塞 I/O 搬到 IO 线程；协程取消时取消这次 call 并标记关闭。 */
  private suspend fun <T> onIO(block: () -> T): T =
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

/** 按 `options` 构造请求。 */
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
  // 方法缺省：有 body 用 POST，无 body 用 GET
  val method = options["method"] as? String ?: if (body == null) "GET" else "POST"
  // okhttp 要求方法与 body 匹配：GET/HEAD 不带，其余必须带一个
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
        // 键名与 `init.js` 的 `_formItem` 一致
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
