package soko.ekibun.acg.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvCodec
import soko.ekibun.ffmpeg.AvFormat
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HttpIO.read` 的**契约**回归：网络源解出来的帧序列必须与本地文件逐帧相同。
 *
 * 两个用例分别压两种服务端形态：认 Range（206，正常路径）与**不认 Range**（一律 200 整个 body，
 * seek 只能靠往前推）。两者都把 body 切成小块、每块之间 sleep，好让「缓冲里一个字节都没有」这个
 * 窗口真的出现。判据是帧序列本身：解出的视频帧 PTS 必须是参考解码的一个**连续后缀**，一帧不缺。
 */
class HttpReadContractTest {
  @Test
  fun rangedServerReadsEveryFrame() {
    val media = TestMedia.copyToTemp(TestMedia.SILENT_BLACK_25S)
    val reference = decodeVideoPts(AvFormat(media.toString(), FileIO.Handler()))
    MediaServer(Files.readAllBytes(media), supportsRange = true).use { server ->
      val overHttp = decodeVideoPts(AvFormat(server.url, HttpIO.Handler()))
      assertEquals(reference, overHttp, "HTTP 源必须解出与本地文件完全相同的视频帧序列")
    }
  }

  /**
   * 服务端**不认 Range**（Range 头一律回 200 整个 body）时的现场：seek 之后只能靠往前推
   * 网络游标。这里先顺序读一小段（把第一条响应的缓冲用起来），再**向前跳**到 20s ——
   * offset 一下越过已缓冲的位置，逼出「缓冲没到就在 IO 层等」那条路。
   */
  @Test
  fun forwardSeekOverRangeIgnoringServerReadsEveryFrame() {
    val media = TestMedia.copyToTemp(TestMedia.SILENT_BLACK_25S)
    val reference = decodeVideoPts(AvFormat(media.toString(), FileIO.Handler()))
    MediaServer(Files.readAllBytes(media), supportsRange = false).use { server ->
      val afterSeek =
        runBlocking {
          val format = AvFormat(server.url, HttpIO.Handler())
          try {
            val video = format.getStreams().first { it.codecType == AVMediaType.VIDEO }
            val codec = AvCodec(video)
            try {
              // 先顺序读一点，逼出第一条响应并让缓存往前推。
              repeat(3) { format.getPacket(listOf(video))?.close() }
              // 容器级 seek 只落关键帧（16.667s），落点之前这段全靠 IO 往前推。
              format.seekTo(20_000_000)
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
              codec.closeDeferred().await()
            }
          } finally {
            format.closeDeferred().await()
          }
        }
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
          codec.closeDeferred().await()
        }
      } finally {
        format.closeDeferred().await()
      }
    }

  /**
   * 本地媒体服务。`supportsRange = false` 时**一律回 200 整个 body** —— 客户端只能自己
   * 往前推游标那条路。body 分块下发、块间 sleep，好让「通道里一个字节都没有」这个窗口出现。
   */
  private class MediaServer(
    private val body: ByteArray,
    private val supportsRange: Boolean,
    private val chunkSize: Int = 4096,
    private val chunkDelayMs: Long = 10,
  ) : AutoCloseable {
    private val pool = Executors.newFixedThreadPool(4)
    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/media.mp4") { handle(it) }
        start()
      }

    val url: String get() = "http://127.0.0.1:${server.address.port}/media.mp4"

    private fun handle(exchange: HttpExchange) {
      val rangeStart =
        exchange.requestHeaders
          .getFirst("Range")
          ?.takeIf { supportsRange }
          ?.removePrefix("bytes=")
          ?.substringBefore('-')
          ?.toIntOrNull()
          ?: 0
      val status = if (rangeStart > 0) 206 else 200
      val length = body.size - rangeStart
      if (status == 206) {
        exchange.responseHeaders.add("Content-Range", "bytes $rangeStart-${body.size - 1}/${body.size}")
      }
      exchange.sendResponseHeaders(status, length.toLong())
      exchange.responseBody.use { out ->
        var position = rangeStart
        while (position < body.size) {
          val count = minOf(chunkSize, body.size - position)
          out.write(body, position, count)
          out.flush()
          position += count
          if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
        }
      }
    }

    override fun close() {
      server.stop(0)
      pool.shutdownNow()
    }
  }
}
