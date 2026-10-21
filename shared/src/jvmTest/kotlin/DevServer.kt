package soko.ekibun

import java.net.BindException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * dev 媒体服务器：把 [TestMediaServer] 以固定端口跑成独立进程。参数 `--port`（默认 8099）/ `--media`。
 */
fun main(args: Array<String>) {
  var port = 8099
  var mediaPath: Path? = null
  var index = 0
  while (index < args.size) {
    when (args[index]) {
      "--port" -> port = args.getOrNull(++index)?.toIntOrNull() ?: error("--port needs a number")
      "--media" -> mediaPath = Path.of(args.getOrNull(++index) ?: error("--media needs a path"))
      else -> error("unknown argument: ${args[index]} (available: --port <n> / --media <path>)")
    }
    index++
  }

  val source =
    mediaPath
      ?: TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART).also {
        println("no --media given, using the bundled test media: $it")
      }
  println("media: $source")

  val bytes = Files.readAllBytes(source)
  println("bytes served: ${bytes.size}")

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
  println("serving at: ${server.url}")
  println("control endpoints: $base/__control/{freeze,resume,throttle?bps=<n>,shutdown}")

  // 常驻直到 Ctrl-C 或 /__control/shutdown
  Thread.currentThread().join()
}
