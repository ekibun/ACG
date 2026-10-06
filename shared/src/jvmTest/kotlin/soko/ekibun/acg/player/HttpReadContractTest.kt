package soko.ekibun.acg.player

import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.TestMediaServer
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvFormat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO.read` 的**契约**回归：网络源解出来的帧序列必须与本地文件逐帧相同。
 *
 * 两个用例分别压两种服务端形态：认 Range（206，正常路径）与**不认 Range**（一律 200 整个 body，
 * seek 只能靠往前推）。两者都把 body 切成小块、每块之间 sleep，好让「缓冲里一个字节都没有」这个
 * 窗口真的出现。判据是帧序列本身：素材用随源码入库的 `media/bbb-640x360-12s-faststart.mp4`
 * （300 帧 / 12s，关键帧每 2s 一个）。解出的视频帧 PTS 必须是参考解码的一个**连续后缀**，一帧不缺。
 *
 * 第三个用例不看帧、只看**服务端请求数**：它守的是「向前 seek 该白读还是该重开会话」那条
 * **性能判据**（[HttpIO.getResponseBlocking] 第三条分支）。判据只影响快慢、不影响正确性，
 * 所以量的是会话数；落点与基线都是实测钉的（见该用例 KDoc）。
 */
class HttpReadContractTest {
  @Test
  fun rangedServerReadsEveryFrame() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val reference = decodeVideoPts(AvFormat(media.toString(), FileIO.Handler()))
    TestMediaServer(Files.readAllBytes(media), chunkDelayMs = 10).use { server ->
      val overHttp = decodeVideoPts(AvFormat(server.url, HttpIO.Handler()))
      assertEquals(reference, overHttp, "HTTP 源必须解出与本地文件完全相同的视频帧序列")
    }
  }

  /**
   * 服务端**不认 Range**（Range 头一律回 200 整个 body）时的现场：seek 之后只能靠往前推
   * 网络游标。这里先顺序读一小段（把第一条响应的缓冲用起来），再**向前跳**到 3s ——
   * offset 一下越过已缓冲的位置，逼出「缓冲没到就在 IO 层等」那条路。
   */
  @Test
  fun forwardSeekOverRangeIgnoringServerReadsEveryFrame() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    val reference = decodeVideoPts(AvFormat(media.toString(), FileIO.Handler()))
    TestMediaServer(Files.readAllBytes(media), chunkDelayMs = 10, honorRange = false).use { server ->
      val afterSeek =
        runBlocking {
          val format = AvFormat(server.url, HttpIO.Handler())
          try {
            val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
            val codec = AvCodec(video)
            try {
              // 先顺序读一点，逼出第一条响应并让缓存往前推。
              repeat(3) { format.getPacket(listOf(video))?.close() }
              // 容器级 seek 只落关键帧（每 2s 一个），seek 到 3s 会落回 2s 那个关键帧，
              // 落点之后这段全靠 IO 往前推。
              format.seekTo(3_000_000)
              val pts = ArrayList<Long>()
              while (true) {
                val packet = format.getPacket(listOf(video)) ?: break
                codec.sendPacketAndGetFrames(packet).forEach { frame ->
                  pts += frame.timeStamp
                  frame.close()
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
      assertTrue(afterSeek.size > 200, "seek 之后应当还读到两百多帧，实际 ${afterSeek.size}")
      // 必须正好是参考序列的一个连续后缀：丢一个 sample 就会在这里断开。
      val startIndex = reference.indexOf(afterSeek.first())
      assertTrue(startIndex >= 0, "seek 后的首帧 ${afterSeek.first()} 不在参考序列里")
      assertEquals(
        reference.drop(startIndex),
        afterSeek,
        "HTTP 源在向前 seek 之后必须一帧都不缺",
      )
    }
  }

  /**
   * 向前 seek 落在「网络游标之上、通道存量之内」时，扣减判据应当**保住会话**（不重开）。
   *
   * [HttpIO.getResponseBlocking] 第三条分支的判据是「白读的**网络代价**」：
   * `offset - rsp.offset - rsp.availableForRead` —— **扣掉通道里已有的部分**，因为那部分白读是
   * 纯内存 memcpy、零网络成本。落地到这里就是一句可观测的话：向前 seek 一小段时
   * [TestMediaServer.requests] **不涨**。
   *
   * 落点为什么取 2s 与 3s 两个：这条素材关键帧每 2s 一个，`seekTo` 只会落关键帧 ——
   * 实测（3 轮 × 两种发块节奏，6/6 一致）在**顺序解到 1s（25 帧）**之后：
   *
   * | seek 目标 | 服务端请求数 |
   * |---|---|
   * | 1s / 2s（落在存量内） | 不涨（扣减生效） |
   * | 3s（越过存量） | 涨 1（真要从网络取） |
   *
   * 两句断言分别守住**收益**与**边界**：扣减不能形同虚设（2s 不该重开），也不能无条件放行
   * （3s 该重开）。判据判错方向只影响性能、不影响正确性，所以这里量的是会话数而非帧内容 ——
   * 帧内容的契约由上面两个用例守。
   */
  @Test
  fun forwardSeekWithinBufferedRangeKeepsSessionButFarSeekDoesNot() {
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    TestMediaServer(Files.readAllBytes(media), chunkDelayMs = 10).use { server ->
      runBlocking {
        val format = AvFormat(server.url, HttpIO.Handler())
        try {
          val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
          val codec = AvCodec(video)
          try {
            // 顺序解到 1s（25 帧）：让会话铺开、网络游标推到 1s 附近，通道里留下存量。
            var decoded = 0
            while (decoded < 25) {
              val packet = format.getPacket(listOf(video)) ?: break
              codec.sendPacketAndGetFrames(packet).forEach { it.close() }
              decoded++
            }
            assertEquals(25, decoded, "顺序读应当能解满 25 帧（这条素材 300 帧）")
            assertEquals(1, server.requests, "顺序读期间不该重开会话")

            // 收益：2s 落回 2s 关键帧，要跳的那段已在通道里 ⇒ 零网络成本 ⇒ 不该重开。
            format.seekTo(2_000_000)
            format.getPacket(listOf(video))?.close()
            assertEquals(
              1,
              server.requests,
              "2s 落点在通道存量之内、零网络成本，不该重开会话 —— " +
                "涨了就说明判据把「通道里已有的部分」也当成了网络代价。",
            )

            // 边界：再多跳 1s 越过存量 ⇒ 真要从网络取 ⇒ 必须重开一条落在新落点上的会话。
            format.seekTo(3_000_000)
            format.getPacket(listOf(video))?.close()
            assertEquals(
              2,
              server.requests,
              "3s 越过通道存量、得真从网络取，应当换一条会话（本次是第 2 条）。",
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

  /** 顺序解完整条视频流，返回**解码序**的全部帧 PTS（微秒）——帧身份的正本。 */
  private fun decodeVideoPts(format: AvFormat): List<Long> =
    runBlocking {
      try {
        val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
        val codec = AvCodec(video)
        try {
          val pts = ArrayList<Long>()
          while (true) {
            val packet = format.getPacket(listOf(video)) ?: break
            codec.sendPacketAndGetFrames(packet).forEach { frame ->
              pts += frame.timeStamp
              frame.close()
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
}
