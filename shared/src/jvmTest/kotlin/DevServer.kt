package soko.ekibun

import java.net.BindException
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * dev 媒体服务器：把 [TestMediaServer] 以固定端口跑成独立进程，参数只有 `--port`（默认 8099）。
 *
 * `/media/<路径>` 按分包里的 `media/<路径>` 取（HLS 这类多文件素材就是这么播的）；`/media` 本身发
 * 随源码入库的那份测试视频。想临时换素材，丢进 `shared/src/jvmTest/resources/media/`（或直接丢进
 * `shared/build/processedResources/jvm/test/media/`，后者不用重编），用 `/media/<名字>` 取。
 */
fun main(args: Array<String>) {
  var port = 8099
  var index = 0
  while (index < args.size) {
    when (args[index]) {
      "--port" -> port = args.getOrNull(++index)?.toIntOrNull() ?: error("--port needs a number")
      else -> error("unknown argument: ${args[index]} (available: --port <n>)")
    }
    index++
  }

  val bytes = Files.readAllBytes(TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART))
  println("fallback media: ${TestMedia.BBB_640X360_12S_FASTSTART} (${bytes.size} bytes)")

  val server =
    try {
      TestMediaServer(
        bytes,
        port = port,
        chunkSize = 64 * 1024,
        control = true,
      )
    } catch (_: BindException) {
      System.err.println(
        "port $port is already in use (a devMediaServer may already be running): " +
          "pick another --port, or stop the existing instance first.",
      )
      exitProcess(1)
    }
  val base = server.url.removeSuffix("/media")
  println("serving at: ${server.url}  （/media/<路径> 按分包里的 media/<路径> 取）")
  println("control endpoints: $base/__control/{freeze,resume,throttle?bps=<n>,shutdown}")

  // 常驻直到 Ctrl-C 或 /__control/shutdown
  Thread.currentThread().join()
}
