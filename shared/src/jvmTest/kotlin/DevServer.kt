package soko.ekibun

import java.net.BindException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * dev 媒体服务器：把 [TestMediaServer] 以**固定端口**跑成独立进程，给 `hotRun` 的播放调试用 ——
 * PlayScreen 的默认 URL 就是它（`http://127.0.0.1:8099/media`），应用侧零改动。
 * 测试不走本文件：用例各自进程内起 [TestMediaServer]（ephemeral 端口、计数器状态隔离）。
 *
 * ```
 * gradlew :shared:devMediaServer                                   # 默认素材与端口 8099
 * gradlew :shared:devMediaServer --args="--port 9000 --media D:/x.mp4"
 * ```
 *
 * 本进程不带任何窗口：控制条由 [DevToolsAgent] 挂在**应用进程**的主窗口上（[DevStrip]）——
 * AWT 的 owner / 跟随只在同进程内成立，服务器进程够不着应用窗口。播放中途热改行为：
 *
 * - 控制条上的冻结 / 恢复 / 限速按钮。
 * - `curl http://127.0.0.1:8099/__control/freeze`（掐断在途响应与新请求，复现 stall 收场）、
 *   `/resume`、`/throttle?bps=300000`（改限速；`bps=0` 解除）、`/shutdown`（结束本进程，
 *   应用侧播放会断，重启 hotRun 即可），响应是当前状态。
 *
 * 素材按 `--media` 指定，缺省用入库测试媒体（[TestMedia.BBB_640X360_12S_FASTSTART]，经测试
 * classpath 取，永远存在）。通用素材必须 **moov 在头部**：moov 在尾的 mp4 经 HttpIO 打开
 * 可能卡死在 `avformat_find_stream_info`。
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

  // 常驻直到 Ctrl-C / /__control/shutdown：显式挂住主线程，不依赖 HttpServer 线程的 daemon 与否。
  Thread.currentThread().join()
}
