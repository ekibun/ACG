package soko.ekibun.acg.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import soko.ekibun.common.Http
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import kotlin.time.Duration.Companion.milliseconds

/** HTTP 上的 [AvIO]：一条活着的会话，响应体边到边读，`abort` 从别的线程叫醒阻塞中的读。 */
class HttpIO(
  /** 本条流的 URL。 */
  private val url: String,
  /** 源的选项（`headers` 等，不含 `url`）。 */
  private val options: Map<String, Any>,
  /** 创建本 IO 的 [Handler]，用于登记「本次读的投递口」。 */
  private val handler: Handler,
) : AvIO {
  class Handler(
    private val options: Map<String, Any>? = null,
  ) : AvIO.Handler {
    /** 当前正在等数据的那次读的投递口，由 [HttpIO.read] 挂上 / 摘掉；`@Volatile` 供跨线程读写。 */
    @Volatile
    private var raise: (() -> Unit)? = null

    override fun open(url: String): AvIO =
      HttpIO(
        url = url,
        options = options ?: mapOf(),
        handler = this,
      )

    /** 叫醒当前正在等数据的那次读；没读在等则空操作。 */
    override fun abort() {
      raise?.invoke()
    }

    /** 登记当前的投递口。 */
    internal fun onSignal(block: (() -> Unit)?) {
      raise = block
    }
  }

  /** 交给 [Http.request] 的选项，`url` 在这里补上。 */
  private val requestOptions: Map<String, Any> = options + mapOf("url" to url)

  private var offset = 0
  private var cachedRsp: Http.Response? = null

  /** 装块缓冲：块首到块尾的字节落在这里，交付时按块内偏移切片。 */
  private val cacheBuf = ByteArray(HttpCache.BLOCK_BYTES)

  /** 打开一条从 [start] 起的 range 会话。 */
  private suspend fun getRange(start: Int): Http.Response {
    val rsp =
      Http.request(
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

  override fun read(
    buf: ByteArray,
    len: Int,
  ): Int =
    // 本类唯一一处 runBlocking：把挂起链路折成同步的 read
    try {
      runBlocking {
        // 本次读的作废信号
        val signal = AbortSignal()
        // 干活的读单独起一支协程
        val job = async { readSuspend(buf, len) }
        // 旁路哨兵：signal 一到就取消 job
        val abort =
          async {
            signal.await()
            job.cancel()
          }
        // 挂上投递口
        handler.onSignal { signal.raise() }
        try {
          job.await()
        } catch (e: CancellationException) {
          // 作废收场：返回本次请求的长度。会话可能已被关掉，但引用留着 —— streamLength 靠它答长度
          len
        } catch (e: Throwable) {
          // 真故障：会话可能是在读到一半时抛的，游标已经不可信，关掉（引用仍留给 streamLength）
          e.printStackTrace()
          cachedRsp?.close()
          -1
        } finally {
          // 取消哨兵并摘掉投递口
          abort.cancel()
          handler.onSignal(null)
        }
      }
    } catch (e: Throwable) {
      e.printStackTrace()
      -1
    }

  /** 「作废」信号：一次性事件，只在 [read] 里声明与消费。 */
  private class AbortSignal {
    private val raised = CompletableDeferred<Unit>()

    /** 投一次「作废」，任意线程可调，重复投等价一次。 */
    fun raise() {
      raised.complete(Unit)
    }

    /** 等到「作废」投来为止（已投过则立刻返回）。 */
    suspend fun await() {
      raised.await()
    }
  }

  /**
   * [read] 的挂起主体：一次交付一块 —— 缓存有就直接给，没有就先把整块装进缓存。
   * 块内后续读全是内存命中，一条会话读到底。
   */
  private suspend fun readSuspend(
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
          // 闲置超时：丢掉这条会话，等一个超时间隔后整块重来，不限次数
          delay(Http.SOCKET_TIMEOUT_MS.milliseconds)
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
