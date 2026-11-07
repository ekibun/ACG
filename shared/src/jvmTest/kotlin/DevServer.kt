package soko.ekibun

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files

/**
 * dev 媒体服务器，起在调用方进程里（bind 0），唯一调用方是 `debugPlay`。没有子进程、PID 文件或端口冲突。
 * `/media/<路径>` 按 classpath 里的 `test-media/` 取文件。
 */
internal fun startDevServer(): TestServer? =
  runCatching {
    val bytes = Files.readAllBytes(TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART))
    println("fallback media: ${TestMedia.BBB_640X360_12S_FASTSTART} (${bytes.size} bytes)")

    lateinit var server: TestServer
    server =
      TestServer(
        bytes,
        chunkSize = 64 * 1024,
        // 路由在构造时登记，回调按名字现取 —— 那时 server 已经赋值
        routes = mapOf("/__control" to { handleControl(it, server) }),
      )
    println("serving at: ${server.mediaUrl}  （/media/<路径> 按分包里的 media/<路径> 取）")
    println("control endpoints: http://127.0.0.1:${server.port}/__control/{freeze,resume,throttle?bps=<n>}")
    server
  }.onFailure { println("[dev] dev media server failed to start: $it") }.getOrNull()

/** 热调端点 `/__control/{freeze,resume,throttle?bps=<n>}`，其余路径回一行状态。没有 shutdown —— 关窗口即退出。 */
private fun handleControl(
  exchange: HttpExchange,
  server: TestServer,
) {
  val uri = exchange.requestURI
  when (uri.path) {
    "/__control/freeze" -> server.frozen = true
    "/__control/resume" -> server.frozen = false
    "/__control/throttle" -> {
      val bps =
        uri.rawQuery
          ?.split('&')
          ?.firstOrNull { it.startsWith("bps=") }
          ?.removePrefix("bps=")
          ?.toLongOrNull()
      if (bps != null) server.bytesPerSecond = bps.takeIf { it > 0 }
    }

    // /__control/status 无动作，落到下面统一回状态文本
  }
  val bpsText = server.bytesPerSecond?.takeIf { it > 0 }?.toString() ?: "unlimited"
  val state = "requests=${server.requests} served=${server.served} frozen=${server.frozen} bps=$bpsText\n"
  val bytes = state.toByteArray(Charsets.UTF_8)
  exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
  exchange.sendResponseHeaders(200, bytes.size.toLong())
  exchange.responseBody.use { it.write(bytes) }
}
