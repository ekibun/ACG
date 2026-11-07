package soko.ekibun.acg.player

import soko.ekibun.TestServer
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 进程级块缓存 `HttpCache`。判据两层：命中不回网络（`requests` 不涨），命中字节逐字节等于真身。
 * 走 `HttpIO` 本体不套 demuxer；每个用例用 `caseUrl` 隔离进程级缓存。
 */
class HttpCacheTest {
  /** 回跳到读过的块：整块命中，退帧逐块回读也不再发请求。 */
  @Test(timeout = 120_000)
  fun seeksBackIntoReadBlocks() {
    val body = pattern(16 * BLOCK_BYTES)
    TestServer(body).use { server ->
      val url = server.caseUrl
      val io = HttpIO(url, mapOf(), IOHandler())
      val buf = ByteArray(BLOCK_BYTES)
      try {
        // 顺序读满 4 块，按累计字节数收（read 允许短读）
        var pos = 0
        while (pos < 4 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "sequential reads must not hit EOF, got $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
        assertEquals(1, server.requests, "sequential reads need exactly one session")

        // 回块首：整块都在缓存里 ⇒ 一次给满一块，网络碰都不碰。
        io.seek(0, AvIO.SEEK_SET)
        val n = io.read(buf, buf.size)
        assertEquals(BLOCK_BYTES, n, "a cached block must be handed back whole")
        assertRealBytes(body, 0, buf, n)
        assertEquals(1, server.requests, "a cache hit must not touch the network")

        // 退帧式逐块回读 4 KiB：一个请求都不该再发
        for (block in 3 downTo 0) {
          val at = block * BLOCK_BYTES + 3 * BLOCK_BYTES / 4
          io.seek(at, AvIO.SEEK_SET)
          val stepping = io.read(buf, 4096)
          assertEquals(4096, stepping, "8 KiB left in the block, this read should be satisfied whole")
          assertRealBytes(body, at, buf, stepping)
        }
        assertEquals(1, server.requests, "stepping back stays inside read blocks, so no new request")
      } finally {
        io.close()
      }
    }
  }

  /** 整条流重播：全程命中，且长度答得出来（`AVSEEK_SIZE` 不能是 -1）。 */
  @Test(timeout = 120_000)
  fun replaysTheWholeStream() {
    val body = pattern(2 * BLOCK_BYTES + 1000)
    TestServer(body).use { server ->
      val url = server.caseUrl
      val buf = ByteArray(BLOCK_BYTES)

      // 第一次播放：整条流过一遍网络，顺带把两块、尾块与长度都留下。
      val first = HttpIO(url, mapOf(), IOHandler())
      try {
        var pos = 0
        while (pos < body.size) {
          val n = first.read(buf, buf.size)
          assertTrue(n > 0, "the first play must not hit EOF early, got $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
        assertEquals(body.size, pos, "the whole stream must be read exactly once")
      } finally {
        first.close()
      }
      val tail = assertNotNull(HttpCache.chunk(url, 2), "the tail block must be cached too")
      assertEquals(1000, tail.size, "the tail block keeps its real length, no zero padding")
      assertContentEquals(
        body.copyOfRange(2 * BLOCK_BYTES, body.size),
        tail,
        "cached tail bytes differ from the real stream",
      )
      val requestsAfterFirst = server.requests
      assertEquals(1, requestsAfterFirst, "the first play needs exactly one session")

      // 第二次播放：全新实例 —— 缓存是**进程级**的，不随实例走。
      val second = HttpIO(url, mapOf(), IOHandler())
      try {
        assertEquals(
          body.size,
          second.seek(0, AvFormat.AVSEEK_SIZE),
          "an instance fully served from cache must still answer the stream length -- -1 makes ffmpeg treat it as unknown",
        )
        var pos = 0
        while (pos < body.size) {
          val n = second.read(buf, buf.size)
          assertTrue(n > 0, "replay must not hit EOF (the cached tail needs no second request), got $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
        assertEquals(requestsAfterFirst, server.requests, "replay is served from cache, no new request")
      } finally {
        second.close()
      }
    }
  }

  /** 非块首落点：落点所在块一并进缓存，回退整段命中。 */
  @Test(timeout = 120_000)
  fun seeksToAnUnalignedLanding() {
    val body = pattern(110 * BLOCK_BYTES)
    TestServer(body).use { server ->
      val url = server.caseUrl
      val io = HttpIO(url, mapOf(), IOHandler())
      val inBlock = 1000
      val target = 100 * BLOCK_BYTES + inBlock
      try {
        // 落点故意不在块首：会话从它所在的**块首**起，块内前缀一并装进缓存。
        io.seek(target, AvIO.SEEK_SET)
        assertRealBytes(body, target, readExactly(io, 4 * BLOCK_BYTES))
        assertEquals(1, server.requests, "jumping forward to read 4 blocks needs one session")
        for (index in 100 until 104) {
          assertNotNull(
            HttpCache.chunk(url, index),
            "block $index must be complete in cache (including block 100, where the seek landed)",
          )
        }
        assertCacheHoldsRealBytes(url, body)

        // 回退到同一落点，重读**已经读过的**那一段（到第 104 块块首为止）⇒ 整段在缓存里。
        io.seek(target, AvIO.SEEK_SET)
        assertRealBytes(body, target, readExactly(io, 4 * BLOCK_BYTES - inBlock))
        assertEquals(
          1,
          server.requests,
          "if the landing block never entered the cache, this jump back reopens a session even though 99% of the bytes are in memory",
        )
      } finally {
        io.close()
      }
    }
  }

  /** 回退命中之后往前走：缓存到头时必须在逻辑游标上重开一条会话，且字节仍等于真身、不漏会话。 */
  @Test(timeout = 60000)
  fun readingPastTheCachedPrefixReopensSessionAtTheLogicalOffset() {
    // 104 块：跳到第 100 块，跳距与理由见上一个用例。
    val body = pattern(104 * BLOCK_BYTES)
    TestServer(body).use { server ->
      val url = server.caseUrl
      val io = HttpIO(url, mapOf(), IOHandler())
      val buf = ByteArray(BLOCK_BYTES)
      try {
        // 顺序读满 3 块：0/1/2 进缓存，会话游标也跟着走到第 3 块。
        var pos = 0
        while (pos < 3 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "sequential reads must not hit EOF, got $n")
          assertRealBytes(body, pos, buf, n)
          pos += n
        }
        assertEquals(1, server.requests, "sequential reads need exactly one session")

        // 跳远：会话换成一条落在第 100 块上的，逻辑游标跟着过去。
        io.seek(100 * BLOCK_BYTES, AvIO.SEEK_SET)
        val far = io.read(buf, buf.size)
        assertTrue(far > 0, "reading from block 100 must not hit EOF, got $far")
        assertRealBytes(body, 100 * BLOCK_BYTES, buf, far)
        assertEquals(2, server.requests, "a long jump must open a new session at the landing point")

        // 回退到开头：3 块全在缓存里 ⇒ 不下网络，会话原地不动
        io.seek(0, AvIO.SEEK_SET)
        repeat(3) { index ->
          val n = io.read(buf, buf.size)
          assertEquals(BLOCK_BYTES, n, "a cached block must be handed back whole")
          assertRealBytes(body, index * BLOCK_BYTES, buf, n)
        }
        assertEquals(2, server.requests, "a jump back served from cache must not send a request")

        // 缓存到头：只能重回网络，且必须落在逻辑游标上
        val past = io.read(buf, buf.size)
        assertTrue(past > 0, "reading past the cached edge must not hit EOF, got $past")
        assertRealBytes(body, 3 * BLOCK_BYTES, buf, past)
        assertEquals(3, server.requests, "past the cached edge a new session must open at the logical offset")

        // 此后游标恢复同步，接着读不该再换会话（短读也无所谓，按累计字节数收）。
        var synced = 3 * BLOCK_BYTES + past
        while (synced < 5 * BLOCK_BYTES) {
          val n = io.read(buf, buf.size)
          assertTrue(n > 0, "after the cursors resync, reads must not hit EOF, got $n")
          assertRealBytes(body, synced, buf, n)
          synced += n
        }
        assertEquals(3, server.requests, "once the cursors resync, no further session should be opened")
      } finally {
        io.close()
      }
      // 被换掉的会话要自己收干净：entered != exited 就是有一条吊在服务端
      val deadline = System.nanoTime() + 8_000_000_000L
      while (System.nanoTime() < deadline && server.exited < server.entered) Thread.sleep(50)
      assertEquals(
        server.entered,
        server.exited,
        "the old session must be closed when swapping (entered > exited means one leaked)",
      )
    }
  }

  /** 跳到第 100 块读 5 块、再回跳到第 95 块读 10 块：后半段前 5 块走网络、后 5 块命中。 */
  @Test(timeout = 60000)
  fun backwardJumpIntoHalfCachedRegionMixesNetworkAndCache() {
    // 110 块：够读完 95..104，末尾再留几块余量。
    val body = pattern(110 * BLOCK_BYTES)
    TestServer(body).use { server ->
      val url = server.caseUrl
      val io = HttpIO(url, mapOf(), IOHandler())
      try {
        // 起点就在第 100 块：全新实例、没有会话 ⇒ 建一条落在第 100 块上的，连读 5 块。
        io.seek(100 * BLOCK_BYTES, AvIO.SEEK_SET)
        assertRealBytes(body, 100 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(1, server.requests, "jumping forward to read 5 blocks needs one session")
        for (index in 100 until 105) {
          assertNotNull(HttpCache.chunk(url, index), "block $index is fully read, so it must be cached")
        }

        // 回跳到第 95 块：95..99 未命中 ⇒ 必须重回网络（会话远在第 105 块上，得换一条）。
        assertNull(HttpCache.chunk(url, 95), "block 95 was never read, it must not be cached")
        io.seek(95 * BLOCK_BYTES, AvIO.SEEK_SET)
        assertRealBytes(body, 95 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(2, server.requests, "jumping back past the network cursor must open a session at block 95")

        // 接着读完 100..104：这 5 块全在缓存里 ⇒ 整块命中，会话原地不动（还停在网络游标 100 块处）。
        assertRealBytes(body, 100 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(2, server.requests, "the second half is cached, no new request")
      } finally {
        io.close()
      }
    }
  }

  /** 一次读跨出缓存尾部：只给缓存能连续提供的那一段，剩下的留给下一轮，游标不许停在半路。 */
  @Test(timeout = 60000)
  fun crossingTheCachedEdgeReturnsOnlyTheCachedPrefix() {
    val body = pattern(110 * BLOCK_BYTES)
    TestServer(body).use { server ->
      val url = server.caseUrl
      val io = HttpIO(url, mapOf(), IOHandler())
      try {
        // 第 100..104 块整块进缓存。
        io.seek(100 * BLOCK_BYTES, AvIO.SEEK_SET)
        assertRealBytes(body, 100 * BLOCK_BYTES, readExactly(io, 5 * BLOCK_BYTES))
        assertEquals(1, server.requests, "reading 5 blocks sequentially needs one session")

        // 落在第 104 块中间、要一整块：缓存只剩这一块的后半截 ⇒ 只给那一段。
        val inBlock = 1000
        io.seek(104 * BLOCK_BYTES + inBlock, AvIO.SEEK_SET)
        val buf = ByteArray(BLOCK_BYTES)
        val n = io.read(buf, buf.size)
        assertEquals(BLOCK_BYTES - inBlock, n, "the cache ends at block 104, only that prefix may be returned")
        assertRealBytes(body, 104 * BLOCK_BYTES + inBlock, buf, n)
        assertEquals(1, server.requests, "this range is fully in memory, no request expected")

        // 下一轮从逻辑游标（第 105 块块首）继续，走网络。
        val next = io.read(buf, buf.size)
        assertTrue(next > 0, "past the cached edge, reads must not hit EOF, got $next")
        assertRealBytes(body, 105 * BLOCK_BYTES, buf, next)
      } finally {
        io.close()
      }
    }
  }

  /** avio 只要 4 KiB 也要把整块装下来：省 JNI 往返，且这块之后直接可命中。 */
  @Test(timeout = 60000)
  fun smallReadsFillWholeBlocksAndLaterHitsStayInMemory() {
    val body = pattern(4 * BLOCK_BYTES)
    TestServer(body).use { server ->
      val url = server.caseUrl
      val buf = ByteArray(4096)

      val first = HttpIO(url, mapOf(), IOHandler())
      try {
        // 落在第 1 块中间：会话从块首起、整块读进缓存，只读 4096 就收手。
        first.seek(BLOCK_BYTES + 1000, AvIO.SEEK_SET)
        assertEquals(4096, first.read(buf, buf.size))
        assertRealBytes(body, BLOCK_BYTES + 1000, buf, 4096)

        // 同一个实例接着读：剩下的那截在内存里，网络一个字节都不碰。
        assertEquals(4096, first.read(buf, buf.size))
        assertRealBytes(body, BLOCK_BYTES + 1000 + 4096, buf, 4096)
        assertEquals(1, server.requests, "the rest of the block comes from memory")

        // 继续小块读完剩下几块：每个新块只该多磨一次网络，但会话仍是同一条。
        var got = BLOCK_BYTES + 1000 + 2 * 4096
        while (got < body.size) {
          val n = first.read(buf, buf.size)
          assertTrue(n > 0, "unexpected EOF after $got bytes")
          assertRealBytes(body, got, buf, n)
          got += n
        }
        assertEquals(1, server.requests, "sequential small reads must not open a second session")
      } finally {
        first.close()
      }

      val chunk = assertNotNull(HttpCache.chunk(url, 1), "a block is cached as soon as it is loaded")
      assertEquals(BLOCK_BYTES, chunk.size, "one read loads the whole block even if avio asked for 4 KiB")
      assertContentEquals(body.copyOfRange(BLOCK_BYTES, 2 * BLOCK_BYTES), chunk)

      val requestsAfterFirst = server.requests
      val second = HttpIO(url, mapOf(), IOHandler())
      try {
        second.seek(BLOCK_BYTES + 1000, AvIO.SEEK_SET)
        assertEquals(4096, second.read(buf, buf.size), "the block is cached, this read must hit")
        assertRealBytes(body, BLOCK_BYTES + 1000, buf, 4096)
        assertEquals(requestsAfterFirst, server.requests, "a cache hit must not touch the network")
      } finally {
        second.close()
      }
    }
  }

  /** 超预算按 LRU 淘汰：预算按**块数**计，塞满再多几十块后最早那批必须让位。 */
  @Test(timeout = 60000)
  fun cacheEvictsLeastRecentlyUsedBlocksBeyondBudget() {
    val url = "http://memory-cache.invalid/evict"
    val overBudget = HttpCache.CACHE_SIZE + 76
    repeat(overBudget) { index ->
      HttpCache.putChunk(url, index, ByteArray(BLOCK_BYTES) { index.toByte() })
    }
    assertNull(
      HttpCache.chunk(url, 0),
      "$overBudget blocks exceed the ${HttpCache.CACHE_SIZE}-block budget, the oldest must be evicted",
    )
    assertNotNull(HttpCache.chunk(url, overBudget - 1), "the block just inserted must not be evicted")
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
    "read bytes differ from the real stream (offset $at, length ${actual.size})",
  )

  /** 从当前游标起读满 [size] 字节 —— 把逻辑游标稳稳停在块边界上。 */
  private fun readExactly(
    io: HttpIO,
    size: Int,
  ): ByteArray {
    val out = ByteArray(size)
    var filled = 0
    while (filled < size) {
      val scratch = ByteArray(size - filled)
      val n = io.read(scratch, scratch.size)
      assertTrue(n > 0, "unexpected EOF at $filled / $size -- the fixture is too short")
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
        "cached block $index differs from the real stream -- the cache invented bytes",
      )
    }
  }

  /** 可辨识的字节图案：补零凑出来的块与它一比就露馅。 */
  private fun pattern(size: Int): ByteArray = ByteArray(size) { ((it * 31) xor (it ushr 5)).toByte() }
}

/** 缓存块的大小（[HttpCache.BLOCK_BYTES]）—— 用例里的偏移都按它算。 */
private val BLOCK_BYTES = HttpCache.BLOCK_BYTES
