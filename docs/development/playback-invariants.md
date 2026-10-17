# 播放链路的不变量与外部依据

只收录**已定稿的知识**：ffmpeg / kotlinx / OkHttp / skiko 的**外部行为依据**，以及由它们推导出的判据。
不在 agent 自动决策路径上；动手改播放链路（`AvFormat` / `FFPlayer` / `HttpIO` / `SurfaceContext`）前先读本文对应节。
命令与验收纪律见 [build-and-test 技能](../../.agents/skills/build-and-test/SKILL.md)。

依据分两类：**外部行为**（库/引擎的既成事实，读本项目代码看不出来）与**判据**（由外部行为推导、必须同时满足多条边界）。

---

## 一、FFmpeg 读包与 demuxer 行为

### 1.1 `av_read_frame` 没有「放回去」这个操作

`av_read_frame` 一旦返回，demuxer 的读位置**已经推走**，没有任何 API 能把包还回码流。
所谓「就地归还」（`packet.close()` 只释放 AVPacket 结构）**不等于**把包放回流里——它只是释放内存，
包承载的数据已从码流消失。

⇒ 轮次作废时读出来的包属于**旧位置**：不能喂给新位置的解码器（参考帧会错），也补不回来。
判据是对象身份：**只有换位置的轮次**（`play` / `seekImpl`）才需要丢弃手里的包；
「只是暂停」不换位置，包照常送解码器入队——暂停是「先别送显」，不是「不要解码」，
所以判据**不能掺**「本机是否在播」这类状态。

### 1.2 `avio` 只把 `AVERROR_EOF` 与负数当读终止

`libavformat/aviobuf.c` 的 `fill_buffer`（本地 `cxx/ffmpeg/ffmpeg/libavformat/aviobuf.c:551-558`）：

```c
len = read_packet_wrapper(s, dst, len);
if (len == AVERROR_EOF) {
    s->eof_reached = 1;              // 不修改 buffer，允许 seek 回退重读
} else if (len < 0) {
    s->eof_reached = 1;
    s->error = len;
} else {
    s->pos += len; ...               // 返回 0 走这一支：缓冲区仍空、继续要数据
}
```

⇒ 自定义 `AvIO.read` **返回 0 是「读空，继续要数据」**，不是 EOF。
`wav` / `pcm` 这类没有重试逻辑的 demuxer 会在文件尾**无限空转**：avformat 线程 100% CPU、
不报错、不返回，且每次空转都伴随一次缓冲区分配。

⇒ `RandomAccessFile` 的 `-1`（EOF）必须翻成 `AVERROR_EOF`；「打不开」的 `-1` 是另一回事，
在更早的分支返回，两者不能混。

同理，`seek` 的 `AVSEEK_SIZE` 分支拿不到长度时必须返回 `-1`（AVERROR）——
**返回 0 会被当成「长度为零的流」**，后续 seek 全部判越界。

### 1.3 作废收场必须返回正数，不能返回错误码

被外部叫醒的那次读（seek / close 作废）**必须返回正数**，且取本次请求的长度。
三档后果（推演见 §5.2）：

| `read` 返回 | ffmpeg 走向 | 后果 |
| --- | --- | --- |
| 负值（`AVERROR_EOF` / `AVERROR_EXIT` / `AVERROR(EAGAIN)`） | `ff_read_packet` 的 `err < 0` 分支（`demux.c:660`）判据不成立 ⇒ `continue`（`:678`） | demuxer **再读一轮**；那次读没有 abort 可用 ⇒ 开新 range 会话干等闲置超时，`closeAsync` 拖到 **8 s** 量级 |
| `0` | avio 的 bypass 直读分支 `size -= 0` 恒真（`aviobuf.c:639`） | **原地死循环**，连开流都过不去 |
| 正数 | `read_packet` 正常返回 ⇒ `handle_new_packet` ⇒ 当场 return（`demux.c:687`） | 不循环，`closeAsync` **43 ms** 返回 |

「谎报读满」是安全的：返回的字节并没有真读出，但那个包必然被丢弃（读作业把包交进预读通道时，
通道已被作废掀掉，`send` 当场抛错、读作业收场）。返回正数只是让 demuxer **停下**的手段。

### 1.4 容器级 seek 会把目标折回流时基

`avformat_seek_file` 在容器没实现 `read_seek2` 时（mp4/mov 就是）回退到 `av_seek_frame`，
内部用 `av_rescale`（`AV_ROUND_NEAR_INF`，即**四舍五入**）把微秒目标折成默认流的 tick。
**偏移量不足半个 tick 会被折回原格**，容器给同一个关键帧——seek 像没生效。

⇒ 做「单帧步退」时，步长 ε 必须同时满足两条边界：

- **下界：大于半个 tick**。否则被折回原格，一帧都不丢。
- **上界：小于一个帧间隔**。否则丢帧收敛会越过前一帧，一下退两帧。

两条同时满足的取法：**ε = 3/4 个 tick**（半格 < 3/4 tick < 1 tick ≤ 帧间隔）。
拿得到帧间隔时用 3/4 × 帧间隔；冷启动还没量到帧间隔时，直接用流时基算 3/4 tick
（相邻帧的刻度值必然不同 ⇒ 间隔至少一整格，上界自动成立）。

### 1.5 `AvIO.Handler.open` 不许抛异常

它跑在 native 的 `io_open` 回调里。Kotlin 异常会 pending 在 JNI 上没人接，
**下一个 JNI 调用直接 abort 整个 JVM**（hs_err 的 Problematic frame 在 `initNative`）。
「打不开」要返回一个 read/seek 恒失败的 IO，让 `avformat_open_input` 自己以 I/O 错误收场——
失败清理路径照常走完。

---

## 二、native 输出端：每流一块缓冲

`cxx/ffmpeg/ffmpeg.cpp` 的 `SWContext` **每种流只有一块输出缓冲**（`videoBuffer` / `audioBuffer`）：

- `postFrame` 把解码帧喂进 `sws_scale` / `swr_convert`，**整体覆写**那块缓冲；
- `getBuffer` 才把它拷给平台（`SetByteArrayRegion` 进 JVM 数组）。

⇒ 三条推论，每条都是静默失效：

### 2.1 同一时刻只允许一轮播放在飞

两轮同时走到 `postFrame` / `getBuffer` 中间就串：上一轮送显读到这一轮的像素。
被 seek 作废的轮次若在下一轮「复活」，还会补一次 flush，画面出现重复帧并整体落后。

⇒ 开一轮独占播放的语义是**后到者顶掉先到者**：先把上一轮作废、等它收干净，再开这一轮。
「本轮还作不作数」的判据**只看对象身份**（一轮 == 一个 PTS 对象），
**不能掺**「本机是否在播」——暂停不换对象，暂停后手里的包仍属于当前位置。

⇒ **不要用 `Mutex` 解**：它排队，上一跳转的结果照样先落地再被覆盖，
且只盖住被包的入口（`seekTo` / `play` / `resume` 有几个入口没数清楚就会漏）。

### 2.2 同一轮内相邻两帧必须先等上一帧送显完

本帧先覆写共享缓冲、而上一帧的 `flush` 还等着抄 ⇒ **上一帧显示成本帧的像素**；
下一帧又趁「等视频追主时钟」的空档写进，于是**每一帧**都错位一帧。

⇒ 解码作业里「等上一帧送显完成」的那次 `join` 不能省，且必须排在 `postFrame` **之前**。

### 2.3 送显时机与丢帧收敛的先后

收敛排 `flushFrame` 之后就变成「先上屏再决定丢不丢」，主时钟被错误锚定后视频干等。

⇒ 丢帧判据必须排在 `flushFrame` **之前**。口径照 ffplay 的 `frame_drops_early`
（`cxx/ffmpeg/ffmpeg/fftools/ffplay.c:1842-1852`）：`diff = dpts - master_clock`，
`|diff| < AV_NOSYNC_THRESHOLD && diff < 0` ⇒ 解码后立刻丢。`AV_NOSYNC_THRESHOLD = 10.0` 秒
（fftools 自有常量，不在 libavutil）。

⇒ **音频也要丢开头连续段**。落点是**容器级**的：seek 到 5.0s 时音频首包可能落在 3.90s、
视频关键帧落在 4.0s。音频不丢会把主时钟按它的上屏时间戳重新锚定到目标之前，视频于是干等。
丢开头连续段没有 audible 缺口——声卡刚被 flush 过。判据用 `AvFrame` 自身时间戳
（native 已折算成 `AV_TIME_BASE` 微秒，与 seek 目标同单位）。

⚠️ **这条的「音频那半」目前没有测试守卫**，且不是加个音频流就行：
`FFPlayerSeekFrameSequenceTest` 的 seek 用例全是纯视频流（主时钟只被 `resyncTo` 锚定，
走不到「音频把锚点拉回去」那条路），`FFPlayerAudioOnlyTest` 只从 0 播到 EOF、不 seek。
真要补，需要三样东西（曾有一版完整实现 `FFPlayerSeekConvergenceTest.kt`，依赖已不存在的
`AvPlayback`、不能直接恢复；本机暂存副本在 `build/_reverted/` 下 —— 不在版本控制里、`clean` 即丢）：

1. **读主时钟锚点**：`PTS.relate` 是 private，公开面拿不到 —— 要么反射（原版做法），
   要么给 `FFPlayer` 开一个测试期观测点；
2. **音频节拍**：音频帧密度是视频的十倍以上（44.1kHz vs 25fps），假声卡不按真实耗时
   消耗就会以解码速度狂奔，把主时钟推到视频前面 ⇒ 视频帧全被判过期丢掉。
   节流必须是**阻塞式**（对齐两端真身：Android `WRITE_BLOCKING`、桌面 `SourceDataLine.write`）；
   用挂起 `delay` 会改变 `playJobs` 的并发拓扑，实测仍饿死视频帧。
3. **落点取关键帧上**：seek 到两个关键帧之间时收敛期会多丢两三帧（队列里已有更晚帧
   ⇒ 当前帧被判过期），那是固有行为，会与「一帧不落」的断言冲突。
   音频侧仍分得开：素材音频首包 ≈3.90 s，落点 4.0 s ⇒ 生效时首帧音频 ≥ 4.0 s、失效时 ≈3.90 s。

2026-10 尝试过在现有用例上直接加音频流，失败在上述第 2 条，已回滚。下次动手前先读这一段。

### 2.4 撑住整轮的挂起点不能抽走

`try { 开灯 } finally { 熄灯 }` 结构里，若没有挂起点撑住整轮，「灯」会在没人撑时静默熄掉：
`finally` 紧跟其后执行，表现为一帧都不送显（超时在「等第一帧上屏」）。

⇒ `try` 块里必须有一个撑住整轮的挂起点（循环 / `await` / `join`）。
抽走本体时先问「**现在谁在撑这盏灯**」——把循环搬去常驻任务、`try` 只剩两行，就是这类事故的典型形状。

---

## 三、桌面视频上传：每帧分配

桌面端每帧 `Image.makeRaster(...)` 新建一块 Skia 自管对象、`drawImageRect` 时再上传一次 GPU，
一帧两次 CPU 拷贝 + 一次分配。这些 native 对象靠 `Cleaner` 回收，而 Cleaner 等 GC 触发——
Java 堆实际占用远低于上限时 GC 可能十秒才来一次，于是 RSS 涨到 GB 量级再成批释放（锯齿），
且释放后不还给 OS。

⇒ 观测方法：`jcmd GC.heap_info` 看 Java 堆（只有几十 MB，与 RSS 对不上是正常的——
差值就是 native 侧）；`jcmd GC.class_histogram` 数 `CleanableImpl` / `Managed$CleanerThunk`
的个数，那是在数「已失去 wrapper、等 Cleaner 回收的 skiko 对象」；RSS 要**采样成序列**看形状，
锯齿 = 靠 GC 回收的堆积，单调涨 = 真泄漏。

⇒ 收敛方向与现状见 `TODO.md` B26（持久 GPU 纹理 + 每帧 `updateTexture`）。
**真正零每帧传输**要 native 直接产出 GPU 纹理，那是 C++ 侧改造。

⇒ Android 端没有这个问题：视频分支直写 Surface，根本不经过 JVM 数组。

---

## 四、OkHttp 传输层

版本：OkHttp **5.3.2**（`gradle/libs.versions.toml`），桌面与 Android 共用一份实现
（`platform/src/jvmShared/.../common/Http.kt`；okhttp 只有 jvm / androidJvm 两个真实 target）。

### 4.1 拉取式：不读就不下，一次读只到一小段

`Http.request` = 拼好 `Request` + `Call.execute()`（阻塞搬到 `Dispatchers.IO`）→ **响应头到就返回**，
body 是一条按需读的流（`Response.body.byteStream()`），读到哪下到哪。
既没有「拿到响应时已经在拉 body」这回事，也没有任何预读缓冲。

⇒ **整包缓冲是这层最容易退回去的形态**：它会表现成远程播放迟迟不出画面（等整包下完）、
内存随文件大小走、无任何报错。判据是
`HttpStreamingTest.firstReadReturnsLongBeforeTheWholeBodyArrives`（第一次读的耗时 < 整包下发时长的一半），
以及 `HttpBodyDownloadTimingProbeTest`（不读时本地存量为 0、读全程峰值也不超过一个 avio 缓冲）。

⇒ 同一入口两种读法要分流：流式边下边用走 `Response.read`（内部等数据到再读一段），
一次性拿完走 `Response.readAll`（读完即 `close`，插件 JS 的 `Response.arrayBuffer()` 走这条，
用例 `ReadAllClosesProbeTest`）。**混用会串**——读完再 `read` 只会拿到流尾。

### 4.2 闲置超时 = 两次读之间，由 okhttp 的 `readTimeout` 实现

**约束：判据是「两次数据包之间有没有出字节」，不是「请求总共花了多久」——整条流可以播几小时。**

okhttp 的 `readTimeout` 恰好就是这个语义（okio 的 `AsyncTimeout` 在两次 `read` 之间计时），
于是本层直接把它设成 `Http.SOCKET_TIMEOUT_MS`（8 s，取 ExoPlayer 的 `DEFAULT_READ_TIMEOUT_MILLIS`），
并把它抛出的 `SocketTimeoutException` 翻成 `Http.IdleTimeoutException`。

⇒ **不在应用层自己另起一套计时器**：引擎给的 `readTimeout` 正好就是这个语义，
应用层再计一次只会多出一个与它不同源的判据。

⇒ **超时即这条连接作废**（okio 超时会关掉 socket），所以 `HttpIO` 的「超时 ⇒ 关掉、换一条重试」
（`readSuspend` 的 `catch (Http.IdleTimeoutException)`）正好接住，不必再谈「连接还能不能留」。

### 4.3 叫醒阻塞读的唯一手段是关掉连接

阻塞在 socket 上的 `read` **不看协程取消点**。取消一个已经落在这层阻塞里的读，能做的只有
`Call.cancel()`，而它就是关掉那条连接。

⇒ 于是「本轮作废」的代价是**那条会话当场作废**：`Http.Response.isClosed` 变真（取消也算作废），
`HttpIO` 据此丢掉它、下一条读重发（§5.3）。

> **DON'T:** 「不 cancel，让后台线程把这次读读完」。那样确实会从流里**真的取走一批字节**，而协程
> 已被取消 ⇒ 网络游标不推进、那批字节也没交付给调用方，之后的字节全部错位（**静默串数据**）。
> §5.2 那个「谎报读满」的安全前提正是「这批字节没有被真的取走」。

---

## 五、HTTP 流的读取与会话生命周期

`HttpIO`（`acg/player/HttpIO.kt`）与 `Http.Response`
（`platform/src/commonMain/.../common/Http.kt`，实现在 `jvmShared`）的实现细节。
源码里只留契约与指针，推演过程在此。通用的 ffmpeg / OkHttp / 协程判据分别在 §1.2/§1.3、§4、§6，
本节的差异点与推演在此，不重复列通用结论。

### 5.1 必须在 IO 层阻塞等

**约束：`AvIO.read` 的回调来自 native `av_read_frame`，数据没到就一直等——没有「稍后重试」这个返回值。**
返回值的两种合法解（`HttpReadContractTest` 钉着）与作废收场的正数返回，后果表见 §1.3
（负值再读一轮、0 原地死循环、正数当场 return）。

**闲置超时由传输层的 `readTimeout` 计时**（8 s，「两次读之间」，见 §4.2），不在这里另起一套计时器：

> **DO:** 判据是「两次数据包之间有没有出字节」，不是「请求总共花了多久」——整条流可播几小时。

> **DON'T:** 用 `AVERROR(EAGAIN)` + `AVIO_FLAG_NONBLOCK` 绕开。`AVERROR(EAGAIN)` 和 `AVIO_FLAG_NONBLOCK`
> 只长在 `avio.c` 的 `retry_transfer_wrapper` 里，且该层只包 `URLProtocol`（`h->prot->url_read`）；
> 本项目走 `avio_alloc_context` + 自有回调，不经过它。上游头文件 `avio.h` 的 `avio_alloc_context`
> 已说明：`read_packet` "For stream protocols, must never return 0 but rather a proper AVERROR code."

### 5.2 每次 read 有一条私有作废信号

**约束：「这一轮不要了」（seek/close，见 `AvFormat.resetChannel`）发生在读所在线程之外，
而读卡在 `av_read_frame` 里——协程取消够不到它（取消协作式，阻塞 JNI 调用里无挂起点），
所以必须有一条能被外部叫醒的路。**

> **DO:** `AvFormat.resetChannel` **必须**在 `formatDispatcher` **之外**的线程调。`ThreadDispatcher`
> 底层是 `Executors.newSingleThreadExecutor`——纯排队、没有 event loop 能借道，所以在 avformat 线程
> **之内**调、或经 `Pointer.closeDeferred()` 的 `submit { }` 排进它的队列，`abort` 就排在要叫醒的那个
> 读作业后面，永远跑不到。`AvFormat.releaseImpl` 因此**不能**「顺手」在里面补一次——它正是经
> `submit { }` 排进同一根线程。

形态：一次读一条私有信号，`Handler` 只持一个投递口（`onSignal` 登记的 `raise`）：

- 有读在等时 `abort()` 投到那条读的信号 ⇒ 建立期/等数据/白读推进**全被覆盖**（它在那一次读内部新建，覆盖那次读全部等待）；
- 没读在等时（两条分片之间、seek 间隙）投递口空 ⇒ **no-op**；
- 下一次读挂的是**新**信号 ⇒ 收不到上次的作废。

「没人在等时投 = no-op」「下一次读收不到上次作废」由「信号挂在读上、生命周期即那次读」天然给出，
不需配套的清旗标动作。旗标那种跨轮共享状态必须有人显式清，而「清早了/忘了清」都是难查时序病。

> **DO:** 用 `CompletableDeferred`。不是 `StateFlow<Boolean>`（状态长期存在、投过一直 `true`、须显式清，
> 而「清早了/忘了清」都是难查时序病）；不是 `Channel`（只需「投一次、之后一直可见」，Channel 反而引入
> 「投信号」与「挂订阅」之间的漏唤醒窗口，投时没人挂上就丢）。

单投递口够用的前提：同一时刻只有一次 `read` 在等——HLS 换分片是「上片读到尾并关掉、才开下片」，
native 单线程串行调 `read`，旧那条 `read` 早已返回、投递口已被它摘掉，不会与新那条并存。**不必攒列表。**

**叫醒方式是 `cancel`，不是 `throw`**（通用判据见 §6.1）：

```kotlin
val signal = AbortSignal()
val job = async { readSuspend(buf) }
val abort = async { signal.await(); job.cancel() }   // 只 cancel，不作声
handler.onSignal { signal.raise() }
try { job.await() }
catch (e: CancellationException) { buf.size }        // 谎报读满，见下一小节
finally { abort.cancel(); handler.onSignal(null) }
```

> **DON'T:** 哨兵 `throw`。它挂在 `runBlocking` 的 scope 上，是**子协程**；`async` 里抛出的异常不是
> 「留给 `await` 取的值」，而是**未捕获异常**，会 `parentCancelled` 向上取消父 `BlockingCoroutine`
> ⇒ 整个 scope（含 `readSuspend`）全被拆掉，外层那个 `catch (e: CancellationException)` **一次都没跑**，
> `read` 直接以 `Int.MIN_VALUE` 收场（实测形状：`kotlinx.coroutines.JobCancellationException:
> Parent job is Cancelling; job="coroutine#26":BlockingCoroutine{Cancelling}`）。

> **DO:** `async` 只用来挂等待，不用来报错。对照：传输层里把阻塞 IO 搬上 IO 线程用的是
> `withContext(Dispatchers.IO) { suspendCancellableCoroutine { … } }`，取消经 `invokeOnCancellation`
> 接到 `Call.cancel()` 上（§4.3）——既没有 `async`，也没有把异常「抛出去」这一说。

> **DO:** `finally` 里两句都不能省——`abort.cancel()`（哨兵正常收场时永远挂在 `signal.await()` 上，
> 不取消它 `runBlocking` 会一直等）、`handler.onSignal(null)`（摘投递口——正常返回/作废收场/真故障三条路都要摘，
> 否则下一条会话会被上一条的死读误伤）。

**作废收场返回正数 `buf.size`，不是任何错误码**（三档后果表见 §1.3）：被叫醒的那次读必须返回一个**正数**，
且取本次请求的长度 `buf.size`（`buf` 是调用方的、并没有真的填），这是让 demuxer **就此收手**的唯一办法。
「谎报读满」安全：返回字节没真读出，但这数据必被丢——读作业交进 `AvFormat.packetChannel` 时通道已被
`resetChannel` 掀掉，`send` 当场抛错、读作业收场，包到不了解码器。「谎报读满」只是让 demuxer 停下的手段。

> **DO:** 值取 `buf.size` 而不是随便一个正数。`append_packet_chunked`（utils.c）的 `do/while`
> 靠 `ret == read_size` 才继续凑包（`ret != read_size` 即 `break`），一次请求的长度正好对上。

### 5.3 abort 叫醒了读，代价是那条会话作废

**约束：会话去留只看「这条会话还出不出字节」（闲置超时，§4.2）；而叫醒一次阻塞读只能靠关连接（§4.3）。**

⇒ 作废分两步收口，两步都在同一条线程上，**没有跨线程拆对方正在用的东西的竞态**：叫醒是哨兵 `cancel()`
掀掉那次读（连带 okhttp 那条连接）；丢掉那条会话是 `HttpIO.read` 自己的收场：

- 判据用 `Http.Response.isClosed`（取消也把它置成作废）：`catch (CancellationException)` 里只丢
  **真的被掀掉**的那条。作废也可能落在建连上，那时手上这条还活着、不该陪葬；
- 下一次读 `cachedRsp == null` ⇒ 直接在逻辑游标上重发（`HttpAbortTest` 那条用例的后半段）。

⇒ **健康会话一样保不住**：被掀的那一刻字节还在 socket 上、没交付给任何人，救不回来；不 cancel 又会让
那批字节被真的取走（§4.3 的 DON'T）。判据记在
`HttpStreamingTest.abortWakesAHealthyReadButDropsTheSession`（请求数 1 → 2，下一次读在新会话上拿到正确字节）。

⇒ 没人在等时 `abort` 仍是 no-op（`HttpStreamingTest.abortWithoutAnyWaitingReadKeepsTheSameSession`）——
FFPlayer 每次 seek / 单帧步进都会 `abort`，而那种时刻通常没有读卡在网络上，会话照旧复用。

> **DON'T:** 把 `close()` 与 `abort()` 合成一件事。`close` 是「这条 IO 到此为止」、无条件释放；
> `abort` 是「这一轮不要了」，只是那一下顺带把会话掀了。合成一个会把「作废」错当成「关会话」。

### 5.4 会话不挂任何协程树

**约束：一条会话（一条 `Http.Response`）不持有协程、也不持有 scope —— 它只是一条连接。**

拉取式下这不需要额外设计：建连（`Call.execute()`）与每一次 `read` 都在**调用方自己的协程**里跑，
只是把阻塞段用 `withContext(Dispatchers.IO)` 搬上 IO 池（§4.3）。没有后台在填缓冲的协程，
也就没有「`runBlocking` 等子协程、子协程等 `Response.close`」那个环。

> **DO:** 阻塞段一律走 `Dispatchers.IO`，不要丢给 `Default`：建连 / TLS 握手 / socket 读是**真阻塞的 IO**，
> `Default`（核数那么大的池）在多路并发时会被占满。

### 5.5 白读推进（`getResponseBlocking` 里的 `advance`）

**约束：seek 之后网络游标没到位时，要在这条响应上白读白丢地往前推。三条约束：**

1. **先登记 `cachedRsp`、再推进**——下面 `reused` 的判定靠的就是「推进之前 `cachedRsp === rsp`」，
   `close`/`seek` 也随时可能从外面看这个字段。若「推完再登记」，推进整段期间 `cachedRsp` 都是旧值
   ⇒ `reused` 判错，且「这条会话已归本 IO」在半途不成立。
2. **新建立的那条必须自己收干净**——抛出时它已经登记进 `cachedRsp`，但连 offset 都没走到、
   不是「可复用的缓存会话」（复用那条的存亡归 `close`/静默自愈管，顺手关掉会伤到还没用完的会话）。
   建它与推进是前后两段、异常只从推进那段抛出 ⇒ 收场只能由发起它的这次读自己做，`cachedRsp`
   也必须在同一处摘掉（留着它，下次读会拿一条没推到位的会话当地基）。
   不收就是一条永久吊在服务端的连接（实测：服务端 `entered=3`/`exited=2`）。
3. **每一段都走 `rsp.read`**，不要绕开它去直接摸底层流——只有它带闲置超时（§4.2）、
   且落在本协程自己的取消点上。

**换会话的判据是「白读的那部分里，还得从网络上取多少」**——即 `offset - rsp.offset - rsp.availableForRead`。
**本地已有的那部分要减掉**：白读它们是纯内存 memcpy、零网络成本，算进代价会高估，于是「其实一个字节都不用下」
的时候也去重开会话，而重开要付 TCP 往返 + TLS + 响应头的固定成本。

> **DO:** 这条判据只在「复用缓存会话」时生效，而那条会话的缓冲量必定已知 ⇒ 扣减量可信。
> 新建那条的存量恒为 0、扣减退化成 0 ⇒ **只会更倾向换会话**，最坏等于扣减前。

**附：`Response.offset` 起点为什么从 header 推。** 起点**必须**是服务端在 header 里**亲口说的**那个数
（206 的 `Content-Range` 起点；没有这个头——也就是 200 从头给——就是 0），**不能**由调用方灌进去。
灌进去的话，`HttpIO` 建完响应还得追一次赋值，而漏一处就**静默重开一条会话**、从头重发。

解析刻意走**字符串**（`substringBefore('-')` / `removePrefix("bytes")`）：`Content-Range` 的形态
（`bytes 3280000-3407871/3407872`）与请求侧 `Range: bytes=0-` 不同，没有现成的解析器可用。

### 5.6 本地存量（`availableForRead`）的三点

`Http.Response.availableForRead` 量的是「已经从网络上取到本地、ffmpeg 还没拿到」的那部分
（拉取式下就是 okio 缓冲里的字节；判据级用例
`HttpReadContractTest.forwardSeekWithinBufferedRangeKeepsSessionButFarSeekDoesNot`，探针
`HttpBodyDownloadTimingProbeTest`）。下面三条每一条都能让上层判断错：

1. **它是 0 就是「网络真的还没被读」**，不是「本对象还没拿到流引用」那类假 0。拉取式不预读：
   一次 `read` 都没调之前本地一个字节都没有（实测：空等 500 ms 后仍是 0）。
2. **空等不会让它涨。** 要它变大只能靠读，而读会同时推进 `Response.offset`。
3. **上限极小**（okio 一次搬运一个 8 KiB 段；实测读满 2 MiB 全程峰值 ≤ 32 KiB）⇒ §5.5 那条判据里的
   扣减项事实上恒为 0，判据退化成「超过网络游标一个 avio 缓冲就换会话」。这不是缺陷：预读一小，
   「已缓冲」才诚实（拿它当「快下好了」的判据在任何一档下都错），代价只是跨会话时多走白读推进（§5.5）。
   **别为了让它变大去加预读缓冲** —— 预读要另起协程在后台填，等于把「会话挂协程树」那套（5.4）搬回来，
   而且「已缓冲」会变成「快下好了」的假象（判据在任何一档下都错）。

---

## 六、协程

### 6.1 `async` 块里用 `throw` 表达控制流会拆掉父 scope

`async` 里抛出的异常**不是**「留给 `await` 取的值」，而是**未捕获异常**：
它会向上取消父 `Job`，父 `BlockingCoroutine` 进入 `Cancelling`，
**整个 scope**（含同 scope 上其他协程）被一起拆掉，外层那个 `catch` 一次都没跑。
`runBlocking` 最终抛的是取消异常（`JobCancellationException: Parent job is Cancelling`），
调用方拿到的是「没返回」的哨兵值。

⇒ 通知等待方用**目标协程的取消**（`job.cancel()`）或
`CompletableDeferred.completeExceptionally()` 再让目标 `await()`——
别让异常从子协程漏进父 scope。**`async` 只用来挂等待，不用来报错。**

⇒ 安全形态：`async { … }` + `select { onAwait / onTimeout }` + `finally { data.cancel() }`。
`select` 只挑一支、**不**替你取消其余；而 `coroutineScope` 会等**所有**子协程，
落选那支不取消就挂死。

### 6.2 作废预读通道用 `cancel()`，收场才用 `close()`

`Channel.close()` **不唤醒**停在 `send` 上的读作业（预读灌满、无人消费时它停在那儿），
随后的 `join` 死等；`close()` 还让已预读的包投递成功、落进没人收的通道 ⇒ 漏包。
`cancel()` 以 `CancellationException` 收场，已预读的包由 `onUndeliveredElement` 归还。

⇒ 作废三步顺序固定，换序即死等：**`abort` → `cancel` 通道 → `join` 读作业**。
`abort` 必须在归属线程**之外**的线程调：`ThreadDispatcher` 底层是
`Executors.newSingleThreadExecutor`，纯排队、没有 event loop 能借道——
在归属线程内调（或经 `closeDeferred()` 的 `submit { }` 排进它的队列），
`abort` 就排在它要叫醒的那个读作业**后面**，永远跑不到。

⇒ 唯一合法例外是 seek：它与 seek 的 native 调用落在同一段不可打断的 `withPtr` 块里，
都在同一条单线程上 ⇒ 那段没有并发访问者。

⇒ 也因此：关闭路径上「叫醒读作业」必须排在「投递销毁」之前，且销毁前要确认读作业已死
（拿着 `AVFormatContext` 的读作业与 `avformat_close_input` 相遇就是 use-after-free）。

### 6.3 `CompletableDeferred(null)` 会挑错重载

`CompletableDeferred` 有两个重载：`CompletableDeferred(value)` 与 `CompletableDeferred(parent: Job?)`。
写 `CompletableDeferred(null)` 时，一旦泛型被实例化成非空类型，解析会挑中 **parent 重载**——
于是这个 Deferred 挂在父 Job 上、从没人 complete，`join` / `await` 永久挂起。

⇒ 构造「立即完成、值为 null」的 Deferred 必须写**命名实参**：`CompletableDeferred(value = null)`。

⇒ 排查「对端线程空转（park 在 `LinkedBlockingQueue.take`）+ 主线程死等」时，
先查 Deferred 有没有人 complete——别据此误判成 native 卡死。
取证步骤见 [debugging.md](./debugging.md)。

### 6.4 QuickJS 桥不许用 `import()` / `require()`

本工程的 QuickJS 走 JNI 桥（`cxx/quickjs/quickjs.cpp` 的 `JS_SetModuleLoaderFunc`），
**动态 `import()` 会把进程打崩**：模块不存在时命中 `assert(js_rc(p)->ref_count == 0)`
（`quickjs.c:6425`），模块存在时又在 `JS_FreeRuntime` 命中
`assert(list_empty(&rt->gc_obj_list))`（`quickjs.c:2464`）—— 引用计数账本被打乱。
构建开着 `-DDUMP_LEAKS` 且未设 `NDEBUG`，断言是活的。

⇒ 能力一律内联进 `init.js`，不建 `files/js/module/webview.js` 这类动态模块。

⇒ ⚠️ `files/js/module/crypto.js` 是压缩的单文件 bundle，内含 `typeof require` 守卫的 Node 兜底分支。
本项目**没有链接 quickjs-libc**（`quickjs.cmake` 的源文件列表无 `libc.c`）、`require` 也不是全局函数，
守卫不成立 ⇒ 那支目前是死代码。**别去动它**：一旦引入 libc，守卫立刻成立，abort 就回来了。

### 6.5 `packetChannel` / `readerJob` 不加 `@Volatile`

**约束：`AvFormat.packetChannel` / `readerJob` 跨线程读写，但无数据竞争、刻意不加 `@Volatile`。**

`@Volatile` 只保可见性，保不了「读到的是不是这一轮的」这个逻辑判据 —— 拿它去修「偶发读到
上一轮粘住的通道、静默返回 EOF」加了也白加。真正的可见性依靠两处同步点：

- 正向：写（`getPacket` 建通道 / 记 `readerJob`）→ 读（`seekTo` 的 `resetChannel`）隔着
  「换位置入口先过 `takeOverPlayback()` → `pause` → `playingJob?.join()`（等跑 `getPacket`
  的协程）」，随后 `withPtr` 派发再过一次执行器队列；
- 反向：`withPtr` 返回的恢复边。

| 访问 | 线程 |
| --- | --- |
| `getPacket` 建通道、记 `readerJob`、消费 | 调用方线程 |
| 被 `FFPlayer.closeAsync` 调（`resetChannel`） | `playerDispatcher`（同线程） |
| 被 `AvFormat.seekTo` 调（`resetChannel`） | `formatDispatcher`（`withPtr` 块内）⇒ 跨线程 |

> **DO:** 这条不变量依赖 `FFPlayer.pause` 的 `playingJob?.join()` 与换位置入口先过
> `takeOverPlayback()`；真要拆这两条前提，换显式同步原语（`AtomicReference` + CAS 建通道），
> 不是补 `@Volatile`。

> **DON'T:** 给 `packetChannel` / `readerJob` 补 `@Volatile` 当修复。
