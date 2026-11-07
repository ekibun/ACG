package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.TestServer
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `HttpIO` 的整条读面：帧序列与本地文件一致、seek 的 whence 语义、会话生命周期（流式读、作废、超时重开）。
 * 判据见 `docs/development/playback-invariants.md` §4.2 / §4.3 / §5。
 */
class HttpIOTest {
  // ---- HttpReadContractTest ----

  /** 同一判据两种服务端：认 Range 与不认 Range（只能白读推过去），帧序列都必须等于本地解码结果。 */
  @Test(timeout = 120_000)
  fun httpSourceDecodesTheSameFramesAsTheLocalFile() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val payload = Files.readAllBytes(media)
    val reference = decodeVideoPts(AvFormat(media.toString(), IOHandler()))

    TestServer(payload, chunkDelayMs = 10).use { server ->
      assertEquals(
        reference,
        decodeVideoPts(AvFormat(server.caseUrl, IOHandler())),
        "an HTTP source must decode the exact same video frame sequence as the local file",
      )
    }

    TestServer(payload, chunkDelayMs = 10, honorRange = false).use { server ->
      val afterSeek =
        runBlocking {
          val format = AvFormat(server.caseUrl, IOHandler())
          try {
            val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
            val codec = AvCodec(video)
            try {
              // 先顺序读一点，让第一条响应与缓存往前推
              repeat(3) { format.getPacket(listOf(video))?.close() }
              // 容器级 seek 只落关键帧（每 2s 一个），落点之后全靠 IO 往前推
              format.seekTo(3_000_000)
              val pts = ArrayList<Long>()
              while (true) {
                val packet = format.getPacket(listOf(video)) ?: break
                packet.use { p ->
                  codec.sendPacketAndGetFrames(p).second?.forEach { frame ->
                    pts += frame.timeStamp
                    frame.close()
                  }
                }
              }
              pts
            } finally {
              codec.closeDeferred().join()
            }
          } finally {
            format.closeDeferred().join()
          }
        }
      // seek 落到 2s 关键帧，此后还剩 10s × 25fps = 250 帧。
      assertTrue(afterSeek.size > 200, "more than 200 frames should remain after the seek, got ${afterSeek.size}")
      // 必须正好是参考序列的一个连续后缀
      val startIndex = reference.indexOf(afterSeek.first())
      assertTrue(startIndex >= 0, "the first frame after seek ${afterSeek.first()} is not in the reference sequence")
      assertEquals(
        reference.drop(startIndex),
        afterSeek,
        "an HTTP source must not drop a single frame after a forward seek",
      )
    }
  }

  /** 向前 seek 落在通道存量之内时保住会话，越过存量才重开。 */
  @Test(timeout = 60000)
  fun forwardSeekWithinBufferedRangeKeepsSessionButFarSeekDoesNot() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    TestServer(Files.readAllBytes(media), chunkDelayMs = 10).use { server ->
      runBlocking {
        val format = AvFormat(server.caseUrl, IOHandler())
        try {
          val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
          val codec = AvCodec(video)
          try {
            // 顺序解到 1s（25 帧）：网络游标推到 1s 附近，通道里留下存量
            var decoded = 0
            while (decoded < 25) {
              val packet = format.getPacket(listOf(video)) ?: break
              packet.use { p -> codec.sendPacketAndGetFrames(p).second?.forEach { f -> f.close() } }
              decoded++
            }
            assertEquals(25, decoded, "sequential reads should decode all 25 frames (this media has 300)")
            assertEquals(1, server.requests, "sequential reads must not reopen the session")

            // 收益：2s 落在存量内 ⇒ 零网络成本 ⇒ 不该重开
            format.seekTo(2_000_000)
            format.getPacket(listOf(video))?.close()
            assertEquals(
              1,
              server.requests,
              "a 2s landing point is inside the buffered range with zero network cost and must not reopen " +
                "the session -- a new request means the criterion counted the already-buffered bytes as network cost.",
            )

            // 边界：再多跳 1s 越过存量 ⇒ 真要从网络取 ⇒ 必须重开一条会话
            // 先清掉这条流的块缓存：2s 那次读顺带把 3s 附近的字节也留住了（MP4 的 pb 缓冲是 MB 级），
            // 不清的话这次读会由内存满足、根本不碰会话 —— 那是缓存起作用的正确结果，但这条用例要守的
            // 是「越过存量必须重开会话」这条判据，得在无缓存干扰的状态下测。
            HttpCache.clear(server.caseUrl)
            format.seekTo(3_000_000)
            format.getPacket(listOf(video))?.close()
            assertEquals(
              2,
              server.requests,
              "a 3s landing point is beyond the buffered range and really needs the network, so a new " +
                "session must be opened (this is the 2nd one).",
            )
          } finally {
            codec.closeDeferred().join()
          }
        } finally {
          format.closeDeferred().join()
        }
      }
    }
  }

  /** 顺序解完整条视频流，返回解码序的全部帧 PTS（微秒）。 */
  private fun decodeVideoPts(format: AvFormat): List<Long> =
    runBlocking {
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val codec = AvCodec(video)
        try {
          val pts = ArrayList<Long>()
          while (true) {
            val packet = format.getPacket(listOf(video)) ?: break
            packet.use { p ->
              codec.sendPacketAndGetFrames(p).second?.forEach { frame ->
                pts += frame.timeStamp
                frame.close()
              }
            }
          }
          pts
        } finally {
          codec.closeDeferred().join()
        }
      } finally {
        format.closeDeferred().join()
      }
    }

  // ---- HttpSeekSemanticsTest ----

  private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

  /** 直接建 [HttpIO]：本文件只判它自己的 whence 语义。 */
  private fun open(server: TestServer): HttpIO = HttpIO(server.caseUrl, mapOf(), IOHandler())

  /**
   * 四种 whence。与 `FileIOTest` 的分工：这里的长度来自会话 `Content-Length`，会话没建时 `AVSEEK_SIZE` 必须是 -1。
   */
  @Test(timeout = 60000)
  fun seekFollowsWhenceSemantics() {
    val body = bytes(8192)
    TestServer(body, honorRange = false).use { server ->
      val io = open(server)
      try {
        // 会话还没建：拿不到长度，契约要求 -1
        assertEquals(-1, io.seek(0, AvFormat.AVSEEK_SIZE), "AVSEEK_SIZE must return -1 before a session exists")

        // SEEK_SET 是绝对位置：读一个字节确认真的落在那儿
        assertEquals(42, io.seek(42, AvIO.SEEK_SET), "SEEK_SET returns the new absolute position")
        val one = ByteArray(1)
        assertEquals(1, io.read(one, one.size))
        assertEquals(body[42], one[0], "a read after SEEK_SET must start at that position")

        // SEEK_CUR 相对游标；退过头钳到 0，不能返回负数
        assertEquals(1000, io.seek(1000, AvIO.SEEK_SET))
        assertEquals(900, io.seek(-100, AvIO.SEEK_CUR), "SEEK_CUR is relative to the cursor")
        assertEquals(1400, io.seek(500, AvIO.SEEK_CUR))
        assertEquals(0, io.seek(-10_000, AvIO.SEEK_CUR), "SEEK_CUR past the start clamps to 0")

        // SEEK_END 相对流长
        assertEquals(body.size, io.seek(0, AvIO.SEEK_END), "SEEK_END + 0 is the stream length")
        assertEquals(body.size - 8, io.seek(-8, AvIO.SEEK_END), "SEEK_END offset relative to stream end")

        // 长度现在有了，未知 whence 仍是 -1
        assertEquals(body.size, io.seek(0, AvFormat.AVSEEK_SIZE), "AVSEEK_SIZE returns the total stream length")
        assertEquals(-1, io.seek(0, 0x7f7f), "an unknown whence must return -1")
      } finally {
        io.close()
      }
    }
  }
  // ---- HttpSessionTest ----

  /** 在侧线程里起一次读；返回线程与「取结果」的句柄 —— 作废要在线程还挂着时打。 */
  private fun readInBackground(
    io: AvIO,
    size: Int = 4096,
  ): Pair<Thread, () -> Int> {
    val result = AtomicInteger(Int.MIN_VALUE)
    val thread =
      Thread { result.set(io.read(ByteArray(size), size)) }.apply {
        isDaemon = true
        start()
      }
    return thread to { result.get() }
  }

  /** 同一件事的「等到有结果」版：超时直接把栈带进失败消息（挂死的读不能拖死整个构建）。 */
  private fun AvIO.readBounded(
    size: Int,
    timeoutMs: Long = 10_000,
  ): Int {
    val (thread, result) = readInBackground(this, size)
    thread.join(timeoutMs)
    if (thread.isAlive) {
      val stack = thread.stackTrace.joinToString("\n")
      throw AssertionError("read($size) did not return within ${timeoutMs}ms, stuck at://n$stack")
    }
    return result()
  }

  /** 流式：第一读在整包发完之前就交付（判据用服务端发送进度，不用墙钟）。 */
  @Test(timeout = 60_000)
  fun firstReadReturnsWhileTheBodyIsStillArriving() {
    val chunks = 60
    val chunkSize = 4096
    val total = chunks * chunkSize
    TestServer(
      body = ByteArray(total) { (it % 251).toByte() },
      chunkDelayMs = 10,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val io = IOHandler().open(server.caseUrl)
      try {
        val first = io.readBounded(chunkSize)
        assertTrue(first > 0, "the first read should get the data that has arrived")
        assertTrue(
          server.served < total,
          "the server had already sent the whole body (${server.served}/$total B): the first read waited for it",
        )

        var got = first
        while (got < total) got += io.readBounded(chunkSize)
        assertEquals(total, got, "reading to the end should yield exactly all the bytes")
      } finally {
        io.close()
      }
    }
  }

  /** 卡在正文上：只挂第一条响应，后续正常发完。 */
  @Test(timeout = 120_000)
  fun abortWhileTheBodyIsHeld() {
    TestServer(
      body = ByteArray(256 * 1024) { (it % 251).toByte() },
      chunkSize = 16 * 1024,
      hangFirstResponse = true,
    ).use { server ->
      val handler = IOHandler()
      val io = handler.open(server.caseUrl)
      try {
        val (reader, result) = readInBackground(io)
        assertTrue(server.firstResponseHanging.await(10, TimeUnit.SECONDS), "the request never reached the server")
        assertTrue(reader.isAlive, "the read must not return while the body is held")
        handler.abort()
        reader.join(10_000)
        assertFalse(reader.isAlive, "abort must wake the read blocked on the body")
        assertEquals(
          4096,
          result(),
          "an aborted read must report the positive 'read a full buf.size' value so the demuxer stops at once " +
            "instead of reading again (see AvIO.read)",
        )

        // 作废信号只活在那一次 read 里：手上那条会话已作废，这次读自己重发
        assertTrue(io.readBounded(4096, timeoutMs = 30_000) > 0, "the next read must resend by itself")
      } finally {
        io.close()
      }
    }
  }

  /** 卡在第一块之前：响应头已到、一个字节都还没来。 */
  @Test(timeout = 120_000)
  fun abortBeforeTheFirstChunkArrives() {
    TestServer(
      body = ByteArray(40 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      firstChunkDelayMs = 300,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val handler = IOHandler()
      val io = handler.open(server.caseUrl)
      try {
        val (reader, result) = readInBackground(io)
        // 服务端还在憋第一块，所以此刻读确实在进行中
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && server.entered < 1) Thread.sleep(5)
        assertTrue(reader.isAlive, "the read should still be in flight before the first chunk")
        handler.abort()
        reader.join(5_000)
        assertFalse(reader.isAlive, "abort must wake the waiting read")
        assertEquals(4096, result(), "an aborted read reports a full buffer")

        assertTrue(io.readBounded(4096) > 0, "reads should keep working after the abort")
        assertEquals(2, server.requests, "the abort dropped that session, so the next read must resend")
      } finally {
        io.close()
      }
    }
  }

  /** 没有读在等的时候：作废必须是 no-op，否则会白白丢掉一条好会话。 */
  @Test(timeout = 120_000)
  fun abortWhenNobodyWaits() {
    TestServer(
      body = ByteArray(40 * 4096) { (it % 251).toByte() },
      chunkDelayMs = 20,
      honorRange = false,
      declareLength = false,
    ).use { server ->
      val handler = IOHandler()
      val io = handler.open(server.caseUrl)
      try {
        assertTrue(io.readBounded(4096) > 0, "the first read should get data")
        assertEquals(1, server.requests, "the first read should open exactly one session")

        // 上一次读早就返回了，此刻没读在跑 ⇒ abort 是 no-op
        handler.abort()

        assertTrue(io.readBounded(4096) > 0, "reads should still work after the abort")
        assertEquals(1, server.requests, "abort with nobody waiting must not drop the session and resend")
      } finally {
        io.close()
      }
    }
  }

  /**
   * 首条会话挂死时，闲置超时丢掉它、重开一条接着读。不重试的话 `read` 折成 -1，ffmpeg 判 IO 错误。
   * 固定 ~8 s：读超时是生产常量 `Http.SOCKET_TIMEOUT_MS`，重开不再额外退避。
   */
  @Test(timeout = 120_000)
  fun idleTimeoutReopensSessionAndKeepsReading() {
    val body = ByteArray(4 * HttpCache.BLOCK_BYTES) { (it % 251).toByte() }
    TestServer(body, hangFirstResponse = true).use { server ->
      val io = HttpIO(server.caseUrl, mapOf(), IOHandler())
      try {
        val buf = ByteArray(4096)
        val n = io.read(buf, buf.size)
        assertTrue(n > 0, "after an idle timeout the read must reopen the session instead of returning -1")
        assertTrue(
          body.copyOfRange(0, n).contentEquals(buf.copyOf(n)),
          "after reopening, the bytes must start at the stream head",
        )
        assertTrue(server.requests >= 2, "the first session hung, so a second one is expected")
      } finally {
        io.close()
      }
    }
  }

  /** 作废落在前推途中：已建好的响应被摘掉并关掉，服务端 `entered == exited`（没有会话被丢在 park 里）。 */
  @Test(timeout = 120_000)
  fun abandonedSessionIsReleasedWhenAdvanceThrows() {
    val body = ByteArray(200_000) { (it % 251).toByte() }
    TestServer(
      body,
      chunkDelayMs = 80,
      honorRange = false,
      declareLength = false,
      heartbeat = true,
    ).use { server ->
      val handler = IOHandler()
      val io = handler.open(server.caseUrl)
      // 先读一次让会话存在，再丢掉它
      assertTrue(io.readBounded(4096) > 0, "first read")
      io.close()

      // 逻辑 offset 拉得很远：下一次读要新建会话、靠 advance 一路往前磨
      io.seek(150_000, AvIO.SEEK_SET)
      val servedBefore = server.served
      val (reader, result) = readInBackground(io)
      // 等第二会话真的在发（80 ms 一块），不睡固定时长
      val streaming = System.nanoTime() + 5_000_000_000L
      while (System.nanoTime() < streaming && server.served - servedBefore < 8192) Thread.sleep(10)
      assertTrue(server.served - servedBefore >= 8192, "the read never got a second session streaming")
      assertTrue(reader.isAlive, "read must be grinding through advance")
      handler.abort()
      reader.join(5_000)
      assertFalse(reader.isAlive, "abort must end the read")
      val ret = result()
      io.close()

      // 静默期：等每个 handler 察觉并退出
      val deadline = System.nanoTime() + 8_000_000_000L
      while (System.nanoTime() < deadline && server.exited < server.entered) Thread.sleep(50)
      assertEquals(4096, ret, "aborted read must report a full-buffer read (buf.size)")
      assertEquals(
        server.entered,
        server.exited,
        "a session was left parked (entered>exited): the response abandoned by discard is never closed",
      )
    }
  }
}
