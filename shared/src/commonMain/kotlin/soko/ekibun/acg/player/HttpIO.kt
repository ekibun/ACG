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

  /** 白读推进用的丢弃缓冲，按 IO 复用。 */
  private val discardBuf = ByteArray(16 * 1024)

  /** 打开一条从 [start] 起的 range 会话。 */
  private suspend fun getRange(start: Int): Http.Response =
    Http.request(
      requestOptions +
        mapOf(
          "headers" to
            (options["headers"] as? Map<*, *> ?: mapOf<Any, Any>()) +
            mapOf(
              "range" to "bytes=$start-",
            ),
        ),
    )

  /** 取出一条网络游标已推进到 `offset` 的响应；返回 null 表示读到流尾。 */
  private suspend fun getResponseBlocking(): Http.Response? {
    var rsp = cachedRsp
    if (rsp == null) {
      rsp = getRange(offset)
    } else if (rsp.offset > offset) {
      // 缓存会话越过 offset（往回 seek）：换一条
      rsp.close()
      rsp = getRange(offset)
    } else if (offset - rsp.offset - rsp.availableForRead > 0) {
      // 前向跳且还没取到的部分不为零：换一条真落在 offset 上的会话
      val newRsp = getRange(offset)
      if (newRsp.offset == offset) {
        rsp.close()
        rsp = newRsp
      } else {
        newRsp.close()
      }
    }
    // 白读把游标推进到 offset
    val want = offset - rsp.offset
    // 这条响应是否为复用缓存
    val reused = cachedRsp === rsp
    cachedRsp = rsp
    val advanced =
      try {
        // 用 rsp.read 白读推进 want 字节
        var left = want
        while (left > 0) {
          val n = rsp.read(discardBuf, len = minOf(left, discardBuf.size))
          if (n <= 0) break // 先读到流尾，没推完
          left -= n
        }
        left <= 0
      } catch (t: Throwable) {
        // 新建立的响应由本次读自己收场
        if (!reused) {
          cachedRsp = null
          rsp.close()
        }
        throw t
      }
    if (!advanced) {
      // 没追到 offset 就是流尾
      rsp.close()
      cachedRsp = null
      return null
    }
    return rsp
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
          // 作废收场：返回本次请求的长度，并丢掉已被关掉的会话
          if (cachedRsp?.isClosed == true) cachedRsp = null
          len
        } catch (e: Throwable) {
          // 真故障
          e.printStackTrace()
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

  /** [read] 的挂起主体：循环取响应并把数据读进 [buf]，异常原样上抛。 */
  private suspend fun readSuspend(
    buf: ByteArray,
    len: Int,
  ): Int {
    while (true) {
      val rsp = getResponseBlocking() ?: return AvFormat.AVERROR_EOF
      // 闲置超时：丢掉这条会话，等一个超时间隔后重试
      val ret =
        try {
          rsp.read(buf, 0, len)
        } catch (e: Http.IdleTimeoutException) {
          // 换一条会话接着读，不限次数
          delay(Http.SOCKET_TIMEOUT_MS.milliseconds)
          rsp.close()
          if (cachedRsp === rsp) cachedRsp = null
          continue
        }
      // 只推逻辑游标，网络游标由 rsp.read 推
      if (ret > 0) offset += ret
      return if (ret < 0) AvFormat.AVERROR_EOF else ret
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
          cachedRsp?.contentLength ?: -1L
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

  /** 关闭并释放本 IO 持有的会话。 */
  override fun close() {
    cachedRsp?.close()
    cachedRsp = null
  }
}
