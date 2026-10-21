package soko.ekibun.acg.player

import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 进程级块缓存 [HttpCache] 的回归。它只当**加速**使，所以判据分两层：
 *
 * 1. **收益**：seek 回到读过的区域时不再发请求（[TestMediaServer.requests] 不涨）；
 * 2. **正确性**：缓存里有的字节必须逐字节等于真身 —— 这是这套缓存唯一能**静默损坏数据**的地方
 *    （`requestCurrentBlock` 只把读到的前缀写进缓存，不补零凑整块）。
 *
 * 用例走 [HttpIO] 本体而不是 avformat：命中与否是 IO 层的事，套上 demuxer 只会把落点搅浑
 * （ffmpeg 自己读多远取决于它的内部缓冲）。
 *
 * 每个用例的 URL 都带**自己的** `?case=` 后缀：缓存是**进程级**的，而 [TestMediaServer] 用的是
 * ephemeral 端口 —— 换一个用例就可能复用同一个端口号，URL 不带后缀就会读上一个用例的缓存。
 */
class HttpMemoryCacheTest {
  /**
   * 回退到读过的区域必须走内存、**一个请求都不发**。
   *
   * 判据取 [TestMediaServer.requests] 而不是 `served`：服务端是一口气把 body 写进 socket 的，
   * 读完之后那个计数器还在涨 —— 它量的是"服务端写了多少"，不是"这次读要不要网络"。
   */
  @Test
  fun backwardSeekIsServedFromCacheWithoutNewRequest() {
    val body = pattern(8 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=seek-back"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        val buf = ByteArray(BLOCK_BYTES)
        // 顺序读满 3 块。按**累计字节数**收而不是按次数：`read` 允许短读（网络给多少读多少）。
        var pos = 0
        while (pos < 3 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "顺序读不该读到流尾，实际返回 $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
        assertEquals(1, server.requests, "顺序读一条会话就够")

        // 回块首：整块都在缓存里 ⇒ 一次给满一块，网络碰都不碰。
        io.seek(0, AvIO.SEEK_SET)
        val n = io.read(buf, buf.size)
        assertEquals(BLOCK_BYTES, n, "整块命中缓存必须一次给满一块")
        assertRealBytes(body, 0, buf, n)
        assertEquals(1, server.requests, "命中缓存不该再发请求")
      } finally {
        io.close()
      }
    }
  }

  /**
   * 重播（**新实例、同一个 URL**）全程命中缓存，连**长度**都答得出。
   *
   * 长度那半句是用例重点：`AVSEEK_SIZE` 原本只问当前会话，而全程命中缓存的实例一条会话都没建过
   * （小文件重播几乎必然如此）⇒ 会一直答 -1，ffmpeg 于是全程以为长度未知。
   */
  @Test
  fun replayOfSmallFileIsServedFromCacheAndAnswersSize() {
    val body = pattern(2 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=replay"
      val buf = ByteArray(BLOCK_BYTES)
      // 第一次播放：整条流都过一遍网络，顺带把两块与长度都留下。
      val first = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        var pos = 0
        while (pos < body.size) {
          val n = first.read(buf, buf.size)
          assertTrue(n > 0, "第一次播放不该提前读到流尾，实际返回 $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
      } finally {
        first.close()
      }
      val afterFirst = server.requests
      assertEquals(1, afterFirst, "第一次播放一条会话就够")

      // 第二次播放：全新实例 —— 缓存是**进程级**的，不随实例走。
      val second = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        assertEquals(
          body.size,
          second.seek(0, AvFormat.AVSEEK_SIZE),
          "全程命中缓存的实例也答得出流长度 —— 答 -1 会让 ffmpeg 全程以为长度未知",
        )
        var pos = 0
        while (pos < body.size) {
          val n = second.read(buf, buf.size)
          assertTrue(n > 0, "重播不该读到流尾，实际返回 $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
        assertEquals(afterFirst, server.requests, "重播全程命中缓存，不该再发请求")
      } finally {
        second.close()
      }
    }
  }

  /**
   * 向前跳 100 块落在**块中间**时，会话换到落点所在块的**块首**，那一块照样拼得满。
   *
   * 跳这么远是为了越过本地存量（实测 0），让落点不随服务端发块节奏漂移：见
   * `docs/development/playback-invariants.md` §5.6。
   */
  @Test
  fun midBlockLandingFillsTheBlockItLandsIn() {
    // 104 块：跳到第 100 块中段之后，后面还留着几块，避免落点贴着流尾。
    val body = pattern(104 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=mid-block"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        val target = 100 * BLOCK_BYTES + 1000
        io.seek(target, AvIO.SEEK_SET)
        // 读满一整块 —— 累计走过的字节足够把「块首 + 块内前缀」拼成完整一块。
        assertRealBytes(body, target, readExactly(io, BLOCK_BYTES))
        assertEquals(1, server.requests, "从全新实例跳到第 100 块中段，一条会话就够")
        assertNotNull(
          HttpCache.chunk(url, 100),
          "落点所在的第 100 块也拼得满（会话从块首起）—— 它进不了缓存的话，回退到落点仍要多开会话",
        )
        assertCacheHoldsRealBytes(url, body)
      } finally {
        io.close()
      }
    }
  }

  /**
   * 回退命中缓存之后接着往前走：缓存到头时必须**在逻辑游标上**重开一条会话。
   *
   * 判据三条：字节逐字节等于真身、只多开一条会话且落在逻辑游标上、收场不漏会话（§5.7）。
   */
  @Test
  fun readingPastTheCachedPrefixReopensSessionAtTheLogicalOffset() {
    // 104 块：跳到第 100 块，跳距与理由见上一个用例。
    val body = pattern(104 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=past-cache"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      val buf = ByteArray(BLOCK_BYTES)
      try {
        // 顺序读满 3 块：0/1/2 进缓存，会话游标也跟着走到第 3 块。
        var pos = 0
        while (pos < 3 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "顺序读不该读到流尾，实际返回 $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
        assertEquals(1, server.requests, "顺序读一条会话就够")

        // 跳远：会话换成一条落在第 100 块上的，逻辑游标跟着过去。
        io.seek(100 * BLOCK_BYTES, AvIO.SEEK_SET)
        val far = io.read(buf, buf.size)
        assertTrue(far > 0, "跳到第 100 块不该读到流尾，实际返回 $far")
        assertRealBytes(body, 100 * BLOCK_BYTES, buf, far)
        assertEquals(2, server.requests, "跳远要在落点上换一条会话")

        // 回退到开头：3 块全在缓存里 ⇒ 一个字节都不下网络，而会话**原地不动**（远在第 100 块上）。
        io.seek(0, AvIO.SEEK_SET)
        repeat(3) { index ->
          val n = io.read(buf, buf.size)
          assertEquals(BLOCK_BYTES, n, "整块命中缓存必须一次给满一块")
          assertRealBytes(body, index * BLOCK_BYTES, buf, n)
        }
        assertEquals(2, server.requests, "回退命中缓存不该发请求")

        // 缓存到头（第 3 块从没读过）：只能重回网络，且必须落在逻辑游标上。
        val past = io.read(buf, buf.size)
        assertTrue(past > 0, "缓存到头之后不该读到流尾，实际返回 $past")
        assertRealBytes(body, 3 * BLOCK_BYTES, buf, past)
        assertEquals(3, server.requests, "缓存到头应当重开一条落在逻辑游标上的会话")

        // 此后游标恢复同步，接着读不该再换会话（短读也无所谓，按累计字节数收）。
        var synced = 3 * BLOCK_BYTES + past
        while (synced < 5 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "游标同步之后继续读不该读到流尾，实际返回 $n")
          assertRealBytes(body, synced, buf, n)
          synced += n
        }
        assertEquals(3, server.requests, "游标恢复同步之后不该再换会话")
      } finally {
        io.close()
      }
      // 被换掉的那条远处会话必须自己收干净。静默期后 entered 与 exited 不相等 == 有一条吊在服务端。
      val deadline = System.nanoTime() + 8_000_000_000L
      while (System.nanoTime() < deadline && server.exited < server.entered) Thread.sleep(50)
      assertEquals(
        server.entered,
        server.exited,
        "换会话时旧那条必须被关掉（entered>exited == 漏了一条会话）",
      )
    }
  }

  /**
   * 跳到第 100 块读 5 块，再回跳到第 95 块读 10 块：后半段前 5 块走网络、后 5 块命中缓存。
   *
   * 判据：字节全程等于真身、整个来回只开两条会话。读一律按块对齐（[readExactly]）—— 跨出缓存边界
   * 的那次读会整次走网络（§5.7 第 6 条），测不到这里要说的形态。
   */
  @Test
  fun backwardJumpIntoHalfCachedRegionMixesNetworkAndCache() {
    // 110 块：够读完 95..104，末尾再留几块余量。
    val body = pattern(110 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=half-cached"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        // 起点就在第 100 块：全新实例、没有会话 ⇒ 建一条落在第 100 块上的，连读 5 块。
        io.seek(100 * BLOCK_BYTES, AvIO.SEEK_SET)
        assertRealBytes(body, 100 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(1, server.requests, "从头跳过去读 5 块一条会话就够")
        for (index in 100 until 105) {
          assertNotNull(HttpCache.chunk(url, index), "第 $index 块读满了就该进缓存")
        }

        // 回跳到第 95 块：95..99 未命中 ⇒ 必须重回网络（会话远在第 105 块上，得换一条）。
        assertNull(HttpCache.chunk(url, 95), "第 95 块从没读过，缓存里不该有它")
        io.seek(95 * BLOCK_BYTES, AvIO.SEEK_SET)
        assertRealBytes(body, 95 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(2, server.requests, "回跳越过网络游标，应当换一条落在第 95 块上的会话")

        // 接着读完 100..104：这 5 块全在缓存里 ⇒ 整块命中，会话原地不动（还停在网络游标 100 块处）。
        assertRealBytes(body, 100 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(2, server.requests, "后半段命中缓存，不该再发请求")
      } finally {
        io.close()
      }
    }
  }

  /**
   * 一次读跨出缓存尾部时，只给缓存能连续提供的那一段（`min(缓存尾部, len)`），剩下的留给下一轮。
   *
   * 两条判据：短读的长度正好等于「落点到缓存尾部」，且这一段来自内存（不发请求）；紧接着那次读从
   * 逻辑游标继续，字节逐字节等于真身 —— 短读不许把游标停在半路。
   */
  @Test
  fun crossingTheCachedEdgeReturnsOnlyTheCachedPrefix() {
    val body = pattern(110 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=cache-edge"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        // 第 100..104 块整块进缓存。
        io.seek(100 * BLOCK_BYTES, AvIO.SEEK_SET)
        assertRealBytes(body, 100 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(1, server.requests, "顺序读 5 块一条会话就够")

        // 落在第 104 块中间、要一整块：缓存只剩这一块的后半截 ⇒ 只给那一段。
        val inBlock = 1000
        io.seek(104 * BLOCK_BYTES + inBlock, AvIO.SEEK_SET)
        val buf = ByteArray(BLOCK_BYTES)
        val n = io.read(buf, buf.size)
        assertEquals(BLOCK_BYTES - inBlock, n, "缓存只到第 104 块末尾，只该给这一段")
        assertRealBytes(body, 104 * BLOCK_BYTES + inBlock, buf, n)
        assertEquals(1, server.requests, "这一段全在内存里，不该发请求")

        // 下一轮从逻辑游标（第 105 块块首）继续，走网络。
        val next = io.read(buf, buf.size)
        assertTrue(next > 0, "越过缓存边界之后不该读到流尾，实际返回 $next")
        assertRealBytes(body, 105 * BLOCK_BYTES, buf, next)
      } finally {
        io.close()
      }
    }
  }

  /**
   * 流尾那块（不足一块）也要进缓存，不等它凑满。短于一块的流（HLS 短分片）尤其必要。
   *
   * 判据：第 2 块长度是 1000 且字节等于真身；新实例重播不涨请求数、字节不重复。
   */
  @Test
  fun trailingPartialBlockIsCachedAtEof() {
    val body = pattern(2 * BLOCK_BYTES + 1000)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=trailing-block"

      val first = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        assertEquals(body.size, readToEnd(first), "整条流应读完且不多不少")
      } finally {
        first.close()
      }
      val tail = assertNotNull(HttpCache.chunk(url, 2), "流尾那块也要进缓存")
      assertEquals(1000, tail.size, "尾块按实际长度存，不补零")
      assertContentEquals(body.copyOfRange(2 * BLOCK_BYTES, body.size), tail, "尾块字节与真身不符")

      val requestsAfterFirst = server.requests
      val second = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        assertEquals(body.size, readToEnd(second), "重播不该多读（尾块命中后不能再要一次网络）")
        assertEquals(requestsAfterFirst, server.requests, "重播整条命中缓存，不该再发请求")
      } finally {
        second.close()
      }
    }
  }

  /**
   * avio 只要 4 KiB，也要把当前这一整块装下来：一次读装整块既省 JNI 往返，又让这个块直接可命中。
   * 多出来的留在缓存里，后面几次读不再碰网络。
   */
  @Test
  fun blockIsFilledEvenWhenAvioAsksLess() {
    val body = pattern(4 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=half-block"
      val buf = ByteArray(4096)

      val first = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        // 落在第 1 块中间：会话从块首起、整块读进缓存，只读 4096 就收手。
        first.seek(BLOCK_BYTES + 1000, AvIO.SEEK_SET)
        assertEquals(4096, first.read(buf, buf.size))
        assertRealBytes(body, BLOCK_BYTES + 1000, buf, 4096)

        // 同一个实例接着读：剩下的那截在内存里，网络一个字节都不碰。
        assertEquals(4096, first.read(buf, buf.size))
        assertRealBytes(body, BLOCK_BYTES + 1000 + 4096, buf, 4096)
        assertEquals(1, server.requests, "块内后续读该从内存给")
      } finally {
        first.close()
      }

      val chunk = assertNotNull(HttpCache.chunk(url, 1), "装块时就该落缓存")
      assertEquals(BLOCK_BYTES, chunk.size, "一次读装整块，哪怕 avio 只要 4 KiB")
      assertContentEquals(body.copyOfRange(BLOCK_BYTES, 2 * BLOCK_BYTES), chunk)

      val requestsAfterFirst = server.requests
      val second = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        second.seek(BLOCK_BYTES + 1000, AvIO.SEEK_SET)
        assertEquals(4096, second.read(buf, buf.size), "整块已缓存，应当命中")
        assertRealBytes(body, BLOCK_BYTES + 1000, buf, 4096)
        assertEquals(requestsAfterFirst, server.requests, "命中缓存不该再发请求")
      } finally {
        second.close()
      }
    }
  }

  /**
   * 小块连续读（帧步进的形态）：每块只应产生一次请求，块内后续读全部命中。
   */
  @Test
  fun smallReadsAcrossBlocksIssueOneRequestPerBlock() {
    val body = pattern(8 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=small-reads"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      val buf = ByteArray(4096)
      try {
        var got = 0
        while (got < 4 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "读到流尾了（已读 $got）")
          assertRealBytes(body, got, buf, n)
          got += n
        }
        // 装块会把会话读到块尾，下一块正好接着这条会话读 —— 4 块不该出现第二条连接。
        assertEquals(1, server.requests, "顺序小块读不该开第二条会话")
      } finally {
        io.close()
      }
    }
  }

  /**
   * 帧步进式的退帧：顺序读过 4 块之后，逐块回到 3/4 处读一小段，一个请求都不该再发。
   */
  @Test
  fun steppingBackAcrossReadBlocksNeverRefetches() {
    val body = pattern(16 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=step-back"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      val buf = ByteArray(BLOCK_BYTES)
      try {
        var pos = 0
        while (pos < 4 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "顺序读不该读到流尾")
          pos += n
        }
        val afterForward = server.requests

        for (block in 3 downTo 0) {
          val at = block * BLOCK_BYTES + 3 * BLOCK_BYTES / 4
          io.seek(at, AvIO.SEEK_SET)
          val n = io.read(buf, 4096)
          assertEquals(4096, n, "块内还剩 8 KiB，这一读应当一次给满")
          assertRealBytes(body, at, buf, n)
        }
        assertEquals(afterForward, server.requests, "退帧全落在读过的块里，不该再发请求")
      } finally {
        io.close()
      }
    }
  }

  /**
   * 会话**一律从块首起**（`getRange` 把请求起点下取整到块边界）⇒ 落点所在的那一块也进得了缓存，
   * 回退到同一个落点是整段命中、一条会话都不开。
   */
  @Test
  fun seekBackToAPreviouslyReadUnalignedOffsetHitsCache() {
    val body = pattern(110 * BLOCK_BYTES)
    TestMediaServer(body).use { server ->
      val url = "${server.url}?case=unaligned-start"
      val io = HttpIO(url, mapOf(), HttpIO.Handler())
      try {
        // 落点故意不在块首：会话从它所在的**块首**起，块内前缀一并装进缓存。
        val inBlock = 1000
        val target = 100 * BLOCK_BYTES + inBlock
        io.seek(target, AvIO.SEEK_SET)
        assertRealBytes(body, target, readExactly(io, 4 * BLOCK_BYTES))
        assertEquals(1, server.requests, "顺序读 4 块一条会话就够")
        for (index in 100 until 104) {
          assertNotNull(HttpCache.chunk(url, index), "第 $index 块应当拼满进缓存（含落点所在的第 100 块）")
        }

        // 回退到同一落点，重读**已经读过的**那一段（到第 104 块块首为止）⇒ 整段都在缓存里，不下网络。
        io.seek(target, AvIO.SEEK_SET)
        assertRealBytes(body, target, readExactly(io, 4 * BLOCK_BYTES - inBlock))
        assertEquals(
          1,
          server.requests,
          "落点所在的块进不了缓存的话，这次回退会重开一条会话 —— 明明 99% 的字节都在内存里",
        )
      } finally {
        io.close()
      }
    }
  }

  /** 超预算按 LRU 淘汰：预算按**块数**计，塞满再多几十块后最早那批必须让位。 */
  @Test
  fun cacheEvictsLeastRecentlyUsedBlocksBeyondBudget() {
    val url = "http://memory-cache.invalid/evict"
    val overBudget = HttpCache.CACHE_SIZE + 76
    repeat(overBudget) { index ->
      HttpCache.putChunk(url, index, ByteArray(BLOCK_BYTES) { index.toByte() })
    }
    assertNull(HttpCache.chunk(url, 0), "$overBudget 块超过 ${HttpCache.CACHE_SIZE} 块预算，最早的块必须被淘汰")
    assertNotNull(HttpCache.chunk(url, overBudget - 1), "刚放进去的块不该被淘汰")
  }

  /** 判据：`buf[0, len)` 必须逐字节等于 body 里从 [at] 起的那一段。 */
  private fun assertRealBytes(
    body: ByteArray,
    at: Int,
    buf: ByteArray,
    len: Int,
  ) = assertRealBytes(body, at, buf.copyOf(len))

  /** 判据：整段 [actual] 必须逐字节等于 body 里从 [at] 起的那一段。 */
  private fun assertRealBytes(
    body: ByteArray,
    at: Int,
    actual: ByteArray,
  ) = assertContentEquals(
    body.copyOfRange(at, at + actual.size),
    actual,
    "读出的字节与真身不符（流内起点 $at、长度 ${actual.size}）",
  )

  /** 从当前位置读到 EOF，返回总字节数。 */
  private fun readToEnd(io: HttpIO): Int {
    val buf = ByteArray(BLOCK_BYTES)
    var got = 0
    while (true) {
      val n = io.read(buf, buf.size)
      if (n <= 0) break
      got += n
    }
    return got
  }

  /**
   * 从当前游标起读满 [size] 字节，返回读到的字节 —— 用来把逻辑游标**稳稳停在块边界**上。
   *
   * 每次只申请「还差多少」：网络那条路允许短读，命中缓存则一次给满，而缓冲区长度就是剩余目标 ⇒
   * 无论如何都不会越过 [size]。
   */
  private fun readExactly(
    io: HttpIO,
    size: Int,
  ): ByteArray {
    val out = ByteArray(size)
    var filled = 0
    while (filled < size) {
      val scratch = ByteArray(size - filled)
      val n = io.read(scratch, scratch.size)
      assertTrue(n > 0, "读到流尾了（已读到 $filled / $size），素材不够长")
      scratch.copyInto(out, filled, 0, n)
      filled += n
    }
    return out
  }

  /** 缓存里现有的**每一块**都必须是真身 —— 补零凑满出来的块会在这里露馅。 */
  private fun assertCacheHoldsRealBytes(
    url: String,
    body: ByteArray,
  ) {
    for (index in 0 until body.size / BLOCK_BYTES) {
      val chunk = HttpCache.chunk(url, index) ?: continue
      assertContentEquals(
        body.copyOfRange(index * BLOCK_BYTES, index * BLOCK_BYTES + chunk.size),
        chunk,
        "缓存里第 $index 块与真身不符 —— 缓存给出了不存在的字节",
      )
    }
  }

  /** 可辨识的字节图案：补零凑出来的块与它一比就露馅。 */
  private fun pattern(size: Int): ByteArray = ByteArray(size) { ((it * 31) xor (it ushr 5)).toByte() }
}

/** 缓存块的大小（[HttpCache.BLOCK_BYTES]）—— 用例里的偏移都按它算。 */
private val BLOCK_BYTES = HttpCache.BLOCK_BYTES
