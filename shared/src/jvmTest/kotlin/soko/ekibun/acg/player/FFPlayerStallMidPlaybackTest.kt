package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.FFPlayer
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 播放到一半断掉：`pause()` / `closeAsync()` 都在有限时间内返回，取包超时经 `onEvent` 上报。见 `docs/development/playback-invariants.md` 第五节。
 */
class FFPlayerStallMidPlaybackTest {
  /** 逐段进度落盘到 `build/stall-diag.log`。 */
  private object Diag {
    private val started = System.currentTimeMillis()
    private val file = File("build/stall-diag.log")

    init {
      file.parentFile?.mkdirs()
      // 先删再写，避免上一轮日志混进来
      runCatching { file.delete() }
      file.writeText("")
    }

    fun log(message: String) {
      val line = "[+%6d ms] %s".format(System.currentTimeMillis() - started, message)
      // appendText 每次开关一次文件：进程被杀也不丢已写的行
      runCatching { file.appendText(line + "\n") }
      println(line)
    }
  }

  /** 空播放设备：音频按采样数 `delay` 当声卡节拍，视频只记帧数。 */
  private fun newSink(): TestSurface = TestSurface(paceAudio = true)

  /** 丢到守护线程上跑一次挂起调用，[join] 带超时；超时只打线程栈并返回 false。 */
  private fun probeBounded(
    label: String,
    timeoutMs: Long,
    block: suspend () -> Unit,
  ): Boolean {
    val done = AtomicInteger(0)
    val thread =
      Thread {
        runCatching { runBlocking { block() } }
          .onFailure { Diag.log("$label threw ${it.javaClass.simpleName}: ${it.message}") }
        done.set(1)
      }.apply {
        isDaemon = true
        name = "probe-$label"
        start()
      }
    thread.join(timeoutMs)
    if (thread.isAlive) {
      Diag.log("$label did not return within ${timeoutMs}ms. Thread snapshot:\n" + threadSnapshot())
      return false
    }
    return done.get() == 1
  }

  /** 挂住时的线程快照：每根线程一行的摘要 + 归属线程的完整栈。 */
  private fun threadSnapshot(): String {
    val traces = Thread.getAllStackTraces().filterKeys { !it.name.startsWith("DestroyJavaVM") }
    val out = StringBuilder()

    out.append("### summary (one line per thread)\n")
    traces.forEach { (t, stack) ->
      val top =
        stack.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
      out
        .append("  ")
        .append(t.name)
        .append(" [")
        .append(t.state)
        .append(", ")
        .append(stack.size)
        .append(" frames] ")
        .append(top)
        .append('\n')
    }

    // 第三根归属线程（默认 pool-N）不按名挑，按空闲单线程池 worker 的栈顶认出来
    out.append("\n### full stack of owner threads\n")
    traces.forEach { (t, stack) ->
      val named = t.name == "avformat" || t.name == "avcodec"
      val idlePoolWorker =
        stack.any {
          it.className.contains("LinkedBlockingQueue") || it.className.contains("ThreadPoolExecutor")
        }
      if (!named && !idlePoolWorker) return@forEach
      out
        .append("--- ")
        .append(t.name)
        .append(" (")
        .append(t.state)
        .append(") ---\n")
      stack.take(20).forEach { out.append("    ").append(it).append('\n') }
      if (stack.size > 20) out.append("    ... (").append(stack.size - 20).append(" more frames)\n")
    }
    return out.toString()
  }

  /** 起播放，不等它播完（独立守护线程）。 */
  private fun startPlayback(
    player: FFPlayer,
    streams: Map<Int, AvStream>,
  ): Thread =
    Thread {
      runCatching { runBlocking { player.play(streams) } }
        .onFailure { Diag.log("play() threw ${it.javaClass.simpleName}: ${it.message}") }
    }.apply {
      isDaemon = true
      name = "probe-play"
      start()
    }

  /** 等到 [timeoutEvents] 里出现满足 [predicate] 的事件（上限 [timeoutMs]）。 */
  private suspend fun awaitEvent(
    events: List<FFPlayer.Event>,
    timeoutMs: Long = 8_000,
    predicate: (FFPlayer.Event) -> Boolean,
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (synchronized(events) { events.any(predicate) }) return true
      delay(20)
    }
    return synchronized(events) { events.any(predicate) }
  }

  /** 播到一半断掉 ⇒ 收场要能从 stall 里出来。 */
  @Test(timeout = 90_000)
  fun playbackStalledMidStreamDoesNotHangForever() {
    Diag.log("=== start ===")
    val bytes = Files.readAllBytes(TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART))
    runStallScenario(bytes)
  }

  private fun runStallScenario(bytes: ByteArray) {
    // 断点由服务器定：累计写出 SERVED_LIMIT 字节后停笔
    TestMediaServer(bytes, chunkSize = 16 * 1024, servedLimit = SERVED_LIMIT).use { server ->
      val sink = newSink()
      // 只收非帧事件（帧事件每帧都来，与判据无关）
      val timeoutEvents: MutableList<FFPlayer.Event> =
        Collections.synchronizedList(ArrayList<FFPlayer.Event>())
      val player =
        FFPlayer(server.url, HttpIO.Handler(), sink) { event ->
          if (event !is FFPlayer.Event.Frame) timeoutEvents.add(event)
        }
      try {
        runBlocking {
          // ---- 第一段：先确认播得起来 ----
          Diag.log("phase 1: getStreams() starting (faststart, moov at head)")
          val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
          Diag.log(
            "phase 1: getStreams() returned, video index=${video.index} ${video.width}x${video.height}; " +
              "served ${server.served}/${bytes.size} bytes, ${server.requests} requests",
          )

          val playThread = startPlayback(player, mapOf(AVMediaType.VIDEO to video))
          val framesBeforeStop = awaitFirstFrame(sink, 10_000)
          Diag.log(
            "phase 1: first frame shown, frameCount=$framesBeforeStop, served ${server.served}/${bytes.size} bytes",
          )
          assertTrue(
            framesBeforeStop > 0,
            "a frame should have been shown before the stream stalled (got 0) -- " +
              "this case did not reach the mid-playback state. " +
              "served ${server.served}/${bytes.size} bytes, ${server.requests} requests",
          )

          // ---- 第二段：等服务器发满断点，并等到取包超时真的发生 ----
          // 判据是 [FFPlayer.Event.ReadTimeout]，不是「帧数停滞」
          awaitServed(server, SERVED_LIMIT, 10_000)
          val servedAtStop = server.served
          val requestsAtStop = server.requests
          val timeoutSeen = timeoutEvents.count { it is FFPlayer.Event.ReadTimeout }
          Diag.log(
            "phase 2: server stopped at $servedAtStop/${bytes.size} bytes (servedLimit $SERVED_LIMIT)/" +
              "requests $requestsAtStop, read-timeout events=$timeoutSeen",
          )
          assertTrue(
            awaitEvent(timeoutEvents) { it is FFPlayer.Event.ReadTimeout },
            "no [FFPlayer.Event.ReadTimeout] within 8s after the cut-off -- the read-timeout branch is " +
              "not running, so the timing assertion below would not measure 'waiting for one withTimeout to fire'. " +
              "This case would be testing nothing.",
          )
          Diag.log(
            "phase 2: read-timeout event received, frames=${sink.frameCount}, served ${server.served}/$servedAtStop",
          )

          // ---- 第三段：判据。顺序不能倒：closeAsync() 第一件事就是 pause() ----

          // 探测前先钉住前提：此刻确实处在取包超时进行中
          assertTrue(
            FFPlayer.Event.ReadTimeout in timeoutEvents,
            "the read-timeout event disappeared -- the premise of this case no longer holds.",
          )
          Diag.log("phase 3: read-timeout event seen before probing pause()")
          Diag.log("phase 3: probing pause() (network still frozen, 5s budget)")
          // 计时在探针外面量
          val pauseStart = System.currentTimeMillis()
          val pauseReturned = probeBounded("FFPlayer.pause", 5_000) { player.pause() }
          val pauseCostMs = System.currentTimeMillis() - pauseStart
          Diag.log("phase 3: pause() returned=$pauseReturned, took ${pauseCostMs}ms, frames=${sink.frameCount}")
          assertTrue(
            pauseReturned,
            "[FFPlayer] could not wind down its playback round after the network stalled mid-playback -- " +
              "pause() did not return within 5s. Root cause chain: the read job is parked in " +
              "getPacketNative -> HttpIO.read's runBlocking, blocking the single-threaded formatDispatcher " +
              "(ThreadDispatcher is newSingleThreadExecutor, pure queueing). Whether the read timeout can " +
              "save it depends on the consumer competing for that thread: AvFormat.getPacket's " +
              "`for (v in channel)` runs on the caller's own thread and withPtr only wraps the background " +
              "read job, so there is no contention and the first withTimeout fires.",
          )

          // 耗时上界：pause() 的 join 只等当前轮次走完，给 1 s 预算
          assertTrue(
            pauseCostMs < 1_000,
            "pause() took ${pauseCostMs}ms during an in-flight read timeout, over the 1_000ms budget -- " +
              "its upper bound should only be 'wait for the current loop round to finish'. " +
              "A significant slowdown means the read loop gained something (an extra delay? a longer " +
              "withTimeout?) and the round can no longer be wound down promptly. Measured range is " +
              "7-108 ms, not a fixed value.",
          )

          // pause() 返回时网络仍未恢复（没有 ReadTimeoutResume）
          assertTrue(
            FFPlayer.Event.ReadTimeoutResume !in timeoutEvents,
            "the network recovered before pause() returned ([FFPlayer.Event.ReadTimeoutResume] arrived) -- " +
              "the timing assertion is not measuring 'waiting for one timeout round to finish'. " +
              "The server cut-off should stay frozen until teardown.",
          )

          // closeAsync 要在掀服务端之前探
          Diag.log("phase 3: thread snapshot before probing closeAsync:\n" + threadSnapshot())
          Diag.log("phase 3: probing closeAsync (network still frozen, 5s budget)")
          val closeWhileStalled = probeBounded("FFPlayer.closeAsync(stalled)", 5_000) { player.closeAsync() }
          Diag.log("phase 3: closeAsync returned within 5s while stalled=$closeWhileStalled")
          assertTrue(
            closeWhileStalled,
            "closeAsync() did not return within 5s after the network stalled mid-playback -- cannot close " +
              "a stream that has already lost its network. Root cause chain: if the round-invalidating " +
              "io.abort() is issued inside formatDispatcher (or queued through closeDeferred()'s " +
              "submit{}), it lands behind the read job's HttpIO.read runBlocking -- ThreadDispatcher is " +
              "newSingleThreadExecutor with pure queueing and no event loop to borrow. The fix is for " +
              "resetChannel() to abort in place on playerDispatcher without queueing, and it cannot rely " +
              "on releaseImpl as a fallback (that queues behind the same thread too).",
          )
          playThread.interrupt()
        }
      } finally {
        // 收场顺序：先掀服务端，再等 close
        Diag.log("teardown: taking down the server first")
        server.close()
        // 收场也只等有限时间：等不到就打印栈放过
        Diag.log("teardown: probing closeAsync (5s budget)")
        val closed = probeBounded("FFPlayer.closeAsync", 5_000) { player.closeAsync() }
        Diag.log("teardown: closeAsync returned within 5s=$closed")
      }
    }
    Diag.log("=== done ===")
  }

  /** 等服务器累计发出到 [target] 字节（上限 [timeoutMs]）—— 断点生效的时点不确定，等它到。 */
  private suspend fun awaitServed(
    server: TestMediaServer,
    target: Long,
    timeoutMs: Long,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (server.served < target && System.currentTimeMillis() < deadline) {
      delay(20)
    }
  }

  /** 只等第一帧（上限 [timeoutMs]）：区分「压根没播起来」与「播到一半才卡」。 */
  private suspend fun awaitFirstFrame(
    sink: TestSurface,
    timeoutMs: Long,
  ): Int {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (sink.frameCount == 0 && System.currentTimeMillis() < deadline) {
      delay(50)
    }
    return sink.frameCount
  }

  private companion object {
    /** 服务器累计发出多少字节就停笔：下界高于开流消耗，上界明显低于素材总长（443,429 字节）。 */
    const val SERVED_LIMIT = 300_000L
  }
}
