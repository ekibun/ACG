package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.AvSurfaceContext
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
 * 1. 服务端不再发字节（[TestMediaServer.served] 不再涨）且**总长大于已发**（数据确实还没发完）——
 *    确认「网络真的在播放中途停了」；
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
 *    `pause()` 仍能返回，但要等当前那个循环轮次走完（实测 7～108 ms，不是毫秒级）。
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
 *    `getPacket` 上就 join 不回来。这比断言帧数更直接：帧数停了只说明缓冲耗尽，而 `pause()`
 *    返回不了才是真的挂死。
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
 * | 开流 + 等首帧 | 10 s 上限 | 本地环回 + faststart，通常 1～2 s；上限只防「压根没播起来」 |
 * | 等停滞 | 8 s 上限，**连续 1.5 s 不出新帧就提前走** | 这才是「已经卡在网络上」的判据。**不要**改成固定时长死等 |
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
   * 空播放设备：只记帧数，不送显不送声（`flushVideoBuffer` 不碰像素，`flushAudioBuffer`
   * 按采样数 `delay` 当声卡节拍，让播放按真实时长推进）。不碰 SDL / 声卡 ⇒ jvmTest 里能跑。
   */
  private class Sink : AvSurfaceContext() {
    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    private val frames = Collections.synchronizedList(ArrayList<Long>())

    override suspend fun flushAudioBuffer(buf: ByteArray): Int {
      val samples = buf.size / (channels * 2)
      if (samples > 0) delay((samples * 1000L / sampleRate).coerceAtLeast(1))
      return 0
    }

    override suspend fun flushVideoBuffer(
      buf: ByteArray,
      width: Int,
      height: Int,
    ) {
      frames.add(System.nanoTime())
    }

    override suspend fun resume() = Unit

    override suspend fun pause() = Unit

    override suspend fun stop() = Unit

    fun frameCount(): Int = synchronized(frames) { frames.size }
  }

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
          .onFailure { Diag.log("$label 抛了 ${it.javaClass.simpleName}: ${it.message}") }
        done.set(1)
      }.apply {
        isDaemon = true
        name = "probe-$label"
        start()
      }
    thread.join(timeoutMs)
    if (thread.isAlive) {
      Diag.log("$label 在 ${timeoutMs}ms 内没返回。线程快照：\n" + threadSnapshot())
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

    out.append("### 摘要（每根线程一行）\n")
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
        .append("帧] ")
        .append(top)
        .append('\n')
    }

    // 本工程的三根归属线程：名字是构造参数给的（avformat / avcodec），第三根是默认 pool-N。
    // 「pool-N-thread-1」按名字认不准（playerDispatcher 与本用例的服务器池可能撞名），
    // 所以第三根**不按名挑** —— 池里凡栈顶是 LinkedBlockingQueue.take / ThreadPoolExecutor.getTask
    // 的都是「空闲的单线程池 worker」，一并打出来让人认。
    out.append("\n### 归属线程的完整栈\n")
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
      if (stack.size > 20) out.append("    ...（还有 ").append(stack.size - 20).append(" 帧）\n")
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
        .onFailure { Diag.log("play() 抛了 ${it.javaClass.simpleName}: ${it.message}") }
    }.apply {
      isDaemon = true
      name = "probe-play"
      start()
    }

  /**
   * 等到**帧数停滞**：连续 [flatMs] 内没有新帧。
   *
   * 这才是「读作业已经卡在网络上」的判据 —— 固定时长死等是白等（健康时早该走了），而帧数一停
   * 就可以立刻进下一步。
   */
  private suspend fun awaitFrameStall(
    sink: Sink,
    flatMs: Long,
    timeoutMs: Long,
  ): Pair<Int, Long> {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = sink.frameCount()
    var flatSince = System.currentTimeMillis()
    val stallStart = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
      delay(100)
      val now = sink.frameCount()
      if (now != last) {
        Diag.log("  帧数 $last -> $now")
        last = now
        flatSince = System.currentTimeMillis()
      } else if (System.currentTimeMillis() - flatSince >= flatMs) {
        break
      }
    }
    return sink.frameCount() to (System.currentTimeMillis() - stallStart)
  }

  /** 播到一半断掉 ⇒ **收场要能从 stall 里出来**。分段与时间预算见类 KDoc 的表。 */
  @Test(timeout = 90_000)
  fun playbackStalledMidStreamDoesNotHangForever() {
    Diag.log("=== 开跑 ===")
    val bytes = Files.readAllBytes(TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART))
    runStallScenario(bytes)
  }

  private fun runStallScenario(bytes: ByteArray) {
    // 断点由**服务器**定，不靠媒体够大（见类 KDoc）：累计写出 [SERVED_LIMIT] 字节后停笔。
    TestMediaServer(bytes, chunkSize = 16 * 1024, servedLimit = SERVED_LIMIT).use { server ->
      val sink = Sink()
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
          Diag.log("第一段：getStreams() 开始（faststart，moov 在头部）")
          val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
          Diag.log(
            "第一段：getStreams() 回来，video index=${video.index} ${video.width}x${video.height}；" +
              "已发 ${server.served}/${bytes.size} 字节，请求 ${server.requests} 次",
          )

          val playThread = startPlayback(player, mapOf(AVMediaType.VIDEO to video))
          val framesBeforeStop = awaitFirstFrame(sink, 10_000)
          Diag.log(
            "第一段：首帧出现，帧数=$framesBeforeStop，已发 ${server.served}/${bytes.size} 字节",
          )
          assertTrue(
            framesBeforeStop > 0,
            "断流之前就该已经出过一帧（实际 0）—— 这说明用例没测到「播到一半」这个现场。" +
              "服务端已发 ${server.served}/${bytes.size} 字节，请求 ${server.requests} 次",
          )

          // ---- 第二段：等服务器发满断点（首帧出现时它可能还没到）----
          // **等**而不是假设「首帧一出现就过了断点」：断点在开流值之上、且播放期累计量是渐涨的，
          // 两者之间没有固定先后。等它自然越过后再进判据，断点取值就不必卡在某个窄缝里。
          awaitServed(server, SERVED_LIMIT, 10_000)
          val servedAtStop = server.served
          val requestsAtStop = server.requests
          Diag.log(
            "第二段：服务器已停在 $servedAtStop/${bytes.size} 字节（断点 $SERVED_LIMIT）/" +
              "请求 $requestsAtStop 次，开始等帧数停滞",
          )
          assertTrue(
            servedAtStop >= SERVED_LIMIT,
            "等了 10s 服务端也只发到 $servedAtStop 字节，没够断点 $SERVED_LIMIT —— " +
              "这个用例没测到「播到一半」",
          )

          val (framesAfterStop, stalledFor) = awaitFrameStall(sink, flatMs = 1_500, timeoutMs = 8_000)
          Diag.log(
            "第二段：等了 ${stalledFor}ms，帧数 $framesBeforeStop -> $framesAfterStop；" +
              "服务端已发 ${server.served}/$servedAtStop（总长 ${bytes.size}），" +
              "请求数 ${server.requests}/$requestsAtStop",
          )
          assertTrue(
            server.served - servedAtStop < bytes.size / 8,
            "断流之后服务端还在猛发字节（多了 ${server.served - servedAtStop}）⇒ " +
              "servedLimit 没生效，这个用例没测到 stall",
          )

          // ---- 第三段：判据。pause() 会 join 播放轮次，轮次卡在 getPacket 上就 join 不回来 ----
          // **顺序不能倒**：`closeAsync()` 第一件事就是 `pause()`，它返回时 player 已经关掉了 ——
          // 之后再调 `pause()` 只是对已关的 player 置个 flag，**毫秒级返回是必然的、测不到任何东西**
          // ⇒ 先探 pause()、后探 closeAsync()。

          // 探测前先钉住前提：**此刻确实处在取包超时进行中**。少了它，下面那条耗时断言
          // 就是空的 —— 不在超时中时 pause() 本来就毫秒级返回，那种返回证明不了任何事。
          // 为什么此刻必然已到：帧数已停 >= 1.5 s（awaitFrameStall 的 flatMs），取包
          // withTimeout 至多 0.1 s 就到点并发出事件（实测冻结后准时触发）。
          assertTrue(
            FFPlayer.Event.ReadTimeout in timeoutEvents,
            "帧数停滞 1.5 s 后还没收到 [FFPlayer.Event.ReadTimeout] —— 取包超时分支没在跑。" +
              "那样 pause() 本来就毫秒级返回，下面那条耗时断言量到的不是「等一个 withTimeout 到点」，" +
              "这条用例就白测了。",
          )
          Diag.log("第三段：探测 pause() 前已收到取包超时事件")
          Diag.log("第三段：探测 pause()（网络仍冻着，5s 上限）")
          // 计时在**探针外面**量：probeBounded 自己只返回布尔（两个调用点共用），
          // 改它的签名会牵动 closeAsync 那两处 ⇒ 这里包一层计时，不动它。
          val pauseStart = System.currentTimeMillis()
          val pauseReturned = probeBounded("FFPlayer.pause", 5_000) { player.pause() }
          val pauseCostMs = System.currentTimeMillis() - pauseStart
          Diag.log("第三段：pause() 5s 内返回=$pauseReturned，耗时 ${pauseCostMs}ms，此时帧数=${sink.frameCount()}")
          assertTrue(
            pauseReturned,
            "网络在播放中途停住之后，[FFPlayer] 的播放轮次收不回来 —— pause() 5 s 都没返回。" +
              "根因链：读作业卡在 getPacketNative → HttpIO.read 的 runBlocking 上，park 住了" +
              "formatDispatcher 那根单线程（ThreadDispatcher 是 newSingleThreadExecutor，纯排队）。" +
              "取包超时能不能救回来，取决于消费侧要不要跟那根线程抢：AvFormat.getPacket 的" +
              "`for (v in channel)` 挂在调用方自己线程上，withPtr 只包着后台读作业 ⇒ 抢不到，" +
              "于是 withTimeout 第一次就生效。",
          )

          // 耗时上界：pause() 的 join 只等「当前那个循环轮次走完」⇒ 机制上界是**一个轮次**，不是
          // 毫秒级也不是无限。给 1 s 预算留足余量（实测 7～108 ms），管的是**量级**不是精确值 ——
          // 加回 delay(100) 会变成 200 ms 上下、仍在预算内，要钉精确值得另写判据。
          assertTrue(
            pauseCostMs < 1_000,
            "pause() 在取包超时进行中耗时 ${pauseCostMs}ms，超了 1_000ms 预算 —— " +
              "它的上界应该只是「等当前那个循环轮次走完」。显著变慢说明" +
              "取包循环里多了别的东西（额外 delay？更长的 withTimeout？），轮次不再能被" +
              "及时收回来。实测是个跨度（7～108 ms），不是定值。",
          )

          // 取包超时经 [FFPlayer.onEvent] 报上来这件事，上面的前提断言已钉过；这里钉另一半：
          // pause() 返回时**网络仍未恢复**（没有 [FFPlayer.Event.ReadTimeoutResume]）⇒
          // 那条耗时断言量的是「等一个超时轮次走完」，不是「等到网络恢复」。
          assertTrue(
            FFPlayer.Event.ReadTimeoutResume !in timeoutEvents,
            "pause() 返回之前网络已恢复（收到了 [FFPlayer.Event.ReadTimeoutResume]）⇒ " +
              "耗时断言量到的不是「等一个超时轮次走完」。断点应由服务器一直冻到收场。",
          )

          // closeAsync 要在**掀服务端之前**探：排到 `server.close()` 之后，挂着的响应体被掀、读作业
          // 自然 unwind ⇒ 那一刻返回 true 只说明「掀得动」。它比 pause() 多一截（结束读作业 + 等
          // `releaseImpl` 落地），而 pause() 不碰 avformat ⇒ 这条是唯一盯着那段自锁的。
          Diag.log("第三段：探测 closeAsync 之前的线程快照：\n" + threadSnapshot())
          Diag.log("第三段：探测 closeAsync（网络仍冻着，5s 上限）")
          val closeWhileStalled = probeBounded("FFPlayer.closeAsync(冻住时)", 5_000) { player.closeAsync() }
          Diag.log("第三段：closeAsync 在冻住时 5s 内返回=$closeWhileStalled")
          assertTrue(
            closeWhileStalled,
            "网络在播放中途停住之后，closeAsync() 5 s 都没返回 —— 关不掉一个已经没网络的流。" +
              "根因链：作废读轮的 io.abort() 若在 formatDispatcher 之内调（或经 closeDeferred() 的" +
              "submit{} 排进它的队列），就排在读作业的 HttpIO.read 的 runBlocking 后面 ——" +
              "ThreadDispatcher 是 newSingleThreadExecutor、纯排队，没有 event loop 能借道。" +
              "解法是让 resetChannel() 在 playerDispatcher 上就地发出 abort、不排队，" +
              "并且不能靠 releaseImpl 兜底（它也排在同一根线程后面）。",
          )
          playThread.interrupt()
        }
      } finally {
        // 收场顺序有讲究：**先掀服务端**（parked 的响应体被 close ⇒ 那次读拿到 IOException，
        // 读作业 unwind、通道关闭、播放轮次自然结束），再等 close。
        // 反过来先 close 的话，播放轮次还卡在读里，close 自己就 join 不回来了。
        Diag.log("收场：先掀服务端")
        server.close()
        // 这里测的就是「可能永不结束」，所以收场也只等得起有限时间：等不到就打印栈放过，
        // 不能把整个构建吊死。
        Diag.log("收场：探测 closeAsync（5s 上限）")
        val closed = probeBounded("FFPlayer.closeAsync", 5_000) { player.closeAsync() }
        Diag.log("收场：closeAsync 5s 内返回=$closed")
      }
    }
    Diag.log("=== 跑完 ===")
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
    sink: Sink,
    timeoutMs: Long,
  ): Int {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (sink.frameCount() == 0 && System.currentTimeMillis() < deadline) {
      delay(50)
    }
    return sink.frameCount()
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
