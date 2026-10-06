package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMediaServer
import soko.ekibun.acg.common.Http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **探针**：`Http.Response.availableForRead` 一开始为什么是 **0**？
 *
 * 本用例把那个 0 **拆成两个互斥的候选**（这才是「为什么」的可判定形式）：
 * - (A) 通道**真的**是空的 —— 网络上一个字节都没下。
 * - (B) 通道**已经装满**（ktor 侧在拉），只是 `Http.Response` 手里**还没有通道引用**。
 *
 * 判据用**服务端实际写出的字节数**当公证人：候选 (A) 蕴含「服务端写出量 ≈ 0」，
 * 候选 (B) 蕴含「服务端早已写出大量字节而本对象仍报 0」。
 *
 * 那个 0 的**直接来源**是 `availableForRead` 的 `channelOrNull?.availableForRead ?: 0`
 * ——通道引用未建立时兜出 0。这里不去挖私有字段证明它（`Http.Response.availableForRead`
 * 本身是公开的），只用**行为**证明：一旦 `read` 过（通道引用必然建立），同一个
 * `availableForRead` 立刻从 0 变成「已装满」。
 */
class HttpAvailableForReadZeroProbeTest {
  @Test(timeout = 60_000)
  fun theInitialZeroIsAMissingReferenceNotAnEmptyChannel() {
    val body = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    TestMediaServer(body, chunkSize = 64 * 1024, honorRange = false).use { server ->
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
        // 让写侧放开跑，堆满 1 MiB 上限。
        Thread.sleep(500)

        val reported = rsp.availableForRead
        val sentBefore = server.served
        println("[probe-zero] 读之前：availableForRead=$reported、服务端已写出 ${sentBefore}B")

        // 候选 (A) 被否：服务端早已推出大量字节，「网络上什么都没下」不成立。
        assertTrue(
          sentBefore > 512 * 1024,
          "服务端只写出 ${sentBefore}B —— 那个 0 可能真的是「通道空」，本用例的前提不成立",
        )
        // 候选 (B) 成立的一半：引用未建立时它就是 0（`?: 0` 兜出来的）。
        assertEquals(0, reported, "读之前应为 0（通道引用未建立）")

        // 决定性对照：一旦 read 过（通道引用建立），同一个值立刻变成「已装满」。
        val n = runBlocking { rsp.read(ByteArray(16 * 1024)) }
        val afterRead = rsp.availableForRead
        println("[probe-zero] 读之后：第一次 read=$n B、availableForRead=$afterRead B")

        // 同一个对象、同一时刻的网络状态，只因「引用建立与否」就从 0 变成 ~1 MiB。
        // ⇒ 那个 0 量的是**引用**，不是**网络**。这正是 seek 判据不能用它的原因。
        assertTrue(n > 0, "read 之后应当拿到数据（通道引用必然已建立）")
        assertTrue(
          afterRead > reported,
          "建立引用后存量应大于读之前的报值（$afterRead vs $reported）",
        )
      } finally {
        rsp.close()
      }
    }
  }
}
