# HTTP 流的读取与会话生命周期

`HttpIO`（`acg/player/HttpIO.kt`）与 `Http.Response`（`acg/common/Http.kt`）的实现细节。源码里只留契约与指针，推演过程在此。排查「不报错只失效」时配套见 `troubleshooting/references/silent-failures.md`。

## 一、必须在 IO 层阻塞等

**约束：`AvIO.read` 的回调来自 native `av_read_frame`，数据没到就一直等——没有「稍后重试」这个返回值。**

`read` 返回值在 aviobuf 只有两种合法解（`HttpReadContractTest` 钉着）：

| `read` 返回 | `fill_buffer` 行为 | 后果 |
| --- | --- | --- |
| `0` | 走 `else`：`buf_end = dst + 0`，缓冲区仍空、不置 `eof_reached` | `avio_read` 返回 0 ⇒ `append_packet_chunked` 交出 0 长包（`av_shrink_packet(…,0)` 再 `av_packet_unref`，`stream_index` 也归 0）；`mov_read_packet` 重试分支只在 `ret < 0` 走 ⇒ sample 游标已进、字节流没进，**这个 sample 同一次 pass 内再也读不到** |
| 负数 | 对任何 `< 0` 置 `eof_reached = 1; error = len` | `avio_read` 只在「这次一个字节都没读」时 `return s->error`（`aviobuf.c:658-659`）⇒ 之后每次零字节读都收那个错误码，直到 `s->error` 被清——**没有任何代码清它**（`avio_seek` 只清 `eof_reached`，`:299/:317`）。最阴：**之后真正的 EOF 会冒充成旧错误码** |

**闲置超时不装进 `HttpTimeout`。** 读闲置超时由 `Response.read` 里自己的 `select onTimeout` 计时（`SOCKET_TIMEOUT_MS = 8_000L`），不交给两端 `HttpTimeout`：

> **DO:** 判据是「两次数据包之间有没有出字节」，不是「请求总共花了多久」——整条流可播几小时。

> **DON'T:** 用 `AVERROR(EAGAIN)` + `AVIO_FLAG_NONBLOCK` 绕开。`AVERROR(EAGAIN)` 和 `AVIO_FLAG_NONBLOCK` 只长在 `avio.c` 的 `retry_transfer_wrapper` 里，且该层只包 `URLProtocol`（`h->prot->url_read`）；本项目走 `avio_alloc_context` + 自有回调，不经过它。上游头文件 `avio.h` 的 `avio_alloc_context` 已说明：`read_packet` "For stream protocols, must never return 0 but rather a proper AVERROR code."

> **DON'T:** 指望两端 `HttpTimeout` 管读闲置。桌面那条 Java engine（`java.net.http.HttpClient`）**没有**「读闲置超时」概念，装上去被静默忽略；OkHttp 自带 10 s 默认值，与 ktor 取值不一定同源，两平台对不上。所以两端都不装、统一由那支超时信号自己计时。

## 二、每次 read 有一条私有作废信号

**约束：「这一轮不要了」（seek/close，见 `AvFormat.resetChannel`）发生在读所在线程之外，而读卡在 `av_read_frame` 里——协程取消够不到它（取消协作式，阻塞 JNI 调用里无挂起点），所以必须有一条能被外部叫醒的路。**

> **DO:** `AvFormat.resetChannel` **必须**在 `formatDispatcher` **之外**的线程调。`ThreadDispatcher` 底层是 `Executors.newSingleThreadExecutor`——纯排队、没有 event loop 能借道，所以在 avformat 线程**之内**调、或经 `Pointer.closeDeferred()` 的 `submit { }` 排进它的队列，`abort` 就排在要叫醒的那个读作业后面，永远跑不到。`AvFormat.releaseImpl` 因此**不能**「顺手」在里面补一次——它正是经 `submit { }` 排进同一根线程。

形态：一次读一条私有信号，`Handler` 只持一个投递口（`onSignal` 登记的 `raise`）：

- 有读在等时 `abort()` 投到那条读的信号 ⇒ 建立期/等数据/白读推进**全被覆盖**（它在那一次读内部新建，覆盖那次读全部等待）；
- 没读在等时（两条分片之间、seek 间隙）投递口空 ⇒ **no-op**；
- 下一次读挂的是**新**信号 ⇒ 收不到上次的作废。

「没人在等时投 = no-op」「下一次读收不到上次作废」由「信号挂在读上、生命周期即那次读」天然给出，不需配套的清旗标动作。旗标那种跨轮共享状态必须有人显式清，而「清早了/忘了清」都是难查时序病。

> **DO:** 用 `CompletableDeferred`。不是 `StateFlow<Boolean>`（状态长期存在、投过一直 `true`、须显式清，而「清早了/忘了清」都是难查时序病）；不是 `Channel`（只需「投一次、之后一直可见」，Channel 反而引入「投信号」与「挂订阅」之间的漏唤醒窗口，投时没人挂上就丢）。

单投递口够用的前提：同一时刻只有一次 `read` 在等——HLS 换分片是「上片读到尾并关掉、才开下片」，native 单线程串行调 `read`，旧那条 `read` 早已返回、投递口已被它摘掉，不会与新那条并存。**不必攒列表。**

**叫醒方式是 `cancel`，不是 `throw`：**

```kotlin
val signal = AbortSignal()
val job = async { readSuspend(buf) }
val abort = async { signal.await(); job.cancel() }   // 只 cancel，不作声
handler.onSignal { signal.raise() }
try { job.await() }
catch (e: CancellationException) { buf.size }        // 谎报读满，见下一小节
finally { abort.cancel(); handler.onSignal(null) }
```

> **DON'T:** 哨兵 `throw`。它挂在 `runBlocking` 的 scope 上，是**子协程**；`async` 里抛出的异常不是「留给 `await` 取的值」，而是**未捕获异常**，会 `parentCancelled` 向上取消父 `BlockingCoroutine` ⇒ 整个 scope（含 `readSuspend`）全被拆掉，外层那个 `catch (Http.AbortedException)` **一次都没跑**，`read` 直接以 `Int.MIN_VALUE` 收场（实测形状：`kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job="coroutine#26":BlockingCoroutine{Cancelling}`）。

> **DO:** `async` 只用来挂等待，不用来报错。对照：`Http.Response.read` 里也有 `async { data }`，它安全是因为那里用 `select` 挑一支、`finally` 把落选支 `cancel()` 掉——异常从来没被「抛出去」过。

> **DO:** `finally` 里两句都不能省——`abort.cancel()`（哨兵正常收场时永远挂在 `signal.await()` 上，不取消它 `runBlocking` 会一直等）、`handler.onSignal(null)`（摘投递口——正常返回/作废收场/真故障三条路都要摘，否则下一条会话会被上一条的死读误伤）。

**作废收场返回正数 `buf.size`，不是任何错误码。** 被叫醒的那次读必须返回一个**正数**，且取本次请求的长度 `buf.size`（`buf` 是调用方的、并没有真的填），这是让 demuxer **就此收手**的唯一办法：

| 返回 | ffmpeg 走向 | 后果 |
| --- | --- | --- |
| 负值（`AVERROR_EOF`/`AVERROR_EXIT`/`AVERROR(EAGAIN)` 实测都一样） | `ff_read_packet`（demux.c:660）`if (err < 0)`：`raw_packet_buffer` 非空且 `request_probe > 0` 时 `!pktl \|\| err == AVERROR(EAGAIN)`（demux.c:668）都不成立 ⇒ `continue` **再读一次** | 多出来的读无 abort 可用 ⇒ 开新 range 会话、干等闲置超时，`closeAsync` 拖到 **8 s** 量级 |
| `0` | `avio_read` 的 bypass 直读分支（`size > buffer_size`，开流路径正是）`size -= 0` 恒真 | 回调原地死循环重调，连开流都过不去 |
| 正数 | `read_packet` 正常返回 ⇒ `handle_new_packet` ⇒ `err == 0` ⇒ 当场 `return`（demux.c:687），**不循环** | 作废后无重进，`closeAsync` **43 ms** 返回 |

「谎报读满」安全：返回字节没真读出，但这数据必被丢——读作业交进 `AvFormat.packetChannel` 时通道已被 `resetChannel` 掀掉，`send` 当场抛错、读作业收场，包到不了解码器。「谎报读满」只是让 demuxer 停下的手段。

> **DO:** 值取 `buf.size` 而不是随便一个正数。`append_packet_chunked`（utils.c）的 `do/while` 靠 `ret == read_size` 才继续凑包（`ret != read_size` 即 `break`），一次请求的长度正好对上。

## 三、abort 只叫醒、不掀会话

**约束：会话去留只看「这条会话还出不出字节」——由正在等的那个读自己判断（`HttpIO.read` 内那支 `select onTimeout`，见 `Http.SOCKET_TIMEOUT_MS`）。**

健康会话跨 abort 复用（seek 每次作废，不白重开连接），而「掀会话」永远由同一条线程自己做，**没有跨线程拆对方正在用的东西的竞态**。

> **DO:** 下一次读面对的仍是那条停住的会话，它会闲置超时到点、自己换一条重试（`HttpIO.readSuspend` 的 `catch (e: Http.IdleTimeoutException)`）——压的是静默自愈路（`HttpAbortTest` 第二个用例）。

> **DON'T:** 把 `close()` 与 `abort()` 合成一件事。`close` 是「这条 IO 到此为止」，无条件释放（掀通道 + 放 `execute` 的 block 回去，ktor 随即 cleanup）；`abort` 是「这一轮不要了」，**不可逆的只有 close**。合成一个会把「作废」错当成「关会话」。

## 四、会话 scope 不能挂调用方 job

**约束：`Http.request` 里那条会话开在 `CoroutineScope(Dispatchers.IO)` 上，不挂调用方 job。**

- 调用方（avformat 线程）在 `runBlocking` 里等到 `[Response]` 再消费，而 `runBlocking` 会等**所有子协程**结束才返回（`BlockingCoroutine.isScopedCoroutine`）⇒ 会话等 `Response.close`、`Response.close` 等 `runBlocking` 返回，**直接死锁**；
- `CoroutineScope(Dispatchers.IO)` 工厂在上下文没有 `Job` 时自己补一个 `Job()`，那个 `Job()` 同样**没有父**，`runBlocking` 的结构化等待够不着这棵树；
- **也别挂在本函数自己的 scope 上**——本函数是 `suspend`、直接跑在调用方协程里，`launch` 上去就是同一个死锁；
- dispatcher 定死 IO 池：建连/TLS 握手是**真阻塞的 IO**，丢给 `Default`（核数那么大的池）会在多路并发时占满。

## 五、白读推进（`getResponseBlocking` 里的 `advance`）

**约束：seek 之后网络游标没到位时，要在这条响应上白读白丢地往前推。三条约束：**

1. **先登记 `cachedRsp`、再推进**——下面 `reused` 的判定靠的就是「推进之前 `cachedRsp === rsp`」，`close`/`seek` 也随时可能从外面看这个字段。若「推完再登记」，推进整段期间 `cachedRsp` 都是旧值 ⇒ `reused` 判错，且「这条会话已归本 IO」在半途不成立。
2. **新建立的那条必须自己收干净**——抛出时它已经登记进 `cachedRsp`，但连 offset 都没走到、不是「可复用的缓存会话」（复用那条的存亡归 `close`/静默自愈管，顺手关掉会伤到还没用完的会话）。建立它的那次 `Http.request` 调用早已返回 ⇒ `close` 与建立期的 job 两边都抓不住它，不收就是一条永久挂在 `done.await()` 上的 ktor 会话 + 漏掉的连接（实测：服务端 `entered=3`/`exited=2`）。
3. **每一段都走 `rsp.read`**（内部先等数据到、再读），不要直接用 `readAvailable`——后者在通道空时是裸的 `awaitContent()`，既没有超时、也不在本协程自己的取消点上。

**换会话的判据是「白读的那部分里，还得从网络上取多少」**——即 `offset - rsp.offset - rsp.availableForRead`。**通道里已有的那部分要减掉**：白读它们是纯内存 memcpy、零网络成本，算进代价会高估，于是「其实一个字节都不用下」的时候也去重开会话，而重开要付 TCP 往返 + TLS + 响应头的固定成本。量用 `Http.Response.availableForRead`（ktor 公开 API）。

> **DO:** 这条判据只在「复用缓存会话」时生效，而那条通道必定已建立（每条缓存会话至少被 `read` 过一次）⇒ 扣减量可信。新建那条的存量恒为 0、扣减退化成 0 ⇒ **只会更倾向换会话**，最坏等于扣减前。

> **DON'T:** 用「把 `skipThreshold` 调大」代替扣减。阈值是常量，扣减按**实际缓冲量**算。缓冲量极度依赖服务端发得多快——慢发（≈ 400 KiB/s）时通道**恒空**、判据退化成扣减前；只有**快发**时才铺开到 ≈ 1 MiB。省不省得到全看生产侧落在哪一档。见 `Http.Response.availableForRead` 的 KDoc。

**附：`Response.offset` 起点为什么从 header 推。** 起点**必须**是服务端在 header 里**亲口说的**那个数（206 的 `Content-Range` 起点；没有这个头——也就是 200 从头给——就是 0），**不能**由调用方灌进去。灌进去的话，`HttpIO` 建完响应还得追一次赋值，而漏一处就**静默重开一条会话**、从头重发。

解析刻意走**字符串**（`substringBefore('-')` / `removePrefix("bytes")`）而不是 ktor 的 `parseRangesSpecifier`：后者解析的是**请求侧**的 `Range: bytes=0-`，形态不同。⇒ 这条也解释了 `getRange` 里那条注释「本函数只管请求，不管记账」的分工。

## 六、通道存量（`availableForRead`）的三反直觉点

`Http.Response.availableForRead` 量的是 `readBuffer.buffer.size`，是「网络已经付过、ffmpeg 还没拿到」的那部分（探针 `HttpBufferedAmountProbeTest`；判据级用例 `HttpReadContractTest.forwardSeekWithinBufferedRangeKeepsSessionButFarSeekDoesNot`）。下面三条每一条都能让上层判断错：

1. **通道没建时它返回 0，而那个 0 不代表「网络上什么都没下」。** 下载与它无关：`Http.request` 拿到响应时引擎侧**已经在拉 body** 了（实测：一次 `read` 都没调、服务端已写出 704 KiB / 共 2 MiB）。0 的真正含义只是「本对象还没拿到通道引用」。⇒ 任何拿它当「网速慢/还没缓冲」的判据都是错的。
2. **存量不会自己涨——空等是等不来的。** 快发服务端 + 第一次读之后，存量稳定在 40879 B、**空等 10 s 一字节不涨**。因为 ktor 的写入先进 `flushBuffer`，而 `flushBuffer`→`readBuffer` 的搬运**只发生在读侧**（`moveFlushToReadBuffer`，由 `awaitContent`/`readAvailable` 触发）。⇒ 想让「已缓冲」变多**只能靠读**，而读会同时推进 `Response.offset`。
3. **存量极度依赖服务端发得多快。** 慢发（4 KiB/块 + 块间 `sleep(10ms)`，≈ 400 KiB/s，接近真实播放）时通道**恒空**、扣减量恒为 0；全速（64 KiB/块）时第一读之后 ≈ 1.0 MiB——写侧被 `flush()` 挂起在 `CHANNEL_MAX_SIZE`（1 MiB，`internal const val`、编译期内联、运行时改不了）附近，最后一次 flush 之前写进 `_writeBuffer` 的那个块不会被退回，所以存量可以略微超过 1 MiB（实测跑出过 1,056,687 B = 1 MiB + 8 KiB）。⇒ 「按已缓冲量判代价」这个改法只在快发时有收益（最多省 1 MiB），慢发时退化成原判据。
