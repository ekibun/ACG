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

/**
 * dev 控制条与 dev 服务器的 **premain agent**（`-javaagent` 挂进 hotRun 的应用进程：
 * 外部 jar + `-D` 传参 + 自建 ClassLoader，应用代码零足迹）。Gradle 侧只剩「把 agent 挂上
 * 应用进程」，两件事都在这里：
 *
 * 1. **拉起 dev 服务器**：用 `-Dacg.dev.classpath`（测试类路径）spawn `DevServerKt` 独立进程；
 *    端口被占先按 PID 文件清残留（自愈——先确认端口被占才动手，PID 复用时不会误伤无辜）；
 *    应用 JVM 正常退出（关窗 / Ctrl-C）时经 shutdown hook 跟着 destroy ——
 *    **服务器与应用进程同生共死**。硬杀 Gradle 客户端时 hook 不跑，服务器残留到下一次
 *    spawn 前被自愈清掉。
 * 2. **挂控制条**：监听 AWT WINDOW_OPENED 等应用主窗口（第一个 [Frame]）出现，把它当
 *    **owner**（AWT 的 owner / 跟随只在同进程内成立）→ 反射调子 ClassLoader 里的
 *    `DevStrip.attach(owner, port)`：无标题栏长条、跟随主窗口移动、经 `/__control` 与服务器对话。
 *
 * 子 ClassLoader 由 `-Dacg.dev.classpath`（测试侧的类与资源）构造 —— 控制条实现住那里，应用
 * 类路径上没有。控制条是 Compose 面板：`androidx.compose.*` 与 skiko 走 **parent-first** 委派、
 * 用的仍是应用自己的那份运行时（版本天然一致），dev 类路径里的 compose jar 只喂编译。
 * `-Dacg.dev.classpath` 不存在时 `premain` 直接返回，零副作用。
 */
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
          check(!portBusy(port)) { "端口 $port 被别的进程占用，且不是上一轮的 devMediaServer" }
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
        // 服务器 = 应用进程的影子：应用正常退出（关窗 / Ctrl-C）时一并收场。
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { process.destroy() } })
      }.onFailure { println("[dev] dev media server spawn failed: $it") }
    }

    private fun portBusy(port: String): Boolean =
      runCatching {
        Socket("127.0.0.1", port.toInt()).close()
      }.isSuccess

    /** 等应用主窗口出现，经子 ClassLoader 调 `DevStrip.attach(owner, port)`（见类 KDoc 第 2 条）。 */
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
      // premain 只登记监听就返回，不拖慢应用启动；主窗口一出现就在 EDT 上挂控制条。
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
