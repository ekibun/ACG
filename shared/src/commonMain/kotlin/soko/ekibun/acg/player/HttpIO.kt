package soko.ekibun.acg.player

import soko.ekibun.acg.common.requestWithDefaultUserAgent
import soko.ekibun.common.Http
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO

/** HTTP 上的 [AvIO]：一条活着的会话，响应体边到边读，`abort` 从别的线程叫醒阻塞中的读。 */
class HttpIO(
  /** 本条流的 URL。 */
  private val url: String,
  /** 源的选项（`headers` 等，不含 `url`）。 */
  private val options: Map<String, Any>,
  /** 创建本 IO 的 [AvIO.Handler]，用于登记「本次读的投递口」。 */
  override val handler: AvIO.Handler,
) : AvIO {
  /** 交给 [Http.requestWithDefaultUserAgent] 的选项，`url` 在这里补上。 */
  private val requestOptions: Map<String, Any> = options + mapOf("url" to url)

  private var offset = 0
  private var cachedRsp: Http.Response? = null

  /** 装块缓冲：块首到块尾的字节落在这里，交付时按块内偏移切片。 */
  private val cacheBuf = ByteArray(HttpCache.BLOCK_BYTES)

  /** 打开一条从 [start] 起的 range 会话。 */
  private suspend fun getRange(start: Int): Http.Response {
    val rsp =
      Http.requestWithDefaultUserAgent(
        requestOptions +
          mapOf(
            "headers" to
              (options["headers"] as? Map<*, *> ?: mapOf<Any, Any>()) +
              mapOf("range" to "bytes=$start-"),
          ),
      )
    // 长度见到一次就记进全局表：全程命中缓存、一条会话都没建过的实例，[streamLength] 靠它答得出。
    HttpCache.putLength(url, rsp.contentLength)
    return rsp
  }

  /** 把 [offset] 所在的块读满、写进缓存，返回这块的有效长度；返回 0 表示已到流尾。 */
  private suspend fun requestCurrentBlock(): Int {
    val reqOffset = HttpCache.blockStart(offset)
    var rsp = cachedRsp
    if (rsp == null || rsp.isClosed) {
      rsp = getRange(reqOffset)
    } else if (rsp.offset > offset) {
      //   |    [////buffer////]
      // offset
      rsp.close()
      rsp = getRange(reqOffset)
    } else if (offset - rsp.offset - rsp.availableForRead > 0) {
      // [////buffer////]   |
      //                  offset
      val newRsp = getRange(reqOffset)
      if (newRsp.offset == reqOffset) {
        rsp.close()
        rsp = newRsp
      } else {
        newRsp.close()
      }
    }
    // 会话一律建在块首；游标不在块首说明复用错了会话
    if (rsp.offset % cacheBuf.size != 0) error("session at ${rsp.offset}, expected block start $reqOffset")
    cachedRsp = rsp
    // 读满到块尾，一块一次
    val target = HttpCache.blockStart(offset + cacheBuf.size)
    var cacheOffset = 0
    while (rsp.offset < target) {
      val index = rsp.offset / cacheBuf.size
      cacheOffset = rsp.offset % cacheBuf.size
      val n = rsp.read(cacheBuf, cacheOffset, cacheBuf.size - cacheOffset)
      if (n <= 0 || cacheOffset + n >= cacheBuf.size) {
        cacheOffset = if (n <= 0) cacheOffset else cacheOffset + n
        // 一个字节都没读到就是流尾，别往缓存里塞空块
        if (cacheOffset > 0) HttpCache.putChunk(url, index, cacheBuf.sliceArray(0 until cacheOffset))
        // 流尾
        if (n <= 0) {
          rsp.close()
          break
        }
      }
    }
    if (rsp.offset > offset) {
      return cacheOffset
    } else {
      rsp.close()
      return 0
    }
  }

  /** 一次交付一块；作废与故障的收场在 [AvIO.read]。 */
  override suspend fun runSuspend(
    buf: ByteArray,
    len: Int,
  ): Int {
    val index = offset / HttpCache.BLOCK_BYTES
    val withinBlock = offset - index * HttpCache.BLOCK_BYTES

    while (true) {
      // 块内定位 = 块号 + 块内偏移；未命中时 requestCurrentBlock 先把整块装进 cacheBuf。
      val (chunk, chunkSize) =
        try {
          val cache = HttpCache.chunk(url, index)
          (cache ?: cacheBuf) to (cache?.size ?: requestCurrentBlock())
        } catch (e: Http.IdleTimeoutException) {
          // 闲置超时：丢掉会话，立刻整块重来。能走到这里就已白等一个完整超时（8 s / 连接 10 s），
          // 不必再退避；真断网抛的是 ConnectException，不进这条分支。
          cachedRsp?.close()
          continue
        }
      if (withinBlock < chunkSize) {
        val take = minOf(chunkSize - withinBlock, len)
        chunk.copyInto(buf, 0, withinBlock, withinBlock + take)
        offset += take
        return take
      }
      return AvFormat.AVERROR_EOF
    }
  }

  /** 读到一半抛错时游标不可信，关掉会话；引用仍留给 [streamLength]。 */
  override fun onReadFailed() {
    cachedRsp?.close()
  }

  override fun seek(
    offset: Int,
    whence: Int,
  ): Int =
    try {
      // 按 POSIX lseek 语义解释 whence
      when (whence) {
        AvFormat.AVSEEK_SIZE -> {
          // 总大小；无法确定时返回 -1
          streamLength()
        }

        AvIO.SEEK_SET -> {
          this.offset = offset
          this.offset.toLong()
        }

        AvIO.SEEK_CUR -> {
          this.offset = (this.offset + offset).coerceAtLeast(0)
          this.offset.toLong()
        }

        AvIO.SEEK_END -> {
          val size = streamLength()
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

  /** 流总长度：优先当前会话，其次全局表 —— 全程命中缓存的实例没有会话。 */
  private fun streamLength(): Long = cachedRsp?.contentLength ?: HttpCache.length(url)

  /** 关闭并释放本 IO 持有的会话。 */
  override fun close() {
    cachedRsp?.close()
    cachedRsp = null
  }
}
