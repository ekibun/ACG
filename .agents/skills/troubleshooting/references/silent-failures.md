# 不报错、只失效

本项目最危险的一类问题**编译通过、不抛异常、但行为静默错误**。每条给出**症状指纹**、**根因**与 **DO/DON'T**；动到任一条之前先读它。

## 构建与原生

**1. 用着旧 dll** — 改动没生效、连日志都没有、参数悄悄错位。
重编与 dll 落位已全自动（消费链任务图都挂 `buildJni`，见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md)「原生构建」）。剩余来路只有：Hot Reload 换不了 native 库（改 `cxx/` 必须整进程重启）、改了 Kotlin 侧 JNI 签名却没意识到 dll 也得重编。
> **DON'T:** 改 `cxx/` 或 JNI 签名后只 Hot Reload——须整进程重启让 dll 重编落位。

**2. 动了 submodule** — 改动不报错，但污染上游源码树、下次同步即丢。
`cxx/ffmpeg/ffmpeg/`、`cxx/quickjs/quickjs/` 是上游树，要参考实现直接读本地文件。
> **DON'T:** 改 `cxx/ffmpeg/ffmpeg/` 与 `cxx/quickjs/quickjs/` 下任何文件。

**3. `external fun` 移进 `companion object` 漏了 `@JvmStatic`** — 编译链接都过，只在真调时抛 `UnsatisfiedLinkError`。
JNI 名只由声明所在类文件决定：放类体是实例方法、名取外层类名；放 companion 多一段 `_00024Companion`。`@JvmStatic` 让原生方法变静态，**第二 JNI 实参从实例变 `jclass`**——凡 native 侧用 `thiz` 的方法须留在类体（本仓库仅 `AvFormat.initNative`）。详见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) JNI 一节。
> **DO:** 移进 companion 必加 `@JvmStatic`；native 侧用 `thiz` 的方法留在类体。

## JS 桥

**4. 改 `soko.ekibun.acg.engine` 下的类名** — 脚本静默失效，不报错。
`JsEngine` 按名字反射实例化、JS 侧按名字调用；改名/移动只让调用失配。
> **DON'T:** 重命名/移动该类下成员，除非同步更新所有按名反射与 JS 调用。

**5. 在 `init.js` 里用 `import()` / `require()`** — 进程 `abort()`。
本桥模块加载路径不支持动态加载，能力须内联（脚本位置见根 [`AGENTS.md`](../../../../AGENTS.md) §3）。
> **DON'T:** 在 `init.js` 用 `import()` / `require()`；能力一律内联。

**6. 在 JS 线程之外碰 `JSRuntime`** — 偶发崩、重跑变绿，像环境问题。
引用计数是裸 `int`、GC 链表与 Shape 哈希链都无锁——真实竞态。`jsCall` / `releaseValue` 已收敛到 dispatcher（线程归属由 `Pointer` 的 dispatcher 保证，见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md)）。
> **DO:** 任何直接调 native 的路径先过 `runOnJsThread` / `withPtrSync` / `onJsThreadQuietly`，不在调用方线程碰 `JSRuntime`。

## Kotlin 构造与所有权

**7. 在基类构造期调子类覆写体** — 覆写体读到的子类字段/构造参数全 `0`，编译器不报错。
`javap -c` 里 `putfield` 排在 `invokespecial <init>` 之后，构造期求值的基类字段看不见子类构造参数。`Pointer.initPtr()` 曾写成基类 `private val ptr = initPtr()`，使 `QuickJS` 的 `stackSize`/`memoryLimit`/`timeout` 全按 `0`：`timeout = 0` 关掉死循环中断（native `JS_SetInterruptHandler` 中 `timeoutMs <= 0` 直接放行），症状是**测试挂死**（见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的 quickjs 节）。
> **DON'T:** 基类构造期碰需子类状态才能算出的东西；把求值推迟到子类字段就位后（`Pointer` 用 `by lazy`，首次读句柄才发生），覆写体只算值。

**8. 「句柄没兑现过就不用还」只看惰性标志** — `close()` 静默变空操作，资源不报错地永久泄漏。
判据须同时看「句柄是否构造参数交进来的」：`nativePtr == null && !ptrLazy.isInitialized()`。构造参数交句柄的子类（`JSRef`/`AvFrame`）手上本就有资源，`ptr` 读没读过都欠一次归还；只看 `isInitialized()` 时 `JSRef.free()` 减到 0 那条路就是「`close()` 返回成功、其实什么都没释放」。对照 `PointerTest.closeSkipsReleaseWhenHandleWasNeverBuilt`（覆写型）/ `explicitZeroHandleIsNotTreatedAsMissing`（构造参数型）。
> **DO:** `close()` 判据用 `nativePtr == null && !ptrLazy.isInitialized()`，覆盖构造参数交句柄的子类。

**9. 「独占 dispatcher 交给 `close()` 收」只挂在 `close()` 里** — `await()` 那条路径整个漏掉，线程不报错地永久泄漏。
`FFPlayer.closeAsync()` 走 `codec.closeDeferred().await()`，每实例一条 `avcodec` 线程照旧攒着。两约束：① 关的时机是**归还落地之后**（早一步 `shutdown` 会把 `releaseImpl` 拒在门外）；② 挂在 `closeDeferred()` 返回的 `Deferred` **完成**上（两条路都覆盖）。对照 `PointerTest.ownedDispatcherIsClosedAfterReleaseLands`（await 路）/ `ownedDispatcherIsClosedEvenWhenHandleWasNeverBuilt`（短路路：返回已完成 Deferred，关闭当场执行）。
> **DO:** 独占 dispatcher 的关闭挂在 `closeDeferred()` 返回的 `Deferred` **完成**上，且晚于归还落地。

**10. 以为调度器关掉后投递会抛 `RejectedExecutionException`** — 关后行为有三种，无一种是「吵着拒绝」，两种完全静默；`releaseImpl` 可能从未跑，资源静默泄漏。
kotlinx 里 `ExecutorCoroutineDispatcherImpl.dispatch` 的 `catch (RejectedExecutionException)` 先 `cancelJobOnRejection(context, e)`、再 `Dispatchers.IO.dispatch(context, block)`（`jvmMain/Executors.kt:133-141`），把块改投 `Dispatchers.IO`：

| 调用 | 结果 |
| --- | --- |
| `d.dispatch(ctx, Runnable)`（ctx 无 Job） | 无异常，块真跑了，跑在 `DefaultDispatcher-worker-3` |
| `withContext(d){}`/`runBlocking(d){}`/`submit{}.await()` | `CancellationException("The task was rejected")`，`RejectedExecutionException` 只是 `cause` |
| `launch(d){}`/不 `await` 的 `submit{}` | 完全静默：Job 取消、块丢弃、`CoroutineExceptionHandler` 收不到 |
| `closeDeferred().await()`（归还路径） | 同上抛 `CancellationException`，`releaseImpl` 从未跑 ⇒ 泄漏 |

> **DON'T:** 按 `RejectedExecutionException` 写 catch（`is RejectedExecutionException` 为 `false`，看 `cause`）。
> **DO:** 判断调度器「还能不能用」看自己的标记（如 `isAlive`/`closedFlag`）。`ThreadDispatcher` 的 `dispatch` 在 `delegate.dispatch` **之前** `check(!closedFlag)` 当场抛 `IllegalStateException`；`close()` **先置标记、再 `shutdown()`**——拦截必须在 `delegate.dispatch` 之前、标记早于 `shutdown()`，否则并发窗口漏网。`Pointer.close()` 因此在「dispatcher 已被别家关掉」时同步抛出。对照 `PointerTest.closedDispatcherRejectsFreshDispatch` / `releaseIsRejectedLoudlyWhenDispatcherAlreadyClosed`。

## FFmpeg 读包与解码

**11. 拿 `List.remove()` 的返回值当"元素在不在队里"的判据** — 跳一帧 + 每轮漏一个 `AVFrame`，不报错。
`remove()` 本身是**出队动作**：返回 `true` 时元素已摘下，只清标记不会放回——那一帧既不在队列、也没归还，静默跳帧 + 泄漏。`FFPlayer.updateJob.invokeOnCompletion` 曾这么写。
> **DO:** 判据写 `queue?.contains(frame)`，再决定"归还"或"留队并撤销标记"。帧出队即 `close()`（所有权归 Java 侧）；seek/`closeAsync` 清队只关不在飞行中的帧（`processing == null`），飞行中的归各自作业收尾，清队处关即 use-after-free。

**12. 把 `av_read_frame` 读出来的包「就地归还」** — 画面持续花屏、错到下一个 IDR，不报错。
`av_read_frame` 已把 demuxer 位置推走，无"放回去" API——「就地归还」实为从码流删包。`AvFormat` 的 `Pointer` 归属独立 `ThreadDispatcher("avformat")`，每次轮次作废（`pause()`/`stepForward`/`resume`/seek）都走这条分支。两排除陷阱**不能**用：① `frame.decodeErrorFlags == 0` 只在 `ff_er_frame_end` 跑过才置位（`h264dec.c:786-812`）；② 原生 stderr 干净不算证据。
> **DO:** 换位置产生的陈旧包**就地归还**（`av_read_frame` 已消费、补不回）：读循环 `if (this@FFPlayer.pts != pts) { packet.close(); break }`；其余（含「只是暂停」）照常送解码器入队——暂停是「先别送显」不是「不要解码」，**判据不要掺 `pts.playing`**。`resume`/`stepForward` **不换 `pts` 对象**（仅 `play`/`seekImpl` 换位置才换）。
> **DON'T:** 用「就地补解」接轮次作废（`pendingPacket` 字段已删，别加回）。

**13. `AvIO.read` 在文件尾返回 `0` 而非 `AVERROR_EOF`** — `wav`/`pcm` 这类无重试 demuxer 播到尾后 avformat 线程 100% CPU、不报错不返回，GC 被每次 32 KB 分配拖垮。
aviobuf 的 `fill_buffer` 只把 `AVERROR_EOF`/负数当终止（`aviobuf.c:551-558`），返回 `0` 走「读空，继续要数据」。
> **DO:** `FileIO.read` 把 `RandomAccessFile` 的 `-1` 翻成 `AVERROR_EOF`；`AvFormat.seek(whence = AVSEEK_SIZE)` 不确定长度返回 `-1`（AVERROR），**返回 0 会被当成「零长度流」**把后续 seek 判越界。

**14. 作废收场翻错误码而非返回正数** — `closeAsync` 静默拖到 8 s 量级，不报错。
`HttpIO.read` 的 `catch (CancellationException)` 若返回 `AVERROR_*` 负值，demuxer 不收手而是**再读一轮**（`ff_read_packet` 的 `continue` 分支，`demux.c:660/668`），那次读无 abort ⇒ 开新 range 会话干等闲置超时；返回 `0` 更糟，avio bypass 直读分支 `size -= 0` 恒真、原地死循环。逐档推演见 [`http-streaming.md`](./http-streaming.md) 第二节。
> **DO:** 作废收场必须返回正数 `buf.size`（谎报读满——那条包必然被丢，安全）。

**15. `close` 路径的 `resetChannel()` 在归属线程之内调会自锁** — 关闭永远转圈（5 s 返回不了），不报错；`pause()` 却毫秒级返回。
`ThreadDispatcher` 是 `newSingleThreadExecutor`、纯排队。`FFPlayer.closeAsync` 须在 `playerDispatcher` 上先调 `resetChannel()` 让 `io.abort()` 就地发出、不排队；若在归属线程内或经 `closeDeferred()` 的 `submit{}` 排队，abort 排在它要叫醒的读作业后，自锁。`seekTo` 的 `resetChannel` 则与 `seekToNative` 落在同一段不可打断的 `withPtr` 块里、都在 `avformat` 单线程上 ⇒ 那段没有并发访问者。详见 [`http-streaming.md`](./http-streaming.md) 第二节。
> **DO:** `closeAsync` 在 `playerDispatcher` 上、进入 `formatDispatcher` 之前就调 `resetChannel()`。

**16. `AvFormat.packetChannel` / `readerJob` 不要加 `@Volatile`** —（误加也修不了）偶发读到上一轮粘住的通道、静默返回 EOF。
两字段确实跨线程读写，但**无数据竞争**——`@Volatile` 只保可见性、不保「读到的是不是这一轮的」逻辑判据。可见性靠两处同步点（依赖别处实现）：写（`getPacket`，playerDispatcher）→ 读（`seekTo` 的 `resetChannel`，formatDispatcher）隔着 `takeOverPlayback()`→`pause`→`playingJob.join()`（跑 `getPacket` 的协程），随后 `withPtr` 派发再过一次执行器队列；反向靠 `withPtr` 返回的恢复边。

| 访问 | 线程 |
| --- | --- |
| `getPacket` 建通道、记 `readerJob`、消费 | 调用方线程 |
| 被 `FFPlayer.closeAsync` 调（`resetChannel`） | `playerDispatcher`（同线程） |
| 被 `AvFormat.seekTo` 调（`resetChannel`） | `formatDispatcher`（`withPtr` 块内）⇒ 跨线程 |

> **DON'T:** 给 `packetChannel`/`readerJob` 加 `@Volatile` 当修复。
> **DO:** 这条不变量依赖 `FFPlayer.pause` 的 `playingJob?.join()` 与换位置入口先过 `takeOverPlayback()`；真要拆这两条前提，改显式同步原语（`AtomicReference` + CAS 建通道）而非补 `@Volatile`。

**17. 把 `try` 的本体搬走、`finally` 立刻执行** — 一帧都不送显（超时在「等第一帧上屏」），不报错。
`try { 开灯 } finally { 熄灯 }` 里若没有撑住整轮的挂起点，「灯」在没人撑时静默熄掉（`FFPlayer.resumeImpl` 曾把读包循环搬去常驻 `readLoop`，`try` 只剩两行、`finally` 当场跟上）。
> **DO:** `try { 开灯 } finally { 熄灯 }` 里必须有一个撑住整轮的挂起点（循环/`await`/`join`）；抽走本体时先问「现在谁在撑这盏灯」（把 `joinAll()` 放进 `try`）。

## 帧时序

**18. 固定常数 ε 做「单帧步退」必然失效** — seek 到关键帧落点再按「-1f」不动，连按原地重画同帧，不报错不卡。
`av_seek_frame` 用 `av_rescale`（`AV_ROUND_NEAR_INF`）把目标折进流时基，半格以内一律折回当前帧，容器给同一关键帧。
> **DO:** ε 须同时大于「半格」、小于「一帧间隔」；取 **ε = 3/4 × 已缓存帧间隔**（`FFPlayer.frameIntervalUs`），天然落在 `半格 ≤ 间隔/2 < 3/4 间隔 < 间隔` 区间内，无需暴露 `time_base`。

**19. 两轮播放同时在飞会串帧** — 上一轮送显读到这一轮像素；被 seek 作废的轮次在下一轮复活、补一次 `flushFrame`，画面重复帧 + 整体落后。
native 侧每种流只有一块输出缓冲（`SWContext::videoBuffer`）——`AvSurfaceContext.postFrame` 整体改写它、`flushFrame` 才抄给平台；两轮同时走到这对调用中间就串。
> **DO:** 同一时刻只允许一轮在飞（`FFPlayer.takeOverPlayback`）；判据只看对象身份（一轮 == 一个 `PTS` 对象，`pts === 当前 pts`），**不掺 `pts.playing`**（`resume`/`stepForward` 不换对象）；语义是「后到者顶掉先到者」。
> **DON'T:** 用 `Mutex` 解——它排队，上一跳转结果照样先落地再被覆盖，且仅盖住被包入口（`seekTo`/`play`/`resume` 裸奔）。

**20. 同一轮内相邻两帧送显顺序反了会串一帧** — 纯顺序播放，送显像素恰好领先自称时间戳一帧，不报错不卡。
同一块共享缓冲：必须先等上一帧送显完再写这一帧。反过来本帧先覆写、`flush` 还等着抄 ⇒ 上一帧显示成本帧像素；下一帧趁「等视频追主时钟」的 `delay` 空档写进，于是**每一帧**错位。
> **DO:** `FFPlayer` 解码作业里那条 `join`（等上一帧送显）不能省；先 `join` 再 `postFrame`。

**21. seek 后丢帧收敛须排在送显之前、且音频也要丢** — 收敛排 `flushFrame` 之后则先上屏再决定丢不丢，主时钟被音频错误锚定，视频干等。
判据照 ffplay `frame_drops_early`：`diff = dpts - master_clock`，主时钟 == 收敛目标，`|diff| < AV_NOSYNC_THRESHOLD && diff < 0` ⇒ 解码后立刻丢。`flushFrame` 无返回值（恒 -1），送显就发生在它那里，故丢帧检查须排**之前**。**音频也要丢**：落点容器级（mp4 seek 5.0s，音频首包 3.90s、视频关键帧 4.0s），音频不丢会把主时钟按它上屏时间戳重新锚定到目标前。丢开头连续段，声卡刚被 flush，无 audible 缺口。判据用 `AvFrame` 自身时间戳（native 已折算 `AV_TIME_BASE` 微秒，与 seek 目标同单位）。
> **DO:** 丢帧判据排 `flushFrame` **之前**；音频与视频一起丢开头连续段。

## 内存与流式

**22. 每帧新建 skiko 对象 ⇒ native 内存攒到 GB 级** — `jcmd GC.heap_info` 堆仅几十 MB，任务管理器 GB 级；`GC.class_histogram` 里 `CleanableImpl`/`Managed$CleanerThunk` 有几百个。
每帧 `Image.makeRaster(...)` + `Image.toComposeImageBitmap`：两个 3.67 MB native 对象，skiko `Managed`、靠 `Cleaner` 回收。Cleaner 等 GC 触发——Java 堆上限 GB 级、实际几十 MB ⇒ GC 十秒一次 ⇒ RSS 涨到 1.3~2.2 GB 再成批放（锯齿），且回收后 RSS 不还。
> **DO:** 每帧路径不分配 skiko 对象——只留**一块**复用 `Bitmap`，每帧 `installPixels`（不拷）挂进 native 像素，Compose 直接画；尺寸变了才新建并 `close()` 旧的（不是池，一路只播一个分辨率）。

**23. `HttpClient.request(...)` 把整个响应体先读进内存** — 远程播放迟迟不出画面（等整包下完），内存随文件走，无任何报错。
ktor 入口：`HttpClient.request(...)` → `HttpStatement.execute()` → `fetchResponse()`，内部 `call.save().response`（注释 "Save the body again..."）⇒ body 完整缓冲。ktor 另有不 save 的 `fetchStreamingResponse()`，公开面 `HttpStatement.execute(block)`——block 里**同时**拿 `headers` 与 `bodyAsChannel()`。
> **DO:** 边下边用走本项目 `Http.request(...)`（`acg/common/Http.kt`）——内部 `prepareRequest(...).execute(block)`，即不 save 路子；读须发生在 block 期间（`HttpIO` 把整条会话挂 block 里，等 `close()` 或「会话被换掉」完成信号）。上游 `HttpStatement.execute()` 整包入口本项目未暴露。

**24. ktor `ByteReadChannel.awaitContent(min)` 参数是「最少字节数」非超时** — 毫秒当参数编译通过、健康流上像正常，但静默处永远醒不来（等凑够 min 字节），abort 叫不醒、旗标查不到。
实现 `source.request(min)`，两引擎（Java/OkHttp）body 通道都是 `ByteChannel`，其 `awaitContent` 挂起等到 ≥min 字节或通道关闭，**无超时重载**。
> **DO:** 「等会儿查旗标」自包 `withTimeoutOrNull(ms) { channel.awaitContent(1) }`：`sleepWhile` 走 `suspendCancellableCoroutine`，超时取消干净（不 consume 字节）、事件驱动性还在。见 `HttpIO.Response.awaitContent` KDoc。

**25. `async` 块里 `throw` 表达控制流 ⇒ 把父 `runBlocking` 一起取消** — 不报错，调用方拿到 `Int.MIN_VALUE` 之类「没返回」哨兵。
哨兵是 `runBlocking` scope 的**子协程**；`async` 里抛的异常不是「留给 `await` 的值」，而是**未捕获异常**，会 `parentCancelled` 向上取消父 `Job`——父 `BlockingCoroutine` 进入 `Cancelling`，整 scope（含 `readSuspend`）被拆掉，那个 `catch` 一次没跑，`runBlocking` 直接抛取消异常（`JobCancellationException: Parent job is Cancelling`）。
> **DON'T:** `async` 块里用 `throw` 表达控制流，尤当它挂在想保住的 scope 上。
> **DO:** 通知等待方用 `目标job.cancel()` 或 `CompletableDeferred.completeExceptionally()` 再让目标 `await()`——别让异常从子协程漏进父 scope。`Http.Response.awaitContent` 的 `async { data }` 安全，因 `select` 挑一支、`finally` 把落选支 `cancel()`，异常从没被抛出。**`async` 只用来挂等待，不用来报错。**

**26. 作废预读通道用 `close()` 而非 `cancel()`** — seek/close 挂住不动（不报错不抛，就是等）；每次作废漏一个 `AVPacket`。
`close()` 不唤醒停在 `channel.send` 上的读作业（预读灌满、无人消费时停那儿），随后 `join` 死等；`close()` 还让已预读包投递成功、落进没人收的通道 ⇒ 漏包。`cancel()` 以 `CancellationException` 收场、已预读包由 `onUndeliveredElement` 归还。
> **DO:** 作废预读通道用 `cancel()`（走 `onUndeliveredElement` 归还）；因此**不能**再「`close()` + 遍历队列挨个关包」——队列已被丢弃。现状见 `AvFormat.resetChannel` 三步固定顺序（第 2 步）。

## 协程与挂起

**27. `CompletableDeferred(null)` 挑错重载 ⇒ 造出永不完成的 Deferred** — 主线程等一个永远不来的通知，
测试挂死数分钟；而对端线程正空转在 `LinkedBlockingQueue.take` 上，容易被误判成 native 卡死。
`CompletableDeferred(null)` 被重载解析挑到 `CompletableDeferred(parent: Job?)` 那个重载，
于是这个 Deferred 挂在父 Job 上、从没人 complete。实测一次卡死 8 分钟（`closeIsIdempotentAcrossEntryPoints`）。
> **DO:** 构造"立即完成、值为 null"的 Deferred 用 `CompletableDeferred(value = null)` 显式收敛到
> 值重载；排查「线程空转 + 主线程死等」时先查 Deferred 有没有人 complete——取证据的步骤见
> [`debugging.md`](./debugging.md) 的「判『阻塞在哪』」一节。
> **DON'T:** 主线程 `await()` 的 Deferred 没有对应的 complete 路径（含"实现了 complete 但重载挑错"这种隐性情况）。
