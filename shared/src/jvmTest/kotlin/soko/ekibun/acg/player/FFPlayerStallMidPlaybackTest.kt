package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.FFPlayer
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「播放到一半断掉」的现场：服务端**发到一半停住不关**（关掉等于告诉客户端 body 结束了，那叫
 * 「截断」不叫「卡住」），看 [FFPlayer] 能不能从 stall 里收场。
 *
 * 与 [HttpAbortTest] 的区别别混：那个卡在**第一个字节之前**（响应头都没给），验的是
 * `AvIO.Handler.abort()` 的 IO 层契约，不走 [FFPlayer]、也碰不到 `getPacket` 外层的
 * `withTimeout`。这里卡在**播到一半**（已经有帧送显了），走的正是取包循环那条路。
 *
 * 冻住之后考察的是这一串：
 * 1. 服务端停笔（[TestMediaServer.served] 停在断点）——确认「网络真的在播放中途停了」；
 * 2. 读作业卡在 [AvFormat.getPacket] 里那次 `getPacketNative` → [HttpIO.read] 的 `runBlocking`
 *    上（[HttpIO.readSuspend] 当前是**无限重试**），那根**单线程** `formatDispatcher`
 *    （`ThreadDispatcher`，`Executors.newSingleThreadExecutor`）正被它 park 住；
 * 3. 但**消费侧不再需要那根线程**：[AvFormat.getPacket] 的 `for (v in channel)` 挂在调用方自己
 *    的线程上，`withPtr` 只包着后台那个读作业（见 [AvFormat.getPacket] 的 KDoc）⇒ 冻结**不会**
 *    把消费侧堵住；
 * 4. 于是 `FFPlayer` 那个取包 `withTimeout` **第一次就生效**（实测：冻结后约 0.1 s 准时触发，
 *    随后**不加退避**地重试 —— 一轮就是 `withTimeout` 本身），
 *    并经 [FFPlayer.onEvent] 上报 [FFPlayer.Event.ReadTimeout]。
 *    **轮次不因此结束** —— `resumeImpl` 那里是 `continue` 而不是 `break`，网络恢复后能接着取包。
 *    `pause()` 仍能返回，但要等当前那个循环轮次走完（实测 7～108 ms）。
 *
 * 读作业**自己不会收场**：[HttpIO.readSuspend] 遇闲置超时是**不限次数**地换会话接着读
 * （真到流尾由 [HttpIO.getResponseBlocking] 返回 null 收场），把「读了多久还没东西」这件事
 * 全权交给上层那个取包超时。分工如此是有意的：抛上去会被 [HttpIO.read] 翻成 IO 错误，
 * 而 aviobuf 会把它毒化成 EOF 状态，播放于是静默地「正常结束」。
 *
 * 第 2 条那个 park 正是 `closeAsync` 自锁的成因：`io.abort()` **必须在读所在线程之外**发出 ——
 * 经 `releaseImpl` 走 `closeDeferred()` 的 `submit { }` 会**排在它要叫醒的读作业后面**、永远
 * 跑不到。所以作废读轮是调用方的责任（见 `AvFormat.releaseImpl` 的 KDoc），[FFPlayer.closeAsync]
 * 在 `playerDispatcher` 上就地调 `resetChannel()`、不排队。
 *
 * 判据有**四条**，缺一条就漏掉一段：
 *
 * 1. **`pause()` 能不能在有限时间内返回** —— [FFPlayer.pause] 会 join 播放轮次，轮次卡在
 *    `getPacket` 上就 join 不回来。这比断言帧数更直接。
 * 2. **`closeAsync()` 能不能在有限时间内返回** —— 它比 `pause()` 多一步：既要结束读作业
 *    （`resetChannel()` → `io.abort()`），又要等 `releaseImpl` 在 `formatDispatcher` 上真的落地。
 *    少了这条断言，上面那个自锁就没人盯着（`pause()` 压根不碰 avformat，它绿着而 `closeAsync` 挂着）。
 * 3. **取包超时有没有经 [FFPlayer.onEvent] 上报** —— 不出声就等于「网络停了」与「播完了」
 *    分不开，上层没机会提示。这条判据盯的是 [FFPlayer.Event.ReadTimeout]。
 * 4. **那条「返回了」是不是真的量在超时上** —— 第 1 条只钉「5 s 内返回」，而 `pause()` 在
 *    **没有**超时进行时同样是毫秒级返回 ⇒ 光有第 1 条的话，测速的那段根本没被触发也照样绿。
 *    所以探测前先断言 [FFPlayer.Event.ReadTimeout] 已经到过（超时进行中的证据），
 *    再对耗时钉一个量级上界（1 s 预算，实测 7～108 ms；机制上界是「一个循环轮次走完」）。
 *
 * **第 1、2 条的探针顺序不能倒**：`closeAsync()` 内部第一件事就是 `pause()`，它返回时 player
 * 已经关掉了 ⇒ 之后再探 `pause()` 只是对已关的 player 置个 flag，**毫秒级返回是必然的、测不到
 * 任何东西**。所以先探 `pause`、后探 `closeAsync`。
 *
 * ## 两条前提，缺一个这个用例就测不准
 *
 * **媒体要 moov 在头部**：用随源码入库的 [TestMedia.BBB_640X360_12S_FASTSTART]（天然
 * faststart）。moov 在**尾部**的媒体经 HttpIO 打开时 [FFPlayer.getStreams] **自己就卡死**，
 * 断流还没开始，测到的是「压根打不开流」而不是 stall。
 *
 * **断点要落在播放期、且剩得下数据**：faststart 素材开流只读前 ~32 KB（索引 `moov` 全在
 * 前 11,381 字节内 ⇒ 32 KB 档位就够 probe 起来，实测 `getStreams()` 8 ms、发 196,608 字节），
 * 所以 443 KB 的体量**足够**支撑 stall 现场 —— 「媒体必须够大」只对碎片化 mp4 成立
 * （每个 `moof` 都要 seek 回去重读索引，开流会反复重读同一区域）。这里不靠媒体够大凑，
 * 靠的是断点（[TestMediaServer.servedLimit]）卡在播放期、且**总长还明显大于已发**。
 *
 * **断点按「服务器一共发出去多少字节」计**：[TestMediaServer] 累计写到 [SERVED_LIMIT] 就停笔、
 * 不 close。为什么用累计字节、上限怎么取，见 [TestMediaServer.servedLimit] 与 [SERVED_LIMIT]
 * 的 KDoc。
 *
 * **播放不能等 [FFPlayer.play] 返回**：这套 [Sink] 下 `play()` 会一路跟着播完整轮才返回
 * （实测同步阻塞 30 s），等它返回就等于「等播放结束」，冻结永远晚一步。所以
 * [startPlayback] 把它丢到独立守护线程。
 *
 * ## 时间预算（别随手加大）
 *
 * 关键是**分段等事件、而不是固定时长死等**。除播放那一段外每段都是秒级返回，只有「真的挂死」
 * 那一支才会把预算吃满：
 *
 * | 段 | 预算 | 依据 |
 * | --- | --- | --- |
 * | 读素材进内存 | 感知不到 | 443 KB，随源码入库 |
 * | 开流 + 等首帧 | 10 s 上限 | 本地环回 + faststart，通常 < 0.1 s；上限只防「压根没播起来」 |
 * | 等断点发满 | 10 s 上限 | 断点由服务器累计量决定，通常 < 0.1 s |
 * | 等取包超时事件 | 8 s 上限，**事件一到就往下走** | 取包 `withTimeout(100ms)` 到点即发事件（实测冻结后 < 0.1 s）。**别**改回「等帧数停滞」：通道里的预读包会让帧继续按墙钟推进约 7 s，那是白等 |
 * | [FFPlayer.pause] 探测 | 5 s 上限，另有 **1 s 量级上界** | 置 `playing = false` 后要等当前那个循环轮次走完（实测 7～108 ms）|
 * | [FFPlayer.closeAsync] 探测（冻住时） | 5 s | 判据之一；自锁没解开时这条会吃满预算 |
 * | closeAsync 收场 | 5 s | 排在 `server.close()` 之后，只为不让构建吊死，不参与判据 |
 *
 * **下界就在播放那一段**：[FFPlayer.resumeImpl] 的主时钟是墙钟（`PTS.now` 走 `monoTimeMs`），
 * 视频帧要等主时钟追上才送显 ⇒ 媒体有多长、墙钟就得走多久，**加速不了**。所以正常一轮
 * 10 s 以内、真的挂死 15 s 左右，这就是这个用例的物理下界。
 *
 * **诊断写文件、不靠 stdout**：Gradle 的 `@Test(timeout)` 超时会杀掉 worker，那时**没 flush 的
 * stdout 会整个丢掉**（实测：一次 300 s 超时的跑，XML 里 `<system-out>` 是空的，完全看不出
 * 卡在哪一步）。所以逐段进度落 `shared/build/stall-diag.log`，每行带毫秒时间戳 —— 报告读不出来时
 * 那个文件还在。
 *
 * 别拿 `system-out` 里的东西当判据：Gradle 自己也会截断它（实测两条用例并存时只留每个用例的
 * 开头几行）。逐段进度只在 `stall-diag.log` 里。
 *
 * 四条判据连同取包超时的事件上报一起是**防回归**的钉子：`pause()` 与 `closeAsync()` 各钉一条
 * 收场路径，缺一个就会漏掉那段自锁。
 */
class FFPlayerStallMidPlaybackTest {
  /**
   * 逐段进度落盘。**不能只 println** —— `@Test(timeout)` 超时会杀掉 worker，没 flush 的 stdout 会
   * 整个丢掉，逐段进度只在 `stall-diag.log` 里（见类 KDoc）。
   *
   * 路径相对**模块目录**（`shared/`），落在 `build/` 下 —— 是构建产物、gitignore，不往源码树里写。
   * 每次跑开头清空，别把上一轮的结果混进来。
   */
  private object Diag {
    private val started = System.currentTimeMillis()
    private val file = File("build/stall-diag.log")

    init {
      file.parentFile?.mkdirs()
      // 先删再写：只 writeText("") 的话，编译失败那一轮压根不会执行到这里，
      // 上一轮的日志会被误读成这一轮的结果。
      runCatching { file.delete() }
      file.writeText("")
    }

    fun log(message: String) {
      val line = "[+%6d ms] %s".format(System.currentTimeMillis() - started, message)
      // appendText 每次开关一次文件：量小（几十行），换来的是「进程被杀也不丢已写的行」。
      runCatching { file.appendText(line + "\n") }
      println(line)
    }
  }

  /**
   * 空播放设备：音频分支按采样数 `delay` 当**声卡节拍**（让播放按真实时长推进），视频只记帧数。
   * 不碰 SDL / 声卡 ⇒ jvmTest 里能跑。
   */
  private fun newSink(): TestSurface = TestSurface(paceAudio = true)

  /**
   * 把一段**可能永不返回**的挂起调用丢到守护线程上跑，[join] 带超时。
   *
   * 超时不算失败（判据由调用方自己判），只把线程栈打到 diag —— 站在报告里能直接看出它卡在哪。
   * 守护线程是必须的：本用例测的就是「可能永久阻塞」，挂在上面会把整个构建吊死。
   */
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

  /**
   * 挂住时的线程快照，**两段**：
   *
   * 1. **摘要** —— 每根线程一行（名字 / 状态 / 帧数 / 栈顶那几帧）。先看这个判断「谁在忙谁在等」，
   *    一屏能装下，本工程所有线程都在里面。
   * 2. **归属线程的完整栈** —— 本工程只有三根归属线程（见下），它们 idle 时栈里**没有任何
   *    `soko.ekibun` 帧**（停在 `LinkedBlockingQueue.take` 上），按内容过滤会把要命的那根筛掉 ——
   *    所以这里**按身份点名**，不靠启发式。
   *
   * [FFPlayer.playerDispatcher] 与 [AvCodec] 的归属线程都是
   * `newSingleThreadExecutor().asCoroutineDispatcher()` ⇒ 名字是默认的 `pool-N-thread-1`，
   * 两根**重名**、无法按名区分。所以摘要里额外标了栈顶帧，让人能认出谁是谁。
   */
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

    // 本工程的三根归属线程：名字是构造参数给的（avformat / avcodec），第三根是默认 pool-N。
    // 「pool-N-thread-1」按名字认不准（playerDispatcher 与本用例的服务器池可能撞名），
    // 所以第三根**不按名挑** —— 池里凡栈顶是 LinkedBlockingQueue.take / ThreadPoolExecutor.getTask
    // 的都是「空闲的单线程池 worker」，一并打出来让人认。
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

  /**
   * 起播放，**不等它播完**。
   *
   * 必须独立的守护线程而不是 `runBlocking` 里直接调：在 [Sink] 这套「按采样数 `delay` 当声卡节拍」
   * 的实现下，[FFPlayer.play] 一路跟着播完整轮才返回（实测 30 s 同步阻塞），挂在测试协程上就等于
   * 「等播放结束才冻结」⇒ 冻结永远晚一步，测不到「播到一半」。
   */
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

  /**
   * 等到 [timeoutEvents] 里出现满足 [predicate] 的事件（上限 [timeoutMs]）。
   *
   * 判据是**事件本身**而不是「帧数停了多久」：取包 `withTimeout(100ms)` 一到点就发
   * [FFPlayer.Event.ReadTimeout]，那一刻就是「已经卡在网络上」的直接证据。
   */
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

  /** 播到一半断掉 ⇒ **收场要能从 stall 里出来**。分段与时间预算见类 KDoc 的表。 */
  @Test(timeout = 90_000)
  fun playbackStalledMidStreamDoesNotHangForever() {
    Diag.log("=== start ===")
    val bytes = Files.readAllBytes(TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART))
    runStallScenario(bytes)
  }

  private fun runStallScenario(bytes: ByteArray) {
    // 断点由**服务器**定，不靠媒体够大（见类 KDoc）：累计写出 [SERVED_LIMIT] 字节后停笔。
    TestMediaServer(bytes, chunkSize = 16 * 1024, servedLimit = SERVED_LIMIT).use { server ->
      val sink = newSink()
      // 只收取包超时事件（帧事件每帧都来，与判据无关）。
      val timeoutEvents: MutableList<FFPlayer.Event> =
        Collections.synchronizedList(ArrayList<FFPlayer.Event>())
      val player =
        FFPlayer(server.url, HttpIO.Handler(), sink) { event ->
          if (event !is FFPlayer.Event.Frame) timeoutEvents.add(event)
        }
      try {
        runBlocking {
          // ---- 第一段：先确认播得起来，否则「后面没反应」分不清是 stall 造成的 ----
          // getStreams() 只读头部的 moov（faststart）。
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

          // ---- 第二段：等服务器发满断点并**等到取包超时真的发生** ----
          // **等**而不是假设「首帧一出现就过了断点」：断点在开流值之上、且播放期累计量是渐涨的。
          // 判据是 [FFPlayer.Event.ReadTimeout]：冻结之后取包 `withTimeout(100ms)` 至多 0.1s 就到点
          // （实测冻结后准时触发）。**别改回「等帧数停滞」** —— 通道里已预读的包要先吃完，
          // 那段时间帧还在按墙钟推进（实测约 7s），等它停是白等。
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

          // ---- 第三段：判据。pause() 会 join 播放轮次，轮次卡在 getPacket 上就 join 不回来 ----
          // **顺序不能倒**：`closeAsync()` 第一件事就是 `pause()`，它返回时 player 已经关掉了 ——
          // 之后再调 `pause()` 只是对已关的 player 置个 flag，**毫秒级返回是必然的、测不到任何东西**
          // ⇒ 先探 pause()、后探 closeAsync()。

          // 探测前先钉住前提：**此刻确实处在取包超时进行中**（第二段已经等到事件了，这里只是
          // 复述一遍）。少了它，下面那条耗时断言就是空的 —— 不在超时中时 pause() 本来就毫秒级返回，
          // 那种返回证明不了任何事。
          assertTrue(
            FFPlayer.Event.ReadTimeout in timeoutEvents,
            "the read-timeout event disappeared -- the premise of this case no longer holds.",
          )
          Diag.log("phase 3: read-timeout event seen before probing pause()")
          Diag.log("phase 3: probing pause() (network still frozen, 5s budget)")
          // 计时在**探针外面**量：probeBounded 自己只返回布尔（两个调用点共用），
          // 改它的签名会牵动 closeAsync 那两处 ⇒ 这里包一层计时，不动它。
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

          // 耗时上界：pause() 的 join 只等「当前那个循环轮次走完」⇒ 机制上界是**一个轮次**，不是
          // 毫秒级也不是无限。给 1 s 预算留足余量（实测 7～108 ms），管的是**量级**不是精确值 ——
          // 加回 delay(100) 会变成 200 ms 上下、仍在预算内，要钉精确值得另写判据。
          assertTrue(
            pauseCostMs < 1_000,
            "pause() took ${pauseCostMs}ms during an in-flight read timeout, over the 1_000ms budget -- " +
              "its upper bound should only be 'wait for the current loop round to finish'. " +
              "A significant slowdown means the read loop gained something (an extra delay? a longer " +
              "withTimeout?) and the round can no longer be wound down promptly. Measured range is " +
              "7-108 ms, not a fixed value.",
          )

          // 取包超时经 [FFPlayer.onEvent] 报上来这件事，上面的前提断言已钉过；这里钉另一半：
          // pause() 返回时**网络仍未恢复**（没有 [FFPlayer.Event.ReadTimeoutResume]）⇒
          // 那条耗时断言量的是「等一个超时轮次走完」，不是「等到网络恢复」。
          assertTrue(
            FFPlayer.Event.ReadTimeoutResume !in timeoutEvents,
            "the network recovered before pause() returned ([FFPlayer.Event.ReadTimeoutResume] arrived) -- " +
              "the timing assertion is not measuring 'waiting for one timeout round to finish'. " +
              "The server cut-off should stay frozen until teardown.",
          )

          // closeAsync 要在**掀服务端之前**探：排到 `server.close()` 之后，挂着的响应体被掀、读作业
          // 自然 unwind ⇒ 那一刻返回 true 只说明「掀得动」。它比 pause() 多一截（结束读作业 + 等
          // `releaseImpl` 落地），而 pause() 不碰 avformat ⇒ 这条是唯一盯着那段自锁的。
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
        // 收场顺序有讲究：**先掀服务端**（parked 的响应体被 close ⇒ 那次读拿到 IOException，
        // 读作业 unwind、通道关闭、播放轮次自然结束），再等 close。
        // 反过来先 close 的话，播放轮次还卡在读里，close 自己就 join 不回来了。
        Diag.log("teardown: taking down the server first")
        server.close()
        // 这里测的就是「可能永不结束」，所以收场也只等得起有限时间：等不到就打印栈放过，
        // 不能把整个构建吊死。
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
    /**
     * 服务器累计发出多少字节就停笔（见 [TestMediaServer.servedLimit]）。
     *
     * 下界**高于开流消耗**（否则连流都打不开，测到的是「压根打不开流」而不是 stall）：
     * 这条 443 KB faststart 素材开流只吃前 ~32 KB（索引 `moov` 全在前 11,381 字节内），
     * 实测 `getStreams()` 8 ms、发 196,608 字节。
     *
     * 上界**明显低于素材总长**（443,429 字节），断点才落在「数据流中途」—— 首帧必定出得来
     * （开流已过），随后累计量越线即停笔。
     *
     * 取 300,000（= 总长的 66%，**还留 34% 没发**）：实测帧数在 t≈7 s 处骤停并上报
     * [FFPlayer.Event.ReadTimeout]，而不限速的对照组同刻仍在推进、一路播到 300 帧。
     *
     * 改媒体就要重按「开流消耗 / 素材总长」重测这个数（见类 KDoc 的「两条前提」）。
     */
    const val SERVED_LIMIT = 300_000L
  }
}
