# 播放链路的不变量与外部依据

只收录**已定稿的知识**：ffmpeg / kotlinx / ktor / skiko 的**外部行为依据**，以及由它们推导出的判据。
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

## 四、ktor 流式读取

版本：ktor **3.5.2**（`gradle/libs.versions.toml`）。

### 4.1 便捷入口会整包缓冲

`HttpClient.request(...)` → `HttpStatement.execute()` → `fetchResponse()`，
内部有一句 `call.save().response`（注释 "Save the body again to make sure that it is replayable…"）
⇒ **body 被完整读进内存**。表现为远程播放迟迟不出画面（等整包下完）、内存随文件大小走，
无任何报错。

⇒ ktor 另有一条不 save 的路：`HttpStatement.execute(block)` → `fetchStreamingResponse()`
→ `builder.skipSaveBody()`。**读必须发生在 block 期间**——block 返回后 ktor 随即 cleanup。

⇒ 同一入口两种读法要分流：流式边下边用走 `Response.read`（内部等数据到再读一段），
一次性拿完走 `Response.readAll`。**混用会串**——读完再 `read` 只会拿到流尾。

### 4.2 `awaitContent(min)` 的参数是最少字节数，不是超时

`ByteReadChannel.awaitContent(min)` 实现是 `source.request(min)`：
挂起等到**凑够 min 字节**或通道关闭，**没有超时重载**（`javap` 确认只有一个 `(int, Continuation)` 签名）。
两个引擎（Java / OkHttp）的 body 通道都是 `ByteChannel`，行为一致。

⇒ 把毫秒数当参数传进去**编译通过**，健康流上看起来也正常（数据一直在来），
但数据停住的静默处**永远醒不来**——它在等凑够那个字节数，abort 叫不醒、旗标查不到。

⇒ 「等一会儿再查旗标」要自包超时：`withTimeoutOrNull(ms) { channel.awaitContent(1) }`
（超时取消干净、不 consume 字节），或等价的 `select { onAwait { … }; onTimeout(ms) { … } }`。

⇒ 也别直接用 `readAvailable`：通道空时它内部是**裸的 `awaitContent()`**——
既没有超时、也不在调用方自己的取消点上，直接调就卡死在那里。

### 4.3 引擎层的「读闲置超时」两平台对不上

桌面（`java.net.http.HttpClient`）**没有**「读闲置超时」这个概念，装上去被静默忽略；
OkHttp 自带 10 s 默认值，与 ktor 的取值不一定同源。

⇒ 两端都不装，统一由应用层自己计时：判据是「两次数据包之间有没有出字节」，
不是「请求总共花了多久」——整条流可以播几小时。`HttpTimeout` 的 socket 超时显式设成无限。

---

## 五、HTTP 流的读取与会话生命周期

`HttpIO`（`acg/player/HttpIO.kt`）与 `Http.Response`（`acg/common/Http.kt`）的实现细节。
源码里只留契约与指针，推演过程在此。通用的 ffmpeg / ktor / 协程判据分别在 §1.2/§1.3、§4、§6，
本节的差异点与推演在此，不重复列通用结论。

### 5.1 必须在 IO 层阻塞等

**约束：`AvIO.read` 的回调来自 native `av_read_frame`，数据没到就一直等——没有「稍后重试」这个返回值。**
返回值的两种合法解（`HttpReadContractTest` 钉着）与作废收场的正数返回，后果表见 §1.3
（负值再读一轮、0 原地死循环、正数当场 return）。

**闲置超时不装进 `HttpTimeout`**：读闲置超时由 `Response.read` 里自己的 `select onTimeout` 计时
（`SOCKET_TIMEOUT_MS = 8_000L`），不交给两端 `HttpTimeout`。判据与两平台对不上的原因见 §4.3：

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
> ⇒ 整个 scope（含 `readSuspend`）全被拆掉，外层那个 `catch (Http.AbortedException)` **一次都没跑**，
> `read` 直接以 `Int.MIN_VALUE` 收场（实测形状：`kotlinx.coroutines.JobCancellationException:
> Parent job is Cancelling; job="coroutine#26":BlockingCoroutine{Cancelling}`）。

> **DO:** `async` 只用来挂等待，不用来报错。对照：`Http.Response.read` 里也有 `async { data }`，
> 它安全是因为那里用 `select` 挑一支、`finally` 把落选支 `cancel()` 掉——异常从来没被「抛出去」过。

> **DO:** `finally` 里两句都不能省——`abort.cancel()`（哨兵正常收场时永远挂在 `signal.await()` 上，
> 不取消它 `runBlocking` 会一直等）、`handler.onSignal(null)`（摘投递口——正常返回/作废收场/真故障三条路都要摘，
> 否则下一条会话会被上一条的死读误伤）。

**作废收场返回正数 `buf.size`，不是任何错误码**（三档后果表见 §1.3）：被叫醒的那次读必须返回一个**正数**，
且取本次请求的长度 `buf.size`（`buf` 是调用方的、并没有真的填），这是让 demuxer **就此收手**的唯一办法。
「谎报读满」安全：返回字节没真读出，但这数据必被丢——读作业交进 `AvFormat.packetChannel` 时通道已被
`resetChannel` 掀掉，`send` 当场抛错、读作业收场，包到不了解码器。「谎报读满」只是让 demuxer 停下的手段。

> **DO:** 值取 `buf.size` 而不是随便一个正数。`append_packet_chunked`（utils.c）的 `do/while`
> 靠 `ret == read_size` 才继续凑包（`ret != read_size` 即 `break`），一次请求的长度正好对上。

### 5.3 abort 只叫醒、不掀会话

**约束：会话去留只看「这条会话还出不出字节」——由正在等的那个读自己判断（`HttpIO.read` 内那支
`select onTimeout`，见 `Http.SOCKET_TIMEOUT_MS`）。**

健康会话跨 abort 复用（seek 每次作废，不白重开连接），而「掀会话」永远由同一条线程自己做，
**没有跨线程拆对方正在用的东西的竞态**。

> **DO:** 下一次读面对的仍是那条停住的会话，它会闲置超时到点、自己换一条重试（`HttpIO.readSuspend`
> 的 `catch (e: Http.IdleTimeoutException)`）——压的是静默自愈路（`HttpAbortTest` 那条用例的后半段「恢复要走重连」）。

> **DON'T:** 把 `close()` 与 `abort()` 合成一件事。`close` 是「这条 IO 到此为止」，无条件释放
> （掀通道 + 放 `execute` 的 block 回去，ktor 随即 cleanup）；`abort` 是「这一轮不要了」，
> **不可逆的只有 close**。合成一个会把「作废」错当成「关会话」。

### 5.4 会话 scope 不能挂调用方 job

**约束：`Http.request` 里那条会话开在 `CoroutineScope(Dispatchers.IO)` 上，不挂调用方 job。**

- 调用方（avformat 线程）在 `runBlocking` 里等到 `[Response]` 再消费，而 `runBlocking` 会等**所有子协程**
  结束才返回（`BlockingCoroutine.isScopedCoroutine`）⇒ 会话等 `Response.close`、`Response.close` 等
  `runBlocking` 返回，**直接死锁**；
- `CoroutineScope(Dispatchers.IO)` 工厂在上下文没有 `Job` 时自己补一个 `Job()`，那个 `Job()` 同样**没有父**，
  `runBlocking` 的结构化等待够不着这棵树；
- **也别挂在本函数自己的 scope 上**——本函数是 `suspend`、直接跑在调用方协程里，`launch` 上去就是同一个死锁；
- dispatcher 定死 IO 池：建连/TLS 握手是**真阻塞的 IO**，丢给 `Default`（核数那么大的池）会在多路并发时占满。

### 5.5 白读推进（`getResponseBlocking` 里的 `advance`）

**约束：seek 之后网络游标没到位时，要在这条响应上白读白丢地往前推。三条约束：**

1. **先登记 `cachedRsp`、再推进**——下面 `reused` 的判定靠的就是「推进之前 `cachedRsp === rsp`」，
   `close`/`seek` 也随时可能从外面看这个字段。若「推完再登记」，推进整段期间 `cachedRsp` 都是旧值
   ⇒ `reused` 判错，且「这条会话已归本 IO」在半途不成立。
2. **新建立的那条必须自己收干净**——抛出时它已经登记进 `cachedRsp`，但连 offset 都没走到、
   不是「可复用的缓存会话」（复用那条的存亡归 `close`/静默自愈管，顺手关掉会伤到还没用完的会话）。
   建立它的那次 `Http.request` 调用早已返回 ⇒ `close` 与建立期的 job 两边都抓不住它，不收就是一条
   永久挂在 `done.await()` 上的 ktor 会话 + 漏掉的连接（实测：服务端 `entered=3`/`exited=2`）。
3. **每一段都走 `rsp.read`**（内部先等数据到、再读），不要直接用 `readAvailable`——后者在通道空时是
   裸的 `awaitContent()`，既没有超时、也不在本协程自己的取消点上。

**换会话的判据是「白读的那部分里，还得从网络上取多少」**——即 `offset - rsp.offset - rsp.availableForRead`。
**通道里已有的那部分要减掉**：白读它们是纯内存 memcpy、零网络成本，算进代价会高估，于是「其实一个字节都不用下」
的时候也去重开会话，而重开要付 TCP 往返 + TLS + 响应头的固定成本。量用 `Http.Response.availableForRead`（ktor 公开 API）。

> **DO:** 这条判据只在「复用缓存会话」时生效，而那条通道必定已建立（每条缓存会话至少被 `read` 过一次）
> ⇒ 扣减量可信。新建那条的存量恒为 0、扣减退化成 0 ⇒ **只会更倾向换会话**，最坏等于扣减前。

> **DON'T:** 用「把 `skipThreshold` 调大」代替扣减。阈值是常量，扣减按**实际缓冲量**算。缓冲量极度依赖
> 服务端发得多快——慢发（≈ 400 KiB/s）时通道**恒空**、判据退化成扣减前；只有**快发**时才铺开到 ≈ 1 MiB。
> 省不省得到全看生产侧落在哪一档。

**附：`Response.offset` 起点为什么从 header 推。** 起点**必须**是服务端在 header 里**亲口说的**那个数
（206 的 `Content-Range` 起点；没有这个头——也就是 200 从头给——就是 0），**不能**由调用方灌进去。
灌进去的话，`HttpIO` 建完响应还得追一次赋值，而漏一处就**静默重开一条会话**、从头重发。

解析刻意走**字符串**（`substringBefore('-')` / `removePrefix("bytes")`）而不是 ktor 的 `parseRangesSpecifier`：
后者解析的是**请求侧**的 `Range: bytes=0-`，形态不同。

### 5.6 通道存量（`availableForRead`）的三反直觉点

`Http.Response.availableForRead` 量的是 `readBuffer.buffer.size`，是「网络已经付过、ffmpeg 还没拿到」的那部分
（探针 `HttpBufferedAmountProbeTest`；判据级用例
`HttpReadContractTest.forwardSeekWithinBufferedRangeKeepsSessionButFarSeekDoesNot`）。
下面三条每一条都能让上层判断错：

1. **通道没建时它返回 0，而那个 0 不代表「网络上什么都没下」。** 下载与它无关：`Http.request` 拿到响应时
   引擎侧**已经在拉 body** 了（实测：一次 `read` 都没调、服务端已写出 704 KiB / 共 2 MiB）。0 的真正含义只是
   「本对象还没拿到通道引用」。⇒ 任何拿它当「网速慢/还没缓冲」的判据都是错的。
2. **存量不会自己涨——空等是等不来的。** 快发服务端 + 第一次读之后，存量稳定在 40879 B、**空等 10 s 一字节不涨**。
   因为 ktor 的写入先进 `flushBuffer`，而 `flushBuffer`→`readBuffer` 的搬运**只发生在读侧**
   （`moveFlushToReadBuffer`，由 `awaitContent`/`readAvailable` 触发）。⇒ 想让「已缓冲」变多**只能靠读**，
   而读会同时推进 `Response.offset`。
3. **存量极度依赖服务端发得多快。** 慢发（4 KiB/块 + 块间 `sleep(10ms)`，≈ 400 KiB/s，接近真实播放）时通道
   **恒空**、扣减量恒为 0；全速（64 KiB/块）时第一读之后 ≈ 1.0 MiB——写侧被 `flush()` 挂起在 `CHANNEL_MAX_SIZE`
   （1 MiB，`internal const val`、编译期内联、运行时改不了）附近，最后一次 flush 之前写进 `_writeBuffer` 的那个块
   不会被退回，所以存量可以略微超过 1 MiB（实测跑出过 1,056,687 B = 1 MiB + 8 KiB）。⇒ 「按已缓冲量判代价」这个改法
   只在快发时有收益（最多省 1 MiB），慢发时退化成原判据。

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
