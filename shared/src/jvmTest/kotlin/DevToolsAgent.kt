package soko.ekibun

import java.awt.AWTEvent
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.io.File
import java.lang.instrument.Instrumentation
import java.net.Socket
import java.net.URLClassLoader
import java.nio.file.Path

/** dev 控制条与 dev 服务器的 premain agent：拉起服务器进程，并在应用主窗口出现后挂上控制条。 */
class DevToolsAgent {
  companion object {
    @JvmStatic
    fun premain(
      args: String?,
      instrumentation: Instrumentation,
    ) {
      val devCp = System.getProperty("acg.dev.classpath") ?: return
      val port = System.getProperty("acg.dev.server.port", "8099")
      spawnServer(port, devCp)
      attachStrip(devCp, port)
    }

    /** 拉起 dev 服务器进程并登记 PID；应用 JVM 正常退出时经 shutdown hook 一并收场。 */
    private fun spawnServer(
      port: String,
      devCp: String,
    ) {
      runCatching {
        val pidFile = File(System.getProperty("acg.dev.pidfile", "dev-media-server.pid"))
        val logFile = File(System.getProperty("acg.dev.logfile", "dev-media-server.log"))
        if (portBusy(port)) {
          pidFile
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.toLongOrNull()
            ?.let { pid -> runCatching { ProcessHandle.of(pid).ifPresent { it.destroy() } } }
          val deadline = System.currentTimeMillis() + 3_000
          while (portBusy(port) && System.currentTimeMillis() < deadline) Thread.sleep(100)
          check(!portBusy(port)) { "port $port is held by another process that is not the previous devMediaServer" }
        }
        pidFile.delete()

        val javaHome = System.getProperty("java.home")
        val javaExe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val javaBin = Path.of(javaHome, "bin", javaExe).toString()
        val command =
          buildList {
            add(javaBin)
            add("-Dstdout.encoding=UTF-8")
            add("-Dstderr.encoding=UTF-8")
            add("-cp")
            add(devCp)
            add("soko.ekibun.DevServerKt")
            add("--port")
            add(port)
            System.getProperty("acg.dev.media")?.let {
              add("--media")
              add(it)
            }
          }
        val process =
          ProcessBuilder(command)
            .directory(File(System.getProperty("acg.dev.workdir", ".")))
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        pidFile.writeText(process.pid().toString())
        println("[dev] dev media server spawned: pid=${process.pid()}")
        // 服务器随应用进程退出一并收场
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { process.destroy() } })
      }.onFailure { println("[dev] dev media server spawn failed: $it") }
    }

    private fun portBusy(port: String): Boolean =
      runCatching {
        Socket("127.0.0.1", port.toInt()).close()
      }.isSuccess

    /** 等应用主窗口出现，经子 ClassLoader 调 `DevStrip.attach(owner, port)`。 */
    private fun attachStrip(
      devCp: String,
      port: String,
    ) {
      val loader =
        URLClassLoader(
          devCp.split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray(),
          DevToolsAgent::class.java.classLoader,
        )
      var attached = false
      // premain 只登记监听就返回
      Toolkit
        .getDefaultToolkit()
        .addAWTEventListener(
          AWTEventListener { event ->
            if (attached || event !is WindowEvent || event.id != WindowEvent.WINDOW_OPENED) return@AWTEventListener
            val owner = event.window as? Frame ?: return@AWTEventListener
            attached = true
            try {
              Class
                .forName("soko.ekibun.DevStrip", true, loader)
                .getMethod("attach", Window::class.java, String::class.java)
                .invoke(null, owner, port)
              println("[dev] control strip attached to \"${owner.title}\"")
            } catch (t: Throwable) {
              println("[dev] control strip failed: $t")
            }
          },
          AWTEvent.WINDOW_EVENT_MASK,
        )
    }
  }
}
