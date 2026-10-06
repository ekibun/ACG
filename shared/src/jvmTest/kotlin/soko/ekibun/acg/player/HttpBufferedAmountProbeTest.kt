package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.acg.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **探针**：量出「顺序读之后 ktor 通道里还剩多少字节」—— [Http.Response.availableForRead]
 * 在真实链路上的**实测量级**。
 *
 * 为什么要有它：向前 seek 时决定「白读」还是「重开会话」的**真实代价**是
 * `offset - rsp.offset - availableForRead` —— 网络游标要推的量里，已在通道内的那些**不需要再走
 * 网络**（见 [HttpIO.skipThreshold]）。这个用例把「通道里实际存着多少」量成事实，**不靠猜**。
 *
 * **结论（实测，见 [Http.Response.availableForRead] 的 KDoc）**：缓冲量**极度依赖服务端发得多快**，
 * 两者差三个数量级 ——
 * - 慢发（4 KiB/块 + 块间 `sleep(10ms)`，≈ 400 KiB/s，接近真实播放）：
 *   读后存量 `[4096, 4096, 0, 0, 0, 0, 0, 0]` —— **消费快于生产，通道恒空**。
 * - 全速（64 KiB/块、无 sleep）：读后存量 **≈ 1.0 MiB**（实测跑出过 167 KiB 与 1057 KiB，
 *   随机时序）—— 生产快于消费，写侧被 `flush()` 挂起在 `CHANNEL_MAX_SIZE` 附近。
 *
 * ⇒ **扣减只在快发时有收益**（最多省 1 MiB），慢发时与不扣减没有差别 —— 生产侧落在哪一档，
 * 决定收益有多大（见 [HttpIO.skipThreshold]）。
 *
 * **不是回归测试**：不钉死某个绝对数值（网络时序 / 引擎块大小 / 平台差异都会让数字浮动），
 * 只钉「缓冲不大幅超过 ktor 的 1 MiB 硬上限」这条**结构**事实。真值打在 stdout 里。
 *
 * **慢发那条的存量是竞态**（实测出现过 `[4096, 4096, 0, 0, ...]`，也出现过全 0，取决于第一读
 * 落在服务端第几次 write 之后）⇒ **不能**拿「缓冲非空」当断言。「缓冲能堆起来」由快发那条钉。
 */
class HttpBufferedAmountProbeTest {
  @Test(timeout = 60_000)
  fun measuresHowMuchIsLeftInTheChannelAfterSequentialReads() {
    val body = ByteArray(512 * 1024) { (it % 251).toByte() }
    TestMediaServer(body, chunkDelayMs = 10).use { server ->
      val rsp =
        runBlocking {
          Http.request(
            mapOf<Any, Any?>(
              "url" to server.url,
              "headers" to mapOf("range" to "bytes=0-"),
            ),
          )
        }
      try {
        val samples = ArrayList<Pair<Int, Int>>() // readSize to buffered
        val buf = ByteArray(16 * 1024)
        // 顺序读若干次：每读一次，通道里剩余的量就是「网络已付、ffmpeg 未拿」的存量。
        repeat(8) {
          val n = runBlocking { rsp.read(buf) }
          assertTrue(n > 0, "第 $it 次读应当拿到数据")
          samples += n to rsp.availableForRead
        }

        val lastBuffered = samples.last().second
        val anyNonZero = samples.any { it.second > 0 }
        println(
          "[probe] 读到的字节数 / 读后通道存量：" +
            samples.joinToString(prefix = "[", postfix = "]") { "${it.first}/${it.second}" },
        )
        println("[probe] body=${body.size}B  服务端被打了 ${server.requests} 次")
        println("[probe] 末次存量=$lastBuffered B（约 ${lastBuffered / 1024.0} KiB）")

        // **不**断言「缓冲非空」：慢发下存量是**竞态**（实测出现过 `[4096, 4096, 0, ...]`，
        // 也出现过全 0 —— 取决于第一读落在服务端第几次 write 之后）。拿它当断言会随机翻车。
        // 「缓冲确实能堆起来」这个前提由 [fastServerAccumulatesMoreThanSlowServer] 钉。

        // 上界：ktor 的硬上限 1 MiB（`CHANNEL_MAX_SIZE`，`internal const val`、编译期内联成
        // 1048576、运行时改不了）。写侧在 `flush()` 里挂起就不再续写 ⇒ 存量不可能超过它
        // （实测快发档跑出过 1,056,687 B = 1 MiB + 8 KiB，正好是「上限 + 一个引擎块」）。
        // 这条兜住「对背压的理解有误」：真大幅超了说明还有第三条缓冲路径没发现。
        assertTrue(
          samples.all { (_, buffered) -> buffered <= 1024 * 1024 + 64 * 1024 },
          "通道存量不应大幅超过 ktor 的 1 MiB 上限，实测 ${samples.map { it.second }}",
        )
        println("[probe]anyNonZero=$anyNonZero（竞态，仅记录不作断言）")

        assertEquals(1, server.requests, "整个探针期间不该重开会话（这条只验单会话连续读）")
      } finally {
        rsp.close()
      }
    }
  }

  /**
   * 第二个探针：**服务端快发**（块间不 sleep）时的缓冲存量 —— 慢发那条说明**消费比生产快**时
   * 通道恒空，扣减在那种情形下不产生任何差别。这条把生产侧拉到比消费快，看缓冲能堆到多少 ——
   * 这是「seek 往前跳时会白扔多少」的上界。
   *
   * 生产侧全速时写侧会在 `flush()` 里挂起（`CHANNEL_MAX_SIZE` = 1 MiB，`internal const val`
   * 编译期内联、运行时改不了），所以存量**该在 1 MiB 附近饱和**而不是无限涨。
   * 断言的是「**大于慢发时的量**」这条**相对**关系（跨用例稳定），不是某个绝对值。
   */
  @Test(timeout = 60_000)
  fun fastServerAccumulatesMoreThanSlowServer() {
    val body = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    // 块间不睡：让服务端以本机速度灌满 TCP 缓冲，写侧放开跑。
    TestMediaServer(body, chunkSize = 64 * 1024).use { server ->
      val rsp =
        runBlocking {
          Http.request(
            mapOf<Any, Any?>(
              "url" to server.url,
              "headers" to mapOf("range" to "bytes=0-"),
            ),
          )
        }
      try {
        val buf = ByteArray(16 * 1024)
        val n = runBlocking { rsp.read(buf) }
        val afterRead = rsp.availableForRead
        println(
          "[probe-fast] 第一读拿到=$n B、读后存量=$afterRead B（约 ${afterRead / 1024.0} KiB）",
        )

        // 生产侧全速 ⇒ 写侧一路挂起在 1 MiB 附近 ⇒ 第一读之后通道里就堆着**接近 1 MiB** 的存量。
        // （慢发那条实测是 8192 然后归零；这里必须显著更多，否则说明「快发也没铺开」，
        //    那么任何基于缓冲量的阈值讨论都失去前提。）
        assertTrue(
          afterRead > 64 * 1024,
          "快发时第一读之后通道里应当已铺开大量数据，实际只有 $afterRead B",
        )
        // 上界实测：ktor 的硬上限是 1 MiB（`CHANNEL_MAX_SIZE`，`internal const val`、
        // 编译期内联成 1048576、运行时改不了），最后一次 flush 之前写进 `_writeBuffer`
        // 的那个块不会被退回 ⇒ 存量可以略微超过 1 MiB，但不应超出一个引擎块的量级。
        assertTrue(
          afterRead <= 1024 * 1024 + 64 * 1024,
          "快发时存量应在 1 MiB 上限附近，实际 $afterRead B —— 背压理解有误？",
        )
        assertEquals(1, server.requests, "快发期间不该重开会话")
      } finally {
        rsp.close()
      }
    }
  }
}
