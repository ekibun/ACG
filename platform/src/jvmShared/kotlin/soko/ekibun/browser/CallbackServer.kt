package soko.ekibun.browser

import kotlinx.coroutines.CompletableDeferred
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// JVM 族（Android + 桌面）共用的回环回调服务器。
//
// 契约（[CallbackSession]）在 commonMain，编排（占位符替换、返回值形状）在 :shared 的
// JsEngine；这里只落地「内建服务器」这一件事：一个常驻 `ServerSocket`，会话落在 URL 路径上。
//
// 服务器是**进程级单例**，不是每个会话一个：端口由 OS 分配（`bind(0)`），要拿到端口就得
// 先有 socket，而每次登录都新建端口会把「地址」变成一次性的东西 —— 复用一个服务器、
// 用路径区分会话，才谈得上「由 URL 定位 session」。

/** 会话地址的 host。只绑回环：同网段的别人不该能替用户完成登录。 */
private const val LOOPBACK_HOST = "127.0.0.1"

/** `accept` 的排队上限。够浏览器预连 + 本次跳转的量即可。 */
private const val BACKLOG = 8

/** 一条连接上等请求行的上限。浏览器会预连 socket 却不发数据，超时即关，不占线程。 */
private const val CONNECTION_IDLE_TIMEOUT_MS = 30_000

/** 请求行（到 LF 为止）的长度上限，防对端灌一段不进内存。 */
private const val MAX_REQUEST_LINE_BYTES = 8 * 1024

/** 会话 id 的随机字节数（转成十六进制后 24 个字符）。 */
private const val SESSION_ID_BYTES = 12

private const val HEX_DIGITS = "0123456789abcdef"

private object CallbackServer {
  private val random = SecureRandom()
  private val sessions = ConcurrentHashMap<String, CompletableDeferred<String>>()
  private val connectionIndex = AtomicInteger()

  private val connections: ExecutorService =
    Executors.newCachedThreadPool { runnable ->
      Thread(runnable, "callback-conn-${connectionIndex.incrementAndGet()}").apply { isDaemon = true }
    }

  private val server: ServerSocket by lazy { startServer() }

  /** 本服务器的 origin，即会话地址去掉路径的那截。 */
  private val origin: String get() = "http://$LOOPBACK_HOST:${server.localPort}"

  fun newSession(): CallbackSession {
    // 先让服务器起来（[origin] 那一下才会建 socket）：它失败时不该在表里留下一条
    // 永远等不到回调的会话。
    val baseUrl = origin
    val sessionId = newSessionId()
    val result = CompletableDeferred<String>()
    sessions[sessionId] = result
    return LoopbackCallbackSession("$baseUrl/$sessionId", sessionId, result)
  }

  /** 撤掉会话：之后的请求按 404 处理。重复撤是无操作。 */
  fun forget(sessionId: String) {
    sessions.remove(sessionId)
  }

  private fun startServer(): ServerSocket {
    val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
    Thread({ acceptLoop(socket) }, "callback-accept").apply { isDaemon = true }.start()
    return socket
  }

  private fun acceptLoop(socket: ServerSocket) {
    while (true) {
      val client =
        try {
          socket.accept()
        } catch (_: IOException) {
          // 这个服务器是进程级的：accept 失败就没有后续了，循环退出。
          return
        }
      connections.execute { serve(client) }
    }
  }

  private fun serve(client: Socket) {
    try {
      client.use { socket ->
        socket.soTimeout = CONNECTION_IDLE_TIMEOUT_MS
        val requestLine =
          readRequestLine(BufferedInputStream(socket.getInputStream())) ?: return
        // origin-form：`GET /<会话id>?code=… HTTP/1.1`。绝对形式（经代理）不处理 ——
        // 回环地址在浏览器里默认不过代理，那种请求到不了这里。
        val target = requestLine.split(' ').getOrNull(1) ?: return
        val result = sessions[target.substringBefore('?').removePrefix("/")]
        if (result == null) {
          // 浏览器还会来取 /favicon.ico，插件的其它请求也可能落到这个端口上。
          respond(socket, "404 Not Found", NOT_FOUND_PAGE)
        } else {
          // 结果里的 target 是**原样**的：不解码交给插件按段解（见 [CallbackSession.awaitCallback]）。
          // 重复打中同一个地址时 complete 幂等，第二次只是再回一次页面。
          result.complete("$origin$target")
          respond(socket, "200 OK", FINISH_PAGE)
        }
      }
    } catch (_: IOException) {
      // 单条连接出问题（对端提前断开之类）不影响会话本身。
    }
  }

  private fun readRequestLine(input: InputStream): String? {
    val line = ByteArrayOutputStream()
    while (line.size() < MAX_REQUEST_LINE_BYTES) {
      val byte = input.read()
      if (byte < 0) return null
      if (byte == '\n'.code) return line.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
      line.write(byte)
    }
    return null
  }

  private fun respond(
    socket: Socket,
    status: String,
    body: String,
  ) {
    val bytes = body.encodeToByteArray()
    val head =
      "HTTP/1.1 $status\r\n" +
        "Content-Type: text/html; charset=utf-8\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        // 回调页没有任何留存价值，地址里还带着授权码。
        "Cache-Control: no-store\r\n" +
        "Connection: close\r\n\r\n"
    val out = socket.getOutputStream()
    out.write(head.toByteArray(Charsets.US_ASCII))
    out.write(bytes)
    out.flush()
  }

  private fun newSessionId(): String {
    val bytes = ByteArray(SESSION_ID_BYTES)
    random.nextBytes(bytes)
    return buildString(SESSION_ID_BYTES * 2) {
      bytes.forEach { byte ->
        val code = byte.toInt() and 0xFF
        append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0xF])
      }
    }
  }
}

private class LoopbackCallbackSession(
  override val callbackUrl: String,
  private val sessionId: String,
  private val result: CompletableDeferred<String>,
) : CallbackSession {
  override suspend fun awaitCallback(): String = result.await()

  override fun close() {
    CallbackServer.forget(sessionId)
    result.cancel()
  }
}

actual fun createCallbackSession(): CallbackSession = CallbackServer.newSession()

/** 回调兑现后给用户看的那一屏。`window.close()` 只对脚本开的窗口有效 ——
 *  Custom Tabs 里是空操作，所以提示语才是真的收尾手段。 */
private val FINISH_PAGE =
  """
  <!doctype html>
  <html lang="zh">
  <head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>登录完成</title>
  </head>
  <body style="margin:15vh 1.5rem 0;font:16px/1.7 system-ui,sans-serif;text-align:center">
  <p style="font-size:1.3em">登录完成</p>
  <p>授权结果已送回应用，可以关闭此页面。</p>
  <script>window.close()</script>
  </body>
  </html>
  """.trimIndent()

/** 不是会话路径的回显（`/favicon.ico` 之类）。 */
private val NOT_FOUND_PAGE =
  """
  <!doctype html>
  <html lang="zh">
  <head>
  <meta charset="utf-8">
  <title>没有这个回调会话</title>
  </head>
  <body style="margin:15vh 1.5rem 0;font:16px/1.7 system-ui,sans-serif;text-align:center">
  <p>这不是一个有效的登录回调地址。</p>
  </body>
  </html>
  """.trimIndent()
