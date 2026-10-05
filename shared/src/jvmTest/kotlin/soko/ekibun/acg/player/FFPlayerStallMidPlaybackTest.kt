package soko.ekibun.acg.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvPlayback
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.FFPlayer
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
 * 1. 服务端不再发字节（[FreezableServer.served] 不再涨）且**总长大于已发**（数据确实还没发完）——
 *    确认「网络真的在播放中途停了」；
 * 2. 读作业卡在 [AvFormat.getPacket] 里那次 `getPacketNative` → [HttpIO.read] 的 `runBlocking`
 *    上（[HttpIO.readSuspend] 当前是**无限重试**），那根**单线程** `formatDispatcher`
 *    （`ThreadDispatcher`，`Executors.newSingleThreadExecutor`）正被它 park 住；
 * 3. 但**消费侧不再需要那根线程**：[AvFormat.getPacket] 的 `for (v in channel)` 挂在调用方自己
 *    的线程上，`withPtr` 只包着后台那个读作业（见 [AvFormat.getPacket] 的 KDoc）⇒ 冻结**不会**
 *    把消费侧堵住；
 * 4. 于是 `FFPlayer` 那个取包 `withTimeout` **第一次就生效**（实测：冻结后约 0.1 s 准时触发，
 *    随后**不加退避**地重试 —— 一轮就是 `withTimeout` 本身），
 *    并经 [AvPlayback.onEvent] 上报
 *    [AvPlayback.Event.PACKET_READ_TIMEOUT]。
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
 * 3. **取包超时有没有经 [AvPlayback.onEvent] 上报** —— 不出声就等于「网络停了」与「播完了」
 *    分不开，上层没机会提示。这条判据盯的是 [AvPlayback.Event.PACKET_READ_TIMEOUT]。
 * 4. **那条「返回了」是不是真的量在超时上** —— 第 1 条只钉「5 s 内返回」，而 `pause()` 在
 *    **没有**超时进行时同样是毫秒级返回 ⇒ 光有第 1 条的话，测速的那段根本没被触发也照样绿。
 *    所以探测前额外取一次 [AvPlayback.isReadTimeOut] 的**快照**确认前提，再对耗时钉一个量级上界
 *    （1 s 预算，实测 7～108 ms；机制上界是「一个循环轮次走完」）。
 *
 * **第 1、2 条的探针顺序不能倒**：`closeAsync()` 内部第一件事就是 `pause()`，它返回时 player
 * 已经关掉了 ⇒ 之后再探 `pause()` 只是对已关的 player 置个 flag，**毫秒级返回是必然的、测不到
 * 任何东西**。所以先探 `pause`、后探 `closeAsync`。
 *
 * ## 两条前提，缺一个这个用例就测不准
 *
 * **媒体必须自己造**（理由见 [FastStartMedia]）：仓库自带的 `TestMedia.SILENT_BLACK_25S` 只有
 * 46 KB 且 `moov` 在**尾部** —— 用它时 [FFPlayer.getStreams] **自己就卡死了**，冻结还没开始，
 * 测到的是「压根打不开流」而不是 stall。
 *
 * **服务端必须节流**（[FreezableServer.bytesPerSecond]）：环回不节流的话 20 MB 媒体**一瞬间**
 * 就发完了（实测：22 MB 只用了 400 ms，而播放要 30 s）—— 冻结时服务端早已无字节可断，
 * 播放一路走到 EOF，判据全绿而**什么都没测到**。节流到播放消耗速率的两倍，冻结时才能保证
 * 「客户端手里还有数据没播完」。
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
 * | 转封装出媒体 | ffmpeg `-c copy -t 3`，约 0.5 s | 不重新编码，只搬 box 并截前 3 秒 |
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
   * 空播放设备：只记帧数与事件上报，不送显不送声（`flushVideoBuffer` 不碰像素，`flushAudioBuffer`
   * 按采样数 `delay` 当声卡节拍，让播放按真实时长推进）。不碰 SDL / 声卡 ⇒ jvmTest 里能跑。
   *
   * `events` 必须当**构造参数**传进 super（照 [FFPlayerAudioOnlyTest] 同一个坑）：写在 super
   * 实参位置上的 lambda 会隐式捕获还没初始化的 `this`，那一行 `{ events.add(it) }` 在 `events`
   * 还是 null 时就跑。
   */
  private class Sink(
    /** 收到的事件（[AvPlayback.onEvent] 的实参）。播放线程写、测试协程并发读 ⇒ 同步读。 */
    val events: MutableList<AvPlayback.Event> =
      Collections.synchronizedList(ArrayList<AvPlayback.Event>()),
  ) : AvPlayback({ _ -> }, { events.add(it) }) {
    /**
     * 探测那一刻的 [AvPlayback.isReadTimeOut] 快照 —— 用来证明「pause() 是在取包超时进行中返回的」。
     * 由测试在探测前调 [readTimeoutNow] 存进来，不是自己探的。
     *
     * 存**快照**而不是每次现读是有意的：[AvPlayback.isReadTimeOut] **没有**跨线程同步（见它的
     * KDoc），而这里读的是 playerDispatcher 的写侧与测试协程的读侧。当持续性状态来源不行，
     * 当**一次性断言前提**够用 —— 读到非 false 就说明「那一刻超时分支已经跑过了」。
     */
    var readTimedOut: Boolean = false
      private set

    fun readTimeoutNow(): Boolean = isReadTimeOut.also { readTimedOut = it }

    override val sampleRate: Int = 48_000
    override val channels: Int = 2
    override val audioFormat: Int = AvFormat.AV_SAMPLE_FMT_S16

    private val frames = Collections.synchronizedList(ArrayList<Long>())

    override suspend fun flushAudioBuffer(buf: ByteBuffer): Int {
      val samples = buf.remaining() / (channels * 2)
      if (samples > 0) delay((samples * 1000L / sampleRate).coerceAtLeast(1))
      return 0
    }

    override fun flushVideoBuffer(
      buf: ByteBuffer,
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
   * 认 Range（回 206，起点与总长都给全 —— [Http.Response] 的 `offset` / `contentLength` 全靠
   * `Content-Range`），按 [bytesPerSecond] **节流**发；[frozen] 一旦置上，**正在发的那条响应立刻
   * 停笔、后续请求也只发响应头就停住**，且**不 close**（close = body 结束 = 截断，不是 stall）。
   *
   * 用**可缓存线程池**而不是固定池：冻结期间挂住的 handler 会一直占着线程，而 [HttpIO] 的重试
   * 还会发新请求 —— 固定池会被挂死的那些吃光，新请求根本进不来。
   */
  private class FreezableServer(
    private val body: ByteArray,
    /**
     * 发送速率上限（字节/秒）。**不能省**（实测：不节流时 22 MB 在 400 ms 内发完，冻结时无事可断，
     * 用例空跑）。取值取「播放消耗速率的两倍」—— 再慢会拖累开流（`avformat_find_stream_info`
     * 自己要读几 MB），再快则冻结时可能已经发完。
     */
    private val bytesPerSecond: Long,
  ) : AutoCloseable {
    private val pool = Executors.newCachedThreadPool()
    private val stopped = CountDownLatch(1)

    /** 冻结期间攥着的响应体 —— [close] 时统一收掉，别让 handler 线程吊死。 */
    private val parked = Collections.synchronizedList(mutableListOf<OutputStream>())

    /** 收到的请求数（诊断用：冻结期间该涨 —— [HttpIO] 的重试会重发）。 */
    val requestCount = AtomicInteger(0)

    /** 已经真正发出去的字节数（不含响应头）。冻结后它不再涨 = 网络确实停了。 */
    val served = AtomicInteger(0)

    @Volatile
    var frozen: Boolean = false

    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/media.mp4") { handle(it) }
        start()
      }

    val url: String get() = "http://127.0.0.1:${server.address.port}/media.mp4"

    private fun handle(exchange: HttpExchange) {
      requestCount.incrementAndGet()
      val rangeStart =
        exchange.requestHeaders
          .getFirst("Range")
          ?.removePrefix("bytes=")
          ?.substringBefore('-')
          ?.toIntOrNull()
          ?: 0
      val from = rangeStart.coerceIn(0, body.size)
      val status = if (rangeStart > 0) 206 else 200
      if (status == 206) {
        exchange.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
      }
      exchange.sendResponseHeaders(status, (body.size - from).toLong())
      val out = exchange.responseBody
      // 节流基准只按**本条响应**算：每条 range 请求从头限速，客户端感知到的就是一个恒定速率的流。
      val startedAt = System.nanoTime()
      var sentHere = 0L
      try {
        var pos = from
        while (pos < body.size) {
          if (frozen) {
            parked += out
            // 停笔。解冻（frozen 回 false）就接着发；close() 则 countDown 把它放出去。
            while (stopped.count != 0L && frozen) Thread.sleep(20)
            if (stopped.count != 0L) continue
            break
          }
          val n = minOf(16 * 1024, body.size - pos)
          out.write(body, pos, n)
          out.flush()
          served.addAndGet(n)
          sentHere += n
          pos += n
          // 「按已发字节数，现在至少应该发到哪」与真实时间的差 —— 差多少就补睡多少。
          val shouldHaveTakenMs = sentHere * 1000L / bytesPerSecond
          val actuallyTakenMs = (System.nanoTime() - startedAt) / 1_000_000L
          val behind = shouldHaveTakenMs - actuallyTakenMs
          if (behind > 0) Thread.sleep(behind)
        }
      } catch (_: InterruptedException) {
      } catch (_: java.io.IOException) {
        // 客户端自己断了（正常收场路径）
      }
    }

    /** 幂等：测试体与 `use` 各会调一次。 */
    override fun close() {
      stopped.countDown()
      parked.forEach { runCatching { it.close() } }
      server.stop(0)
      pool.shutdownNow()
    }
  }

  /**
   * 把一段**可能永不返回**的挂起调用丢到守护线程上跑，[join] 带超时。
   *
   * 超时不算失败（判据由调用方自己判），只把线程栈打到 diag —— 站在报告里能直接看出它卡在哪。
   * 守护线程是必须的：本用例测的就是「可能永久阻塞」，挂在上面会把整个构建吊死
   * （同 `TestIo.readBounded` 的理由）。
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
    // 前提不满足（缺 ffmpeg / 缺素材）就当跳过：提前 return，不算失败。
    val bytes =
      FastStartMedia.produce()
        ?: run {
          Diag.log("=== 跳过 ===")
          return
        }
    runStallScenario(bytes)
  }

  private fun runStallScenario(bytes: ByteArray) {
    FreezableServer(bytes, bytesPerSecond = 1_500_000L).use { server ->
      val sink = Sink()
      val player = FFPlayer(server.url, HttpIO.Handler(), sink)
      try {
        runBlocking {
          // ---- 第一段：先确认播得起来，否则「后面没反应」分不清是 stall 造成的 ----
          // getStreams() 只读头部的 moov（faststart，见 FastStartMedia），不会自己卡死。
          Diag.log("第一段：getStreams() 开始（faststart，moov 在头部）")
          val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
          Diag.log(
            "第一段：getStreams() 回来，video index=${video.index} ${video.width}x${video.height}；" +
              "已发 ${server.served.get()}/${bytes.size} 字节，请求 ${server.requestCount.get()} 次",
          )

          val playThread = startPlayback(player, mapOf(AVMediaType.VIDEO to video))
          val framesBeforeFreeze = awaitFirstFrame(sink, 10_000)
          Diag.log(
            "第一段：首帧出现，帧数=$framesBeforeFreeze，已发 ${server.served.get()}/${bytes.size} 字节",
          )
          assertTrue(
            framesBeforeFreeze > 0,
            "冻结网络之前就该已经出过一帧（实际 0）—— 这说明用例没测到「播到一半」这个现场。" +
              "服务端已发 ${server.served.get()}/${bytes.size} 字节，请求 ${server.requestCount.get()} 次",
          )

          // ---- 第二段：冻住网络。已经在途的那条响应停笔，之后的请求也只发头就停 ----
          server.frozen = true
          val servedAtFreeze = server.served.get()
          val requestsAtFreeze = server.requestCount.get()
          Diag.log(
            "第二段：已冻结（冻结时已发 $servedAtFreeze/${bytes.size} 字节 / 请求 $requestsAtFreeze 次），" +
              "开始等帧数停滞",
          )
          // 「数据还没发完」是「播放中途」的前提：服务端已发 == 总长说明它早发完了，
          // 冻结冻的是空气，播放会一路走到 EOF（判据全绿而什么都没测到）。
          assertTrue(
            servedAtFreeze < bytes.size,
            "冻结时服务端已经把 $servedAtFreeze/${bytes.size} 字节全发出去了 ⇒ 冻结冻的是空气，" +
              "这个用例没测到「播放中途」。检查 [FreezableServer] 的节流是不是没生效。",
          )

          val (framesAfterFreeze, stalledFor) = awaitFrameStall(sink, flatMs = 1_500, timeoutMs = 8_000)
          Diag.log(
            "第二段：等了 ${stalledFor}ms，帧数 $framesBeforeFreeze -> $framesAfterFreeze；" +
              "服务端已发 ${server.served.get()}/$servedAtFreeze（总长 ${bytes.size}），" +
              "请求数 ${server.requestCount.get()}/$requestsAtFreeze",
          )
          assertTrue(
            server.served.get() - servedAtFreeze < bytes.size / 8,
            "冻结之后服务端还在猛发字节 ⇒ 冻结没生效，这个用例没测到 stall",
          )

          // ---- 第三段：判据。pause() 会 join 播放轮次，轮次卡在 getPacket 上就 join 不回来 ----
          // **顺序不能倒**：`closeAsync()` 第一件事就是 `pause()`，它返回时 player 已经关掉了 ——
          // 之后再调 `pause()` 只是对已关的 player 置个 flag，**毫秒级返回是必然的、测不到任何东西**
          // ⇒ 先探 pause()、后探 closeAsync()。

          // 探测前先钉住前提：**此刻确实处在取包超时进行中**。少了它，下面那条耗时断言
          // 就是空的 —— 不在超时中时 pause() 本来就毫秒级返回，那种返回证明不了任何事。
          // 为什么此刻读得到：帧数已停 >= 1.5 s（awaitFrameStall 的 flatMs），取包
          // withTimeout 早到点、setter 早把 isReadTimeOut 置 true ⇒ 快照必然非 false。
          val timedOutNow = sink.readTimeoutNow()
          Diag.log("第三段：探测 pause() 前置快照 isReadTimeOut=$timedOutNow")
          assertTrue(
            timedOutNow,
            "探测 pause() 的那一刻并不处在取包超时进行中（[AvPlayback.isReadTimeOut] 为 " +
              "false）—— 那样 pause() 本来就毫秒级返回，下面那条耗时断言量到的不是" +
              "「等一个 withTimeout 到点」，这条用例就白测了。冻结后帧数停了 >= 1.5 s，" +
              "取包超时早该到点；为 false 说明 [FFPlayer.resumeImpl] 的超时分支没在跑。",
          )
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

          // 取包超时必须经 [AvPlayback.onEvent] 报上来 —— 这条判据是它的钉子。它成立靠的是
          // **事件本就发生在探测之前**（冻结后还剩几包可出帧，`withTimeout` 随后至多 0.1 s 触发，
          // 而 `awaitFrameStall` 要等满 1.5 s）⇒ 那个 `flatMs` 别调到 1.5 s 以下，会变成偶尔红。
          assertTrue(
            sink.events.contains(AvPlayback.Event.PACKET_READ_TIMEOUT),
            "取包超时后没有收到 [AvPlayback.onEvent] 的 PACKET_READ_TIMEOUT（实际收到 " +
              "${sink.events.toList()}）—— 网络停住与播放结束分不开，" +
              "上层没机会提示「网络停了」。",
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

  /**
   * 本用例专用的媒体：**`moov` 在头部**（faststart）、只截**前 3 秒**、体积仍远大于 avio 缓冲。
   *
   * 三条都是判据的前提，缺一个这个用例就测不准：
   *
   * 1. **必须 faststart**。仓库自带的 [`TestMedia.SILENT_BLACK_25S`] 只有 46 KB，而它的 `moov`
   *    在**文件尾部**（box 扫描：ftyp@0 → mdat@40 → moov@17813）—— `avformat_find_stream_info`
   *    要经 `HttpIO` 的 `seek` 发尾部的 range 请求才能拿到索引 ⇒ 用它时 **`getStreams()` 自己
   *    就卡死**，冻结还没开始，测到的是「压根打不开流」而不是 stall。
   * 2. **必须够大**。`HttpIO.getBufferSize()` 是 32 KB，而 `avformat_find_stream_info` 自己就要读
   *    好几 MB（实测约 3.4 MB）。媒体比它还小的话，冻结之后**开流阶段就已经读完整条流**了，
   *    播放时客户端手里根本没有「还没到的数据」，测不到 stall。
   * 3. **必须短**。播放时长由墙钟决定（[FFPlayer.resumeImpl] 的主时钟走 `monoTimeMs`，视频帧要等
   *    主时钟），**加速不了** ⇒ 媒体多长，这个用例就得等多久。3 秒是「够 stall 显形」与「跑得完」
   *    之间的折中。
   *
   * 生成方式：拿 `.workbuddy/test.mp4`（本机素材，29.8 s / 1308×736 / h264+aac）`-c copy`
   * 加 `-t 3` 截断加 `-movflags +faststart` 转封装。**不重新编码** —— 快，且帧序列与原素材逐帧相同。
   *
   * ffmpeg CLI 与那份素材都是**本机前提**。缺任一个时 [produce] 返回 null，用例**提前 return**
   * 当作跳过 —— 不引 JUnit 的 `Assume`（`jvmTest` 只依赖 `kotlin.test`，见 `shared/build.gradle.kts`），
   * 也不让它红成「代码坏了」。
   */
  private object FastStartMedia {
    /** 前提不满足时返回 null 并记下原因。 */
    fun produce(): ByteArray? {
      val source = findSourceMedia()
      if (source == null) {
        Diag.log("跳过：仓库根下没有 .workbuddy/test.mp4（这个用例靠它生成 faststart 媒体）")
        return null
      }
      val ffmpeg = System.getenv("FFMPEG") ?: findOnPath("ffmpeg")
      if (ffmpeg == null) {
        Diag.log("跳过：PATH 上没有 ffmpeg（可用 FFMPEG 环境变量指到可执行文件）")
        return null
      }
      val out = Files.createTempFile("acg-stall-", ".mp4")
      out.toFile().deleteOnExit()
      val result =
        runCatching {
          val process =
            ProcessBuilder(
              ffmpeg,
              "-y",
              "-hide_banner",
              "-loglevel",
              "error",
              "-i",
              source,
              "-t",
              "3",
              "-c",
              "copy",
              "-movflags",
              "+faststart",
              out.toAbsolutePath().toString(),
            ).redirectErrorStream(true).start()
          val log = process.inputStream.bufferedReader().readText()
          check(process.waitFor() == 0) { "ffmpeg 转封装失败：\n$log" }
        }
      result.exceptionOrNull()?.let {
        Diag.log("跳过：ffmpeg 转封装失败 —— ${it.message}")
        return null
      }
      val bytes = Files.readAllBytes(out)
      // 钉住「moov 在头部」这个前提：faststart 没生效的话，第一条就该是 mdat。
      check(bytes.size > 1024 * 1024) { "faststart 媒体只有 ${bytes.size} 字节，不足以让 stall 显形" }
      check(topLevelBoxTypes(bytes).firstOrNull { it != "ftyp" } == "moov") {
        "faststart 没生效（前几个 box：${topLevelBoxTypes(bytes).take(4)}）"
      }
      Diag.log("媒体就绪：${bytes.size} 字节，顶层 box ${topLevelBoxTypes(bytes).take(4)}")
      return bytes
    }

    /**
     * 从测试的工作目录（Gradle 给的是**模块目录** `shared/`，不是仓库根）向上找
     * `.workbuddy/test.mp4`。找不到返回 null。
     *
     * 别写成相对路径直接 `File(".workbuddy/test.mp4")`（会静默跳过、用例绿得毫无意义）；
     * 判据是**一路走到含有 `settings.gradle.kts` 的那一层**。
     */
    private fun findSourceMedia(): String? {
      var dir: File? = File("").absoluteFile
      while (dir != null) {
        val candidate = File(dir, listOf(".workbuddy", "test.mp4").joinToString(File.separator))
        if (candidate.isFile) return candidate.absolutePath
        if (File(dir, "settings.gradle.kts").isFile) return null
        dir = dir.parentFile
      }
      return null
    }

    /** 在 PATH 上找可执行文件（Windows 逐个 PATHEXT 后缀都试）。找不到返回 null。 */
    private fun findOnPath(name: String): String? {
      val paths = System.getenv("PATH").orEmpty()
      val suffixes =
        if (System.getProperty("os.name").startsWith("Windows")) {
          val pathext = System.getenv("PATHEXT").orEmpty()
          pathext.split(';').filter { it.isNotBlank() }.ifEmpty { listOf(".exe", ".bat", ".cmd") }
        } else {
          listOf("")
        }
      for (dir in paths.split(File.pathSeparatorChar).filter { it.isNotBlank() }) {
        for (suffix in suffixes) {
          val candidate = File(dir, name + suffix)
          if (candidate.isFile) return candidate.absolutePath
        }
      }
      return null
    }

    /** 只查一层，够用来确认 moov / mdat 的相对位置。 */
    private fun topLevelBoxTypes(bytes: ByteArray): List<String> {
      val out = ArrayList<String>()
      val bb = java.nio.ByteBuffer.wrap(bytes)
      bb.order(java.nio.ByteOrder.BIG_ENDIAN)
      var off = 0
      while (off + 8 <= bytes.size && out.size < 8) {
        val size = bb.getInt(off)
        val type = String(bytes, off + 4, 4, Charsets.US_ASCII)
        out += type
        if (size < 8) break
        off += size
      }
      return out
    }
  }
}
