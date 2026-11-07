package soko.ekibun.ffmpeg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `FFPlayer` 的整条播放面：起播收场、连点 seek、跳转与步进的帧序列、断流收场、HLS 广告跳过。
 * 判据见 `docs/development/playback-invariants.md`，广告部分见 `docs/design/hls-ad-skip.md`。
 */
class FFPlayerTest {
  private companion object {
    const val MEDIA = TestMedia.BBB_640X360_12S_FASTSTART

    /** IO 累计交出多少字节就断流：下界高于开流消耗，上界明显低于素材总长（432,897 字节）。 */
    const val SERVED_LIMIT = 300_000L
  }

  @Test(timeout = 120_000)
  fun playbackRunsThroughToTeardown() =
    runBlocking {
      val media = TestMedia.file(MEDIA)
      val playback = TestSurface(rate = 44_100)
      // 收集在闭包里：列表只在收场之后读，播放中只看原子量
      val frames = ArrayList<Long?>()
      val shown = AtomicInteger()
      val ended = AtomicBoolean(false)
      val onEvent: (FFPlayer.Event) -> Unit = { ev ->
        if (ev is FFPlayer.Event.Frame) {
          frames += ev.pts
          if (ev.pts == null) ended.set(true) else shown.incrementAndGet()
        }
      }

      // 段一：带视频轨。play() 挂到整轮结束，所以只 launch 不 await。
      val withVideo = FFPlayer(media.absolutePath, TestFileIO(media), playback, onEvent = onEvent)
      try {
        val video = withVideo.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // 从片尾前 1 秒起播；duration 是 AV_TIME_BASE 微秒
        val start = maxOf(video.duration - 1_000_000, 0)
        val playJob = launch { withVideo.play(mapOf(AVMediaType.VIDEO to video), start) }

        // ① 片尾以 null PTS 收场
        withTimeout(30_000) {
          while (!ended.get()) delay(10)
        }
        // ② 本轮自己收干净：play() 返回
        withTimeout(20_000) { playJob.join() }
        // ③ 收干净之后 pause 仍能返回（轮次挂死的话这里会卡住整个播放器）
        withTimeout(5_000) { withVideo.pause() }

        assertTrue(
          shown.get() > 0,
          "a frame should have been shown before the EOF teardown, got frames=${frames.take(5)}...",
        )
      } finally {
        // withTimeoutOrNull：本用例失败时不许拖住整个测试进程
        withTimeoutOrNull(10_000) { withVideo.closeAsync() }
      }

      // 段二：只有音频轨。没有视频就没有「下一帧画面」，收场只能靠 EOF。
      frames.clear()
      shown.set(0)
      ended.set(false)
      val audioBefore = playback.audioFrameCount
      val audioOnly = FFPlayer(media.absolutePath, TestFileIO(media), playback, onEvent = onEvent)
      try {
        val audio = audioOnly.getStreams().first { it.codecType == AVMediaType.AUDIO }
        val playJob = launch { audioOnly.play(mapOf(AVMediaType.AUDIO to audio), 0) }

        withTimeout(30_000) {
          while (!ended.get()) delay(10)
        }
        withTimeout(20_000) { playJob.join() }

        assertTrue(
          playback.audioFrameCount > audioBefore,
          "audio frames must reach the platform side, got ${playback.audioFrameCount - audioBefore}",
        )
      } finally {
        withTimeoutOrNull(10_000) { audioOnly.closeAsync() }
      }
    }

  /** seek 之后 [windowMs] 内新收到的帧数；够了就提前返回。只读原子计数：这条用例不能 pause。 */
  private suspend fun framesAfterSeek(
    scope: CoroutineScope,
    player: FFPlayer,
    shown: AtomicInteger,
    lastPts: AtomicLong,
    target: Long,
    secondGapMs: Long,
    windowMs: Long = 2_500,
  ): Int {
    val base = shown.get()
    // UI 的写法：不等它返回（`seekTo` 要等自己那一轮结束）
    scope.launch { player.seekTo(target) }
    if (secondGapMs > 0) {
      delay(secondGapMs)
      scope.launch { player.seekTo(target + 1_000_000) }
    }
    withTimeoutOrNull(windowMs) {
      while (shown.get() - base <= 40) {
        delay(5)
      }
    }
    return shown.get() - base
  }

  @Test(timeout = 180_000)
  fun backToBackSeeksKeepPlaying() =
    runBlocking {
      val shown = AtomicInteger()
      val lastPts = AtomicLong(0)
      val media = TestMedia.file(TestMedia.HLS_AD_PLAYLIST)
      val sink = TestSurface()
      val player =
        openPlayer(sink, media = media, onEvent = { ev ->
          if (ev is FFPlayer.Event.Frame) {
            ev.pts?.let {
              shown.incrementAndGet()
              lastPts.set(it)
            }
          }
        })
      try {
        val streams = player.getStreams().associateBy { it.codecType }
        // 判据是帧序列，与墙钟无关：不设速度限制，送显跟着解码走
        sink.setSpeedRatio(100f)
        val playJob = launch { player.play(streams, 0) }
        // 起播用「出过帧」判定，不睡固定时长
        withTimeout(20_000) { while (shown.get() == 0) delay(5) }

        // 素材 25 fps：2.5 s 该有 60 帧上下，落到「一帧就停」时只有 1~2 帧
        listOf(0L to 9_000_000L, 5L to 22_000_000L, 60L to 12_000_000L).forEach { (gap, target) ->
          val added = framesAfterSeek(this, player, shown, lastPts, target, gap)
          assertTrue(
            added > 40,
            "frames must keep coming after rapid seeks (gap ${gap}ms), got $added; last shown=${lastPts.get()}",
          )
        }

        player.pause()
        playJob.join()
      } finally {
        player.closeAsync()
      }
    }

  /** 送显侧观测：每次送显记下整帧 RGBA 哈希，顺序与 `Event.Frame` 一一对应。 */
  private class HashSink {
    private val list = ArrayList<Int>()
    private val total = AtomicInteger()

    /** 已送显的帧数（原子；播放中也会读）。 */
    val size: Int get() = total.get()

    /** 播放线程调用。 */
    fun add(hash: Int) {
      list += hash
      total.incrementAndGet()
    }

    /** 只在动作返回后调用。 */
    fun hashAt(index: Int): Int = list[index]
  }

  private fun newSink(): Pair<TestSurface, HashSink> {
    val recorded = HashSink()
    return TestSurface(onVideo = { buf -> recorded.add(fnv1a(buf)) }) to recorded
  }

  private fun fnv1a(buf: ByteArray): Int {
    var h = 0x811c9dc5.toInt()
    for (b in buf) {
      h = (h xor b.toInt()) * 0x01000193
    }
    return h
  }

  /** 像素正本（帧时间戳 → 整帧哈希），跨用例共用。 */
  private val referencePixelsLock = Any()

  @Volatile
  private var referencePixelsMemo: Map<Long, Int>? = null

  /** 开播放器。解码路径钉在 `FFPlayer` 的默认值上，像素正本才与播放同源。 */
  private fun openPlayer(
    surface: TestSurface,
    onEvent: (FFPlayer.Event) -> Unit = {},
    media: File = TestMedia.file(TestMedia.BBB_640X360_12S_FASTSTART),
  ): FFPlayer =
    FFPlayer(
      media.absolutePath,
      TestLocalIO(),
      surface,
      hardware = true,
      onEvent = onEvent,
    )

  /** 顺序播到「显示过 [count] 帧」为止，随即暂停。 */
  private suspend fun CoroutineScope.playUntilFramesShown(
    player: FFPlayer,
    shown: AtomicInteger,
    count: Int,
  ) {
    val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
    val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
    withTimeout(10_000) { while (shown.get() < count) delay(5) }
    player.pause()
    playJob.join()
  }

  /**
   * 顺序解完整条视频流，得到「帧时间戳 → 整帧哈希」的正本。必须与播放同一条解码路径：
   * 硬解 NV12 / 软解 YUV420P 经 sws 有 1~3 LSB 差，跨路径哈希不等。
   */
  private fun referencePixels(): Map<Long, Int> =
    runBlocking {
      val (sink, hashes) = newSink()
      val media = TestMedia.file(TestMedia.BBB_640X360_12S_FASTSTART)
      val format = AvFormat(media.absolutePath, TestLocalIO())
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val codec = AvCodec(video, hardware = true)
        try {
          val pixels = HashMap<Long, Int>()

          suspend fun collect(frames: List<AvFrame>) =
            frames.forEach { frame ->
              sink.postFrame(AVMediaType.VIDEO, frame)
              sink.flushFrame(AVMediaType.VIDEO, frame)
              pixels[frame.timeStamp] = hashes.hashAt(hashes.size - 1)
              frame.close()
            }
          while (true) {
            val packet = format.getPacket(listOf(video)) ?: break
            packet.use { collect(codec.sendPacketAndGetFrames(it).second ?: emptyList()) }
          }
          // 解码器重排缓冲里还压着尾帧：送个空包冲刷，一直收到吐干净
          while (true) {
            val tail = codec.sendPacketAndGetFrames().second ?: break
            if (tail.isEmpty()) break
            collect(tail)
          }
          pixels
        } finally {
          codec.closeDeferred().join()
        }
      } finally {
        format.closeDeferred().join()
        sink.closeDeferred().join()
      }
    }

  /** 像素正本只解一遍，跨用例共用同一张表。 */
  private fun referencePixelsOnce(): Map<Long, Int> {
    synchronized(referencePixelsLock) {
      referencePixelsMemo?.let { return it }
      val computed = referencePixels()
      referencePixelsMemo = computed
      return computed
    }
  }

  /** 每一帧送显的像素都属于这一帧自己的时间戳。 */
  private fun assertPixelsMatch(
    shown: List<Long>,
    pixels: List<Int>,
    reference: Map<Long, Int>,
  ) {
    assertEquals(shown.size, pixels.size, "every flush should correspond to one Frame event")
    val bad = shown.zip(pixels).filter { (ts, hash) -> reference[ts] != hash }
    assertTrue(
      bad.isEmpty(),
      "these frames carry pixels from another frame's timestamp (corrupt picture): ${bad.map { it.first }}",
    )
  }

  /** 跳到 [landing] 再连退两次，返回这两次各自显示的那帧。 */
  private suspend fun stepBackTwice(
    player: FFPlayer,
    pts: List<Long>,
    landing: Long,
  ): List<Long?> {
    player.seekTo(landing)
    return (0 until 2).map {
      val mark = pts.size
      assertTrue(player.stepBack(), "stepBack must succeed when the landing is a keyframe")
      pts.drop(mark).lastOrNull()
        ?: error("stepBack should show at least one frame, got ${pts.drop(mark)}")
    }
  }

  /** 跳转之后连续播放：一帧不落（4.0–6.0s 正好 51 帧），且每帧的像素属于它自己的时间戳。 */
  @Test(timeout = 180_000)
  fun playAfterSeekShowsEachOwnFrame() {
    val reference = referencePixelsOnce()
    val pts = ArrayList<Long>()
    val shown = AtomicInteger()
    val lastPts = AtomicLong(0)
    val (sink, hashes) = newSink()
    runBlocking {
      val player =
        openPlayer(sink, onEvent = { ev ->
          if (ev is FFPlayer.Event.Frame) {
            ev.pts?.let {
              pts += it
              shown.incrementAndGet()
              lastPts.set(it)
            }
          }
        })
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
        withTimeout(10_000) { while (shown.get() < 3) delay(5) }

        // 播放中跳转：seekTo 自己会挂到这一轮结束，所以也要 launch
        val from = 4_000_000L
        val mark = shown.get()
        val hashMark = hashes.size
        val seekJob = launch { player.seekTo(from) }
        withTimeout(60_000) { while (lastPts.get() < 6_000_000L) delay(5) }
        player.pause()
        seekJob.join()
        playJob.join()

        // 跳转前那一轮可能还漏出几帧，两条列表一起丢掉以保持一一对应
        val stale = pts.drop(mark).takeWhile { it < from }.size
        val shownTs = pts.drop(mark).drop(stale)
        val pixels = (hashMark + stale until hashes.size).map { hashes.hashAt(it) }
        assertEquals(
          (100..150).map { it * 40_000L },
          shownTs.take(51),
          "continuous playback after the seek must not drop a frame (4.0s..6.0s is exactly 51 frames)",
        )
        assertPixelsMatch(shownTs, pixels, reference)
      } finally {
        player.closeAsync()
      }
    }
  }

  /** 前进 30 步再退回 4 步：每步只走一帧（40000µs），像素各归其帧，后退沿原路倒走。 */
  @Test(timeout = 180_000)
  fun stepForwardAndBackAfterSeekWalkFrames() {
    val reference = referencePixelsOnce()
    val pts = ArrayList<Long>()
    val shown = AtomicInteger()
    val (sink, hashes) = newSink()
    val from = 4_000_000L
    runBlocking {
      val player =
        openPlayer(sink, onEvent = { ev ->
          if (ev is FFPlayer.Event.Frame) {
            ev.pts?.let {
              pts += it
              shown.incrementAndGet()
            }
          }
        })
      try {
        // 先播起来：seekTo 要拿当前 pts 推新位置，没播过直接抛 "no pts data"
        playUntilFramesShown(player, shown, count = 3)
        player.seekTo(from)
        val forward =
          (1..30).map {
            val stepMark = shown.get()
            val stepHash = hashes.size
            player.stepForward()
            val ts =
              pts.drop(stepMark).singleOrNull()
                ?: error("stepForward should show exactly one frame, got ${pts.drop(stepMark)}")
            ts to hashes.hashAt(stepHash)
          }
        val forwardTs = forward.map { it.first }
        assertEquals(
          (1..30).map { from + it * 40_000L },
          forwardTs,
          "each stepForward must advance exactly one frame, at a constant frame interval",
        )
        assertPixelsMatch(forwardTs, forward.map { it.second }, reference)

        val back =
          (0 until 4).map {
            val stepMark = shown.get()
            assertTrue(player.stepBack(), "stepBack should succeed")
            pts.drop(stepMark).singleOrNull()
              ?: error("stepBack should show exactly one frame, got ${pts.drop(stepMark)}")
          }
        // 刚走过的是 f1..f30，退一步就该看见 f29，再退一步 f28……
        val retraced = forwardTs.asReversed().drop(1).take(back.size)
        assertEquals(
          retraced,
          back,
          "stepping back should retrace the forward path one frame at a time: forward=$forwardTs back=$back",
        )
      } finally {
        player.closeAsync()
      }
    }
  }

  /** 落点是 GOP 首帧时后退仍能退动：冷启动（步长按半个 tick 推）与暖机（用实测帧间隔）都要成立。 */
  @Test(timeout = 180_000)
  fun stepBackFromKeyframeLandingOnColdAndWarmStart() {
    val pts = ArrayList<Long>()
    val shown = AtomicInteger()
    // 4_000_000µs 正好是关键帧（GOP 50 帧 @25fps ⇒ 每 2 秒一个）
    val landing = 4_000_000L
    val expected = listOf(landing - 40_000, landing - 80_000)
    val (sink, _) = newSink()
    runBlocking {
      val player =
        openPlayer(sink, onEvent = { ev ->
          if (ev is FFPlayer.Event.Frame) {
            ev.pts?.let {
              pts += it
              shown.incrementAndGet()
            }
          }
        })
      try {
        // 冷启动：降到 1% 速度，第二帧要等 4 秒才送显，只显示过一帧就不必跟暂停抢时间
        sink.setSpeedRatio(0.01f)
        playUntilFramesShown(player, shown, count = 1)
        // 「还没量到间隔」是本用例的前提，当场钉住
        assertEquals(1, shown.get(), "cold start requires exactly one frame shown, got $pts")
        assertEquals(
          expected,
          stepBackTwice(player, pts, landing),
          "stepping back should walk one frame at a time (frame interval 40000us)",
        )

        // 暖机：帧间隔已量到，同一条落点再走一遍
        sink.setSpeedRatio(1f)
        assertEquals(
          expected,
          stepBackTwice(player, pts, landing),
          "stepping back should walk one frame at a time (frame interval 40000us)",
        )
      } finally {
        player.closeAsync()
      }
    }
  }

  /**
   * 有音频流时的单帧步进。前进：步进轮的音频整轮静音，时间轴不能只由音频推进，否则零事件。
   * 后退：音频先越过 `resyncTo` 是音频在推进，不是 seek 落到后面，不能据此收轮。
   */
  @Test(timeout = 120_000)
  fun stepWithAudioStreamKeepsReportingAndStepping() {
    val pts = ArrayList<Long>()
    val shown = AtomicInteger()
    val lastPts = AtomicLong(0)
    runBlocking {
      // 音频帧报自己的时间戳、并按实时节奏放：画面才会走到片中，而不是停在第一帧
      val player =
        openPlayer(TestSurface(paceAudio = true, reportAudioTs = true), onEvent = { ev ->
          if (ev is FFPlayer.Event.Frame) {
            ev.pts?.let {
              pts += it
              shown.incrementAndGet()
              lastPts.set(it)
            }
          }
        })
      try {
        val streams = player.getStreams().associateBy { it.codecType }
        assertTrue(
          streams.containsKey(AVMediaType.AUDIO),
          "precondition: the media must carry an audio stream, got ${streams.keys}",
        )
        val playJob = launch { player.play(streams, 0) }
        withTimeout(20_000) { while (lastPts.get() < 6_000_000) delay(10) }
        player.pause()
        playJob.join()
        assertTrue(pts.last() >= 5_000_000, "precondition: pause must stop inside the clip, got ${pts.takeLast(3)}")

        // 前进一帧：这一轮时间轴不能只等音频，必须报出帧事件
        val mark = shown.get()
        player.stepForward()
        assertTrue(pts.drop(mark).isNotEmpty(), "one stepForward must emit one Event.Frame")

        // 连退两次：每次都必须真的退动，而不是被音频帧提前收轮
        repeat(2) {
          assertTrue(player.stepBack(), "stepBack must succeed with an audio stream present (attempt $it)")
        }
      } finally {
        player.closeAsync()
      }
    }
  }

  /**
   * 片尾退一帧：drain 排在读循环之后，尾帧必须能在「是不是前一帧」判定之前进队列（invariants 1.8）。
   * 否则末尾两帧之间会留一个帧间隔的空洞。
   */
  @Test(timeout = 120_000)
  fun stepBackAtEndOfStreamShowsTheFrameBeforeLast() {
    val frames = ArrayList<Long?>()
    val ended = AtomicBoolean(false)
    val media = TestMedia.file(TestMedia.HLS_AD_PLAYLIST)
    runBlocking {
      val player =
        openPlayer(TestSurface(), media = media, onEvent = { ev ->
          if (ev is FFPlayer.Event.Frame) {
            frames += ev.pts
            if (ev.pts == null) ended.set(true)
          }
        })
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // 从片尾前 5 秒起播，别为了一帧播满整段
        val playJob =
          launch { player.play(mapOf(AVMediaType.VIDEO to video), 24_000_000) }
        // 播到片尾：末尾会发一条 Frame(null)
        withTimeout(60_000) { while (!ended.get()) delay(10) }
        playJob.join()

        val shown = frames.filterNotNull()
        val last = shown.last()
        val previous = shown[shown.size - 2]
        // 常规帧间隔：取末尾若干次相邻差值的众数（24fps 下是 41666/41667 交替，允许一格的抖动）
        val interval =
          shown
            .takeLast(21)
            .zipWithNext { a, b -> b - a }
            .groupingBy { it }
            .eachCount()
            .maxBy { it.value }
            .key
        assertTrue(
          last - previous <= interval + interval / 2,
          "a one-frame gap before the last frame (interval $interval): $previous -> $last",
        )
        val mark = frames.size

        assertTrue(player.stepBack(), "stepBack at the end of the stream must succeed")
        assertEquals(
          listOf(previous),
          frames.drop(mark).filterNotNull(),
          "stepBack must move exactly one frame: last=$last previous=$previous",
        )
      } finally {
        player.closeAsync()
      }
    }
  }

  /** 断流用的假 IO：交够 [serveLimit] 字节后读就挂住，只有 `abort` 能取消这次读。 */
  private class StallIO(
    private val bytes: ByteArray,
    private val serveLimit: Long,
  ) : AvIO.Handler() {
    private val servedBytes = AtomicLong(0)
    private val readCalls = AtomicInteger(0)

    /** 已交付的字节数。 */
    val served: Long get() = servedBytes.get()

    /** 读调用次数（含挂住没返回的那些）。 */
    val requests: Int get() = readCalls.get()

    override fun open(url: String): AvIO {
      var position = 0L
      return object : AvIO {
        override val handler: AvIO.Handler get() = this@StallIO

        override suspend fun runSuspend(
          buf: ByteArray,
          len: Int,
        ): Int {
          readCalls.incrementAndGet()
          if (position >= bytes.size) return AvFormat.AVERROR_EOF
          // 断流：一个永远不完成的等待，只有 abort 把它取消掉
          if (position >= serveLimit) CompletableDeferred<Unit>().await()
          val n = minOf(len.toLong(), serveLimit - position, bytes.size - position).toInt()
          bytes.copyInto(buf, 0, position.toInt(), position.toInt() + n)
          position += n
          servedBytes.addAndGet(n.toLong())
          return n
        }

        override fun seek(
          offset: Int,
          whence: Int,
        ): Int {
          if (whence == AvFormat.AVSEEK_SIZE) return bytes.size
          val base =
            when (whence) {
              AvIO.SEEK_SET -> 0L
              AvIO.SEEK_CUR -> position
              AvIO.SEEK_END -> bytes.size.toLong()
              else -> return -1
            }
          position = (base + offset).coerceIn(0, bytes.size.toLong())
          return position.toInt()
        }

        override fun close() {}
      }
    }
  }

  /** 空播放设备：音频按采样数 `delay` 当声卡节拍，视频只记帧数。 */
  private fun newPacedSink(): TestSurface = TestSurface(paceAudio = true)

  /** 起播放，不等它播完（独立守护线程）。 */
  private fun startPlayback(
    player: FFPlayer,
    streams: Map<Int, AvStream>,
  ): Thread =
    Thread {
      runCatching { runBlocking { player.play(streams) } }
    }.apply {
      isDaemon = true
      name = "probe-play"
      start()
    }

  /** 等到读超时事件出现（读原子计数，不碰事件列表；上限 [timeoutMs]）。 */
  private suspend fun awaitReadTimeout(
    readTimeouts: AtomicInteger,
    timeoutMs: Long = 8_000,
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (readTimeouts.get() > 0) return true
      delay(20)
    }
    return readTimeouts.get() > 0
  }

  /** 播到一半断掉 ⇒ 收场要能从 stall 里出来。 */
  @Test(timeout = 90_000)
  fun playbackStalledMidStreamDoesNotHangForever() {
    val bytes = Files.readAllBytes(TestMedia.file(MEDIA).toPath())
    runStallScenario(bytes)
  }

  private fun runStallScenario(bytes: ByteArray) {
    // 断点由 StallIO 定：累计交出 SERVED_LIMIT 字节后读就挂住
    val stall = StallIO(bytes, SERVED_LIMIT)
    val sink = newPacedSink()
    // 只收非帧事件（帧事件每帧都来，与判据无关）
    val readTimeouts = AtomicInteger()
    val readResumes = AtomicInteger()
    val player =
      FFPlayer("memory:$MEDIA", stall, sink) { event ->
        when (event) {
          is FFPlayer.Event.ReadTimeout -> readTimeouts.incrementAndGet()
          FFPlayer.Event.ReadTimeoutResume -> readResumes.incrementAndGet()
          else -> {}
        }
      }
    try {
      runBlocking {
        // ---- 第一段：先确认播得起来 ----
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }

        val playThread = startPlayback(player, mapOf(AVMediaType.VIDEO to video))
        val framesBeforeStop = awaitFirstFrame(sink, 10_000)
        assertTrue(
          framesBeforeStop > 0,
          "a frame should have been shown before the stream stalled (got 0) -- " +
            "this case did not reach the mid-playback state. " +
            "served ${stall.served}/${bytes.size} bytes, ${stall.requests} reads",
        )

        // ---- 第二段：等 IO 发满断点，并等到取包超时真的发生 ----
        // 判据是 [FFPlayer.Event.ReadTimeout]，不是「帧数停滞」
        awaitServed(stall, SERVED_LIMIT, 10_000)
        val servedAtStop = stall.served
        val readsAtStop = stall.requests
        val timeoutSeen = readTimeouts.get()
        assertTrue(
          awaitReadTimeout(readTimeouts),
          "no [FFPlayer.Event.ReadTimeout] within 8s after the cut-off -- the read-timeout branch is " +
            "not running, so the timing assertion below would not measure 'waiting for one withTimeout to fire'. " +
            "This case would be testing nothing.",
        )

        // ---- 第三段：判据。顺序不能倒：closeAsync() 第一件事就是 pause() ----

        // 探测前先钉住前提：此刻确实处在取包超时进行中
        assertTrue(
          readTimeouts.get() > 0,
          "the read-timeout event disappeared -- the premise of this case no longer holds.",
        )
        // pause() 必须在 1 s 内返回（实测 7–108 ms）。卡住的根因是读作业停在 AvIO.read 的
        // runBlocking，占着单线程 formatDispatcher
        probeBounded("FFPlayer.pause", 1_000) { player.pause() }

        // pause() 返回时 IO 仍未恢复（没有 ReadTimeoutResume）
        assertTrue(
          readResumes.get() == 0,
          "the IO recovered before pause() returned ([FFPlayer.Event.ReadTimeoutResume] arrived) -- " +
            "the timing assertion is not measuring 'waiting for one timeout round to finish'. " +
            "The stall should stay frozen until teardown.",
        )

        // closeAsync 要在叫醒 IO 之前探
        // 同上：5s 内不返回就 fail。卡住说明 resetChannel 的 abort 排在了读作业后面。
        probeBounded("FFPlayer.closeAsync(stalled)", 5_000) { player.closeAsync() }
        playThread.interrupt()
      }
    } finally {
      // 收场也只等有限时间：等不到就打印栈放过
      probeBounded("FFPlayer.closeAsync", 5_000) { player.closeAsync() }
    }
  }

  /** 等 IO 累计交到 [target] 字节（上限 [timeoutMs]）—— 断点生效的时点不确定，等它到。 */
  private suspend fun awaitServed(
    io: StallIO,
    target: Long,
    timeoutMs: Long,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (io.served < target && System.currentTimeMillis() < deadline) {
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

  private fun hlsMedia(): String = TestMedia.file(TestMedia.HLS_AD_PLAYLIST).absolutePath

  /** 按 url 开片：分片是 playlist 旁边的相对路径，单文件版会把 playlist 的内容回给每个分片。 */
  private fun hlsIO(): TestLocalIO = TestLocalIO()

  /** 广告的声明区间：Σ EXTINF(主片 6×2.0) 到 +（1.7 × 3）。 */
  private val adFrom = 12_000_000L
  private val adTo = 17_100_000L

  /**
   * 播放层一轮：播到广告 → 跳过 → 回退看广告 → 撤销。
   * 跳过：`AdSkipped` 的 from/to 必须夹住广告两端；回退：从断点后一帧起播、且不再发 `AdSkipped`。
   * 回退与撤销都必须 `launch`：`reverseAd` 的 `resume()` 是 `withContext` 子作业，等它返回就等于等这一轮播完。
   */
  @Test(timeout = 120_000)
  fun skipRevertAndUndoRoundTrip() =
    runBlocking {
      // 这条用例的判据就是事件流本身（接缝前后的顺序与空档），播放中也要读，
      // 所以用无锁、迭代安全的队列；其余用例都不需要容器。
      val events = ConcurrentLinkedQueue<FFPlayer.Event>()
      val eventCount = AtomicInteger()

      fun snapshot() = events.toList()
      val sink = TestSurface()
      val player =
        FFPlayer(hlsMedia(), hlsIO(), sink) { event ->
          events.add(event)
          eventCount.incrementAndGet()
        }
      try {
        // 素材只有一条 h264 视频轨，显式只传它
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // 判据全在帧时间戳上，与墙钟无关：不设速度限制
        sink.setSpeedRatio(100f)
        val job = launch { player.play(mapOf(AVMediaType.VIDEO to video), 10_000_000) }

        // ---- 跳过：播过广告边界 ----
        withTimeout(30_000) {
          while (snapshot().none { it is FFPlayer.Event.Frame && (it.pts ?: 0) >= 18_000_000 }) delay(20)
        }

        val skipped = snapshot().filterIsInstance<FFPlayer.Event.AdSkipped>()
        assertTrue(
          skipped.isNotEmpty(),
          "exactly one AdSkipped is expected between 10s and 18s, got ${snapshot().filter {
            it !is FFPlayer.Event.Frame
          }}",
        )
        val hop = skipped.first()
        assertTrue(hop.from in 11_000_000L..13_000_000L, "hop.from must point before the ad, got ${hop.from}")
        assertTrue(hop.to in 17_000_000L..18_000_000L, "hop.to must point past the ad end, got ${hop.to}")

        // 时间轴在接缝处只跳一次：抬了时钟才有这 5.1 s 的声明空档；没抬就会看到广告自己的
        // 时间戳（1.48 s 起算）插进来，空档消失、序列还会倒退
        val shown = snapshot().filterIsInstance<FFPlayer.Event.Frame>().mapNotNull { it.pts }
        assertTrue(shown.zipWithNext().all { (a, b) -> b >= a }, "shown timestamps must be monotonic, got $shown")
        val gaps = shown.zipWithNext { a, b -> b - a }.filter { it > 1_000_000 }
        assertEquals(1, gaps.size, "exactly one gap across the seam, got $gaps")
        assertTrue(
          gaps.single() in 4_000_000L..6_000_000L,
          "the gap must equal one ad length, got ${gaps.single()}us",
        )

        // ---- 回退：从断点后一帧起播广告 ----
        val revertMark = eventCount.get()
        // 两个入口都按 UI 的写法 launch：它们要等自己那一轮播完才返回
        val revertJob = launch { player.reverseAd(hop.from) }
        withTimeout(30_000) {
          while (
            snapshot()
              .drop(revertMark)
              .filterIsInstance<FFPlayer.Event.Frame>()
              .count { (it.pts ?: 0) in hop.from..16_900_000 } <= 60
          ) {
            delay(20)
          }
        }
        val reverted = snapshot().drop(revertMark)
        val adFrames =
          reverted
            .filterIsInstance<FFPlayer.Event.Frame>()
            .mapNotNull { it.pts }
            .filter { it in hop.from..16_900_000 }
        assertTrue(adFrames.size > 60, "after reverting, the ad frames must be shown, got ${adFrames.size}")
        assertTrue(
          adFrames.first() - hop.from < 200_000,
          "reverting must resume one frame past the hop, first frame ${adFrames.first()} hop ${hop.from}",
        )
        assertEquals(
          0,
          reverted.count { it is FFPlayer.Event.AdSkipped },
          "reverting must not re-classify the ad, got ${reverted.filter { it !is FFPlayer.Event.Frame }}",
        )
        assertTrue(player.isSkipAd, "reverting keeps skipAd on, later ads still get skipped")

        // ---- 撤销：落回广告之后，接着往前播 ----
        // 回退那一轮还在播，`seekTo` 因此按「本来在播」续播；等它结束后再撤就会退化成暂停态 seek
        val undoMark = eventCount.get()
        launch { player.seekTo(hop.to) }
        withTimeout(30_000) {
          while (
            snapshot()
              .drop(undoMark)
              .filterIsInstance<FFPlayer.Event.Frame>()
              .none { (it.pts ?: 0) >= hop.to + 1_000_000 }
          ) {
            delay(20)
          }
        }
        player.pause()
        job.join()
        revertJob.join()

        val undone = snapshot().drop(undoMark)
        val frames = undone.filterIsInstance<FFPlayer.Event.Frame>().mapNotNull { it.pts }
        // 回退那轮的旧帧只会漏在开头；有帧落到断点之后收敛就接管，此后不许退回广告、不许倒退。
        val landedAt = frames.indexOfFirst { it >= hop.to }
        assertTrue(landedAt >= 0, "after undo no frame reached past the hop ${hop.to}, got $frames")
        val afterLanding = frames.drop(landedAt)
        assertTrue(
          afterLanding.all { it >= hop.to - 500_000 },
          "after undo the picture must not fall back into the ad, got ${afterLanding.take(8)}",
        )
        assertTrue(
          afterLanding.zipWithNext().all { (a, b) -> b >= a },
          "after undo timestamps must be monotonic, got ${afterLanding.take(12)}",
        )
        assertEquals(
          0,
          undone.count { it is FFPlayer.Event.AdSkipped },
          "undo must not re-classify the ad, got ${undone.filter { it !is FFPlayer.Event.Frame }}",
        )
        assertTrue(
          frames.max() >= hop.to + 1_000_000,
          "after undo playback must continue forward, furthest ${frames.max()} hop ${hop.to}",
        )
      } finally {
        player.closeAsync()
      }
    }

  /**
   * 关掉跳过：两处断裂（12 s 广告起、17.1 s 广告止）必须被 demuxer 平移到声明轴上，跨过它们时间戳仍连续。
   * 改写失效的症状是 5 s 空洞 —— 广告帧退回 1.48 s，被 `resyncTo` 当成没收敛整段丢掉。
   */
  @Test(timeout = 120_000)
  fun framesStayContinuousAcrossBothAdBreaks() {
    val frames = window(adFrom - 600_000, adTo + 700_000)
    for (seam in listOf(adFrom, adTo)) {
      val around = frames.filter { it in (seam - 600_000)..(seam + 700_000) }
      assertTrue(around.size > 10, "seam=$seam only got ${around.size} frames: $around")
      assertTrue(
        around.any { it < seam } && around.any { it > seam },
        "seam=$seam needs frames on both sides, got $around",
      )
      assertTrue(around.sorted() == around, "seam=$seam timestamps must be monotonic: $around")
      val step = around.zipWithNext { a, b -> b - a }.max()
      assertTrue(step < 200_000, "seam=$seam shows a ${step}us gap: $around")
    }
  }

  /** 从 [from] 起播，收帧到越过 [until] 为止（关掉广告跳过，只看时间轴）。 */
  private fun window(
    from: Long,
    until: Long,
  ): List<Long> =
    runBlocking {
      val frames = ArrayList<Long?>()
      val lastPts = AtomicLong(0)
      val sink = TestSurface()
      val player =
        FFPlayer(hlsMedia(), hlsIO(), sink) { ev ->
          if (ev is FFPlayer.Event.Frame) {
            frames += ev.pts
            ev.pts?.let(lastPts::set)
          }
        }
      try {
        // 判据是帧序列与时间戳，与墙钟无关：不设速度限制
        sink.setSpeedRatio(100f)
        player.setSkipAd(false)
        // 素材只有一条 h264 视频轨
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val job = launch { player.play(mapOf(AVMediaType.VIDEO to video), from) }
        withTimeout(30_000) {
          while (lastPts.get() < until) delay(10)
        }
        player.pause()
        job.join()
        frames.filterNotNull()
      } finally {
        player.closeAsync()
      }
    }
}
