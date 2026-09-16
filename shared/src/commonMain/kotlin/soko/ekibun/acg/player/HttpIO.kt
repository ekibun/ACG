package soko.ekibun.acg.player

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.common.Http
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import kotlin.getValue

/** POSIX lseek 的 whence 取值（各平台一致：Linux/macOS 0/1/2，Windows CRT 0/1/2）。 */
private const val SEEK_SET = 0
private const val SEEK_CUR = 1
private const val SEEK_END = 2

class HttpIO(
  val options: Map<String, Any>,
) : AvIO {
  private class Response(
    private val rsp: HttpResponse,
    var offset: Int,
  ) {
    // bodyAsChannel() 本身挂起，这里只做一次 InputStream 包装，
    // runBlocking 仅用于把非挂起的惰性初始化桥过 JVM IO 接口。
    private val stream by lazy {
      runBlocking { rsp.bodyAsChannel() }.toInputStream()
    }

    /**
     * 已知的流总长度；**未知时为 -1**，不是 0。
     * 0 会被 ffmpeg 当成"长度为零的流"，从而把 seek 全部判为越界。
     *
     * `Content-Length` 只在 200 响应里给出完整长度；206 响应给的是
     * `Content-Range: bytes start-end/total`，其中 `/total` 才是流总长，
     * 所以两种都要看。
     */
    val contentLength: Long by lazy {
      rsp.headers["Content-Range"]
        ?.substringAfterLast('/')
        ?.trim()
        ?.toLongOrNull()
        ?: rsp.headers["Content-Length"]?.toLongOrNull()
        ?: -1L
    }

    val available
      get() =
        try {
          stream.available()
        } catch (_: java.io.IOException) {
          0
        }

    fun read(buf: ByteArray): Int {
      val ret = stream.read(buf)
      if (ret > 0) offset += ret
      return ret
    }

    fun takeOut(count: Int) {
      if (count <= 0) return
      stream.skip(count.toLong())
      offset += count
    }

    fun close() {
      try {
        stream.close()
      } catch (_: Throwable) {
        // 已被 cancel / 连接已断，忽略
      }
      rsp.cancel()
    }
  }

  class Handler(
    private val options: Map<String, Any>? = null,
  ) : AvIO.Handler {
    override fun open(url: String): AvIO =
      HttpIO(
        (options ?: mapOf()) +
          mapOf(
            "url" to url,
          ),
      )
  }

  override fun getBufferSize() = 32768L

  private var offset = 0
  private var cachedRsp: Response? = null

  private fun getRange(start: Int): Response {
    val rsp =
      runBlocking {
        Http.request(
          options +
            mapOf(
              "headers" to (options["headers"] as? Map<*, *> ?: mapOf<Any, Any>()) +
                mapOf(
                  "range" to "bytes=$start-",
                ),
            ),
        )
      }
    // 只有服务端真的回了 206 Partial Content 才说明 start 被采纳；
    // 回 200 表示忽略 Range 从头给，此时网络游标必须按 0 记。
    return Response(rsp, if (rsp.status.value == 206) start else 0)
  }

  /**
   * 取出一条"网络游标已经推进到 `offset`"的响应。
   *
   * 这里约定了一个不变量：**调用方（native 的 avio 回调）只会在单线程上调用
   * read/seek**，因此本方法不必加锁。但它内部**不允许阻塞线程**——它跑在
   * AvFormat 的单线程 dispatcher 上，Thread.sleep 会连带饿死同线程上的
   * demux / decode 协程。所以"数据还没到"的情况一律返回 null 让上层重试。
   */
  private fun getResponseBlocking(): Response? {
    var rsp = cachedRsp ?: getRange(offset)
    if (rsp.offset + rsp.available < offset) {
      /*
       * [////buffer////]   |
       *                  offset
       */
      val newRsp = getRange(offset)
      if (newRsp.offset + newRsp.available > rsp.offset + rsp.available) {
        rsp.close()
        rsp = newRsp
      } else {
        // not support content-range
        newRsp.close()
      }
      // consume：把网络游标推进到 offset。数据没到就退出，让调用方稍后重试。
      while (rsp.offset + rsp.available < offset) {
        val skipped = rsp.available
        if (skipped <= 0) {
          // 没有更多缓冲可丢，本次先放弃推进（调用方会重试 read/seek）
          cachedRsp = rsp
          return null
        }
        rsp.takeOut(skipped)
      }
      rsp.takeOut(offset - rsp.offset)
    } else if (rsp.offset <= offset) {
      /*
       * [///|///buffer///////]
       *   offset
       */
      rsp.takeOut(offset - rsp.offset)
    } else {
      /*
       *   |    [////buffer////]
       * offset
       */
      rsp.close()
      cachedRsp = null
      return getResponseBlocking()
    }
    cachedRsp = rsp
    return rsp
  }

  override fun read(buf: ByteArray): Int {
    return try {
      // 数据在缓冲里追上 offset 之前先返回 0（"暂时没有"），
      // 让 avio 稍后重试，而不是阻塞线程去等网络。
      val rsp = getResponseBlocking() ?: return 0
      val ret = rsp.read(buf)
      if (ret > 0) offset += ret
      ret
    } catch (e: Throwable) {
      e.printStackTrace()
      -1
    }
  }

  override fun seek(
    offset: Int,
    whence: Int,
  ): Int =
    try {
      // 按 POSIX lseek 语义解释 whence。
      // SEEK_SET(0) 绝对 / SEEK_CUR(1) 相对当前位置 / SEEK_END(2) 相对流尾，
      // 三者公式不同；原实现把后两者都当绝对偏移，
      // demuxer 的 "SEEK_CUR + 负偏移" 回退试探会直接跳到错误位置。
      when (whence) {
        AvFormat.AVSEEK_SIZE -> {
          // 契约：返回流总大小；无法确定时必须返回 -1（AVERROR）。
          // 返回 0 会被 ffmpeg 解读成"长度为 0"。
          cachedRsp?.contentLength ?: -1L
        }

        SEEK_SET -> {
          this.offset = offset
          this.offset.toLong()
        }

        SEEK_CUR -> {
          this.offset = (this.offset + offset).coerceAtLeast(0)
          this.offset.toLong()
        }

        SEEK_END -> {
          val size = cachedRsp?.contentLength ?: -1L
          if (size < 0L) {
            -1L
          } else {
            this.offset = (size + offset).coerceAtLeast(0).toInt()
            this.offset.toLong()
          }
        }

        else -> -1L
      }.toInt()
    } catch (e: Throwable) {
      e.printStackTrace()
      -1
    }

  override fun close() {
    cachedRsp?.close()
    cachedRsp = null
  }
}
