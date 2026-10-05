package soko.ekibun.acg.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.common.Http
import soko.ekibun.acg.common.SOCKET_TIMEOUT_MS
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import kotlin.getValue
import kotlin.time.Duration.Companion.milliseconds

/**
 * HTTP 上的 [AvIO]：一条**活着的**会话，响应体边到边读（走 [Http.requestStreaming]，不用
 * [Http.request] —— 后者整包缓冲，见 `silent-failures.md` 的「整包缓冲」一节）。
 *
 * 读与 seek 只在一条线程上（native 的 avio 回调单线程串行），所以本类不加锁。[read] 是**唯一**的
 * `runBlocking`（native 回调要同步签名），进门就把活交给挂起主体。
 *
 * 数据没到时**在 IO 层等**，而那次读卡在 `av_read_frame` 里、协程取消够不到 ⇒
 * [AvIO.Handler.abort] 从另一条线程叫醒它（**只叫醒、不掀会话**，推演见 `http-streaming.md`）。
 */
class HttpIO(
  val options: Map<String, Any>,
  /** 创建本 IO 的 [Handler]（反向引用）—— 用于把「本次读的投递口」登记回去，见 [Handler.abort]。 */
  private val handler: Handler,
) : AvIO {
  class Handler(
    private val options: Map<String, Any>? = null,
  ) : AvIO.Handler {
    /**
     * 「当前正在等数据的那次读」的投递口 —— 由 [HttpIO.read] 挂上 / 摘掉（见那边的 KDoc）。
     *
     * 单值而不是列表：同一时刻只有一次 `read` 在等（HLS 换分片是串行的，见类文档）。
     * `@Volatile`：`abort()` 从 avformat 线程进来，读写跨方法可见性。
     */
    @Volatile
    private var raise: (() -> Unit)? = null

    override fun open(url: String): AvIO =
      HttpIO(
        (options ?: mapOf()) +
          mapOf(
            "url" to url,
          ),
        handler = this,
      )

    /** 叫醒「当前正在等的那次读」；没读在等则 no-op。会话不在这里掀（见 [HttpIO.read]）。 */
    override fun abort() {
      raise?.invoke()
    }

    /** 登记当前的投递口（[HttpIO.read] 进来时）。 */
    internal fun onSignal(block: (() -> Unit)?) {
      raise = block
    }
  }

  private var offset = 0
  private var cachedRsp: Http.Response? = null

  /** 丢弃缓冲：白读推进时往里读、读完就丢；按 IO 复用，别每次调用都分配。 */
  private val discardBuf = ByteArray(16 * 1024)

  /**
   * 「往前跳多远就不如在网络上白读白丢」的阈值 —— 取 avio 缓冲的大小（[getBufferSize]，与 ffmpeg
   * 对齐，来由见 `cxx/AGENTS.md`）。
   *
   * 比阈值时**要减掉通道里已有的部分**（[Http.Response.availableForRead]）：那部分白读是纯内存
   * memcpy、零网络成本。**别改成「把阈值调大来代替扣减」**（阈值是常量，扣减按实际缓冲量算）。
   * 与 ffmpeg 官方判据（`aviobuf.c:279`，`offset1 <= buffer_size + short_seek_threshold`）的
   * 对照、以及「缓冲量极度依赖服务端发得多快」的实测，都见 `http-streaming.md` 第五节。
   */
  private val skipThreshold: Int get() = getBufferSize().toInt()

  /**
   * 打开一条从 [start] 起的会话，等它把响应交出来。**不用**给 [Http.Response.offset] 赋起点 ——
   * 它自己从 `Content-Range` header 算，本函数只管请求、不管记账。
   *
   * 这里**没有** `runBlocking`（[Http.requestStreaming] 本身就是 suspend），阻塞交给最外层的 [read]。
   * **建立期也能被掀掉**：[read] 那支哨兵**取消整条协程** —— 建连 / TLS 握手 / 等响应头都算。
   */
  private suspend fun getRange(start: Int): Http.Response =
    Http.requestStreaming(
      options +
        mapOf(
          "headers" to (options["headers"] as? Map<*, *> ?: mapOf<Any, Any>()) +
            mapOf(
              "range" to "bytes=$start-",
            ),
        ),
    )

  /**
   * 取出一条「网络游标已经推进到 `offset`」的响应；**返回 null 只表示真的读到了流尾**。
   */
  private suspend fun getResponseBlocking(): Http.Response? {
    var rsp = cachedRsp
    if (rsp == null) {
      rsp = getRange(offset)
    } else if (rsp.offset > offset) {
      //   |    [////buffer////]
      // offset
      // 缓冲已经越过 offset（seek 往回）：这条响应给不出 offset 处的内容，必须换一条。
      rsp.close()
      rsp = getRange(offset)
    } else if (offset - rsp.offset - rsp.availableForRead > skipThreshold) {
      // [////已交付////][//通道//]  |
      //                       offset
      // 往前跳得远 —— 远到**扣掉通道里已有的那些**之后仍超过一个 avio 缓冲：剩下的部分还得从网络上
      // 取，与其白读白丢，不如换一条**真落在 offset 上**的。服务端给不出这种响应时保留手上这条。
      // 扣减量在本分支里可信（进来的是复用的缓存会话，通道必定已建立），推导见
      // `http-streaming.md` 第五节。
      val newRsp = getRange(offset)
      if (newRsp.offset == offset) {
        rsp.close()
        rsp = newRsp
      } else {
        newRsp.close()
      }
    }
    // 这里**必须阻塞**推进，不能退回「缓冲没到就返回 0 让上层重试」：aviobuf 没有「稍后重试」这种
    // 语义（`fill_buffer` 对两种返回值的后果见 `http-streaming.md` 第一节）。
    val want = offset - rsp.offset
    // 这条 rsp 是**新建立**的还是**复用的缓存会话**：[getResponseBlocking] 开头
    // `rsp = cachedRsp` 取出来的就是复用的。抛出时两者的收场不同—— 复用那条的存亡归 [close] /
    // 静默自愈管，顺手关掉会伤到还没用完的会话。
    val reused = cachedRsp === rsp
    cachedRsp = rsp
    val advanced =
      try {
        // 把 rsp 自己往前推 want 字节（白读白丢）。每一段都走 `rsp.read`（内部先等数据到、再读），
        // 别直接用 `readAvailable` —— 后者在通道空时是裸的 `awaitContent()`，既没有超时、也不在
        // 本协程自己的取消点上。闲置超时（`IdleTimeoutException`）直接上抛，交给下面的 catch 收场。
        var left = want
        while (left > 0) {
          val n = rsp.read(discardBuf, len = minOf(left, discardBuf.size))
          if (n <= 0) break // 先读到流尾：没推完（下面 `left <= 0` 判定为 false）
          left -= n
        }
        left <= 0
      } catch (t: Throwable) {
        // **新建立的那条必须自己收干净**：它已经登记进 [cachedRsp]，但连 offset 都没走到、不是
        // 「可复用的缓存会话」，留着下次读会拿它当地基。而建立它的那次 [Http.requestStreaming] 早已
        // 返回 ⇒ [close] 与建立期的 job 两边都抓不住它（实测：服务端 entered=3 / exited=2）。
        if (!reused) {
          cachedRsp = null
          rsp.close()
        }
        throw t
      }
    if (!advanced) {
      // 读到流尾都没追到 offset：这是真 EOF，不是「数据没到」。
      rsp.close()
      cachedRsp = null
      return null
    }
    return rsp
  }

  override fun read(buf: ByteArray): Int =
    // 这里是**唯一**的 `runBlocking`：`AvIO.read` 是 native 回调要求的同步签名，
    // 而下面整条链路（建立会话 / 等数据 / 推进游标）现在全是 suspend —— 阻塞只能由
    // 发起方按需包。调用方（native 的 `av_read_frame`）本来就在本线程等这批数据。
    runBlocking {
      // **本次读的作废信号就在这里声明、只在这里消费**（不往任何下层传）：
      // 下面那支哨兵负责 await 它，`Handler.abort()` 通过投递口 raise 它。
      val signal = AbortSignal()
      // 真正干活的读（建立会话 / 等数据 / 白读推进），单独一支协程 ——
      // 好让哨兵能把它**整棵取消**掉。
      val job = async { readSuspend(buf) }
      // **旁路哨兵**：等信号，一醒来就 `cancel` 掉 `job` ⇒「叫醒」不依赖下面任何一层知道 signal 的
      // 存在，取消顺着协程树走。哨兵**只能 `cancel`、不能 `throw`** —— 抛异常会 `parentCancelled`
      // 向上取消整个 `runBlocking`（`http-streaming.md` 第二节）。
      val abort =
        async {
          signal.await()
          job.cancel()
        }
      // 挂上投递口才能被 [Handler.abort] 叫醒；摘掉必须在 `finally` —— 正常返回、
      // 作废收场、真故障三条路都要摘，否则下一条会话会被上一条的死读误伤。
      handler.onSignal { signal.raise() }
      try {
        job.await()
      } catch (e: CancellationException) {
        // 哨兵叫醒的收场：`job.cancel()` 让这里抛 `CancellationException`。
        // 这就是「本轮作废」，翻 `AVERROR_EXIT` 且静默（不是故障）。
        AvFormat.AVERROR_EXIT
      } catch (e: Throwable) {
        // 真故障才出声。被作废叫醒的读走上面那条，落不到这里 —— 不必再查旗标。
        e.printStackTrace()
        -1
      } finally {
        // 那支哨兵正常收场时**永远**挂在 `signal.await()` 上，不取消它 `runBlocking`
        // 会一直等下去（`runBlocking` 等它 scope 上所有子协程）。
        abort.cancel()
        handler.onSignal(null)
      }
    }

  /**
   * 「作废」信号：**一次性事件**，投一次就够。**只在 [read] 里声明、只在 [read] 里消费** —— 不往任何
   * 下层传（取消走协程树，见类文档）。用 `CompletableDeferred` 而不是 `StateFlow` / `Channel`
   * 的三条理由见 `http-streaming.md` 第二节。
   */
  private class AbortSignal {
    private val raised = CompletableDeferred<Unit>()

    /** 投一次「作废」。非挂起，可从任意线程调用；重复投等价一次。 */
    fun raise() {
      raised.complete(Unit)
    }

    /** 订阅：等到「作废」投来为止（已投过则立刻返回）。 */
    suspend fun await() {
      raised.await()
    }
  }

  /**
   * [read] 的挂起主体。异常按原样往上抛（由 [read] 的 `catch` 翻译成 `AVERROR_*`）。
   * 「作废」不经这里 —— 那是 [read] 那支哨兵**取消本协程**的事，本函数被取消时任一处
   * 挂起点都会抛 `CancellationException`。
   */
  private suspend fun readSuspend(buf: ByteArray): Int {
    while (true) {
      // 取不到响应 = 真的读到流尾（见 [getResponseBlocking]）。
      val rsp = getResponseBlocking() ?: return AvFormat.AVERROR_EOF
      // 等数据到再读一段 —— `rsp.read` 内部先把「等」和「读」合在一起，闲置超时时**抛**
      // [Http.IdleTimeoutException]（见它的 KDoc）。这里要的却是「超时 ⇒ 换一条会话接着读」，
      // 所以就地 catch 住。
      val ret =
        try {
          rsp.read(buf)
        } catch (e: Http.IdleTimeoutException) {
          // 闲置超时：这条会话多半死了 ⇒ 丢掉、按同样间隔换一条，**不限次数**（抛上去会被 aviobuf
          // 毒化成 EOF、播放静默「正常结束」，见 `http-streaming.md`）。间隔同样取
          // [SOCKET_TIMEOUT_MS]：刚踩完一个超时窗口就重连，换来的仍是死连接。
          // 上层另有时间闸（FFPlayer 的取包超时 → `AvPlayback.onEvent`），这里**只管一直读**。
          delay(SOCKET_TIMEOUT_MS.milliseconds)
          rsp.close()
          if (cachedRsp === rsp) cachedRsp = null
          continue
        }
      // 逻辑游标推进：`offset` 是 seek 设的、读后推进的**逻辑**位置。网络游标（`rsp.offset`）由
      // `rsp.read` 自己推，这里**不能**再动它 —— 重复记账会让网络游标虚高，下一次读就误判成「缓冲
      // 越过 offset」、白白重开会话（2026-10-03 实测：每次读都多打一个请求）。
      if (ret > 0) offset += ret
      return if (ret < 0) AvFormat.AVERROR_EOF else ret
    }
  }

  override fun seek(
    offset: Int,
    whence: Int,
  ): Int =
    try {
      // 按 POSIX lseek 语义解释 whence：SEEK_SET 绝对 / SEEK_CUR 相对当前位置 / SEEK_END 相对流尾，
      // 三者公式不同。原实现把后两者都当绝对偏移，demuxer 的 "SEEK_CUR + 负偏移" 回退试探会直接
      // 跳到错误位置。
      when (whence) {
        AvFormat.AVSEEK_SIZE -> {
          // 契约：返回流总大小；无法确定时必须返回 -1（AVERROR）。
          // 返回 0 会被 ffmpeg 解读成"长度为 0"。
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

  /** 与 abort 不同，这里**不等**：语义是「这条 IO 到此为止」，必须无条件释放。 */
  override fun close() {
    // close 掀起这条会话：通道 + 放 block 回去（ktor 随即 cleanup）都在这里。
    cachedRsp?.close()
    cachedRsp = null
  }
}
