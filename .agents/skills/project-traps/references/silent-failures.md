# 不报错、只失效

> 条目原样移自根 [`AGENTS.md`](../../../../AGENTS.md) §2（原题「会静默失败的几件事」），
> **未逐条对照代码重新核定**。§2 现在只留索引，正本是本文件。

本项目最危险的一类问题**不报错、只失效**，排查时最容易误判成"代码没写对"。动到下面任一条之前先读它：

- **用着旧 dll** → 不报错，只是参数错位，症状是"改动没生效、连日志都没有"。重编与落位已全自动
  （所有消费链的任务图都挂着 `buildJni`，见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的「原生构建」），
  剩余的来路只有 **Hot Reload 换不了 native 库**（改完 `cxx/` 必须整进程重启）与改了 Kotlin 侧
  JNI 签名却没意识到 dll 也得跟着重编。
- **改 `soko.ekibun.acg.engine` 下的类名** → `JsEngine` 按名字反射实例化它们、JS 侧按名字调用，
  改名/移动**不会报错**，只会让脚本静默失效。
- **在 `init.js` 里用 `import()` / `require()`** → 本桥的模块加载路径会把进程 `abort()`。
  能力必须内联（脚本位置见根 [`AGENTS.md`](../../../../AGENTS.md) §3）。
- **在 JS 线程之外碰 `JSRuntime`** → 偶发崩、**重跑就变绿**，看着像环境问题，其实是真实竞态
  （引用计数是裸 `int`、GC 链表与 Shape 哈希链都无锁）。桥接层已经把 `jsCall` / `releaseValue`
  收敛到 dispatcher 上了 —— **新增任何直接调 native 的路径都要先过 `runOnJsThread` /
  `withPtrSync` / `onJsThreadQuietly`**，别在调用方线程上调
  （规则 7 见 skill [`quickjs-ownership`](../../quickjs-ownership/references/quickjs-reference-ownership.md)）。
- **在基类构造期调子类的覆写体（Kotlin）** → 覆写体里读到的子类字段/构造参数**全是 `0`**，
  而且**编译器不报错**。判据一眼可辨：`javap -c` 里 `putfield` 排在 `invokespecial <init>`
  **之后**，所以「构造期求值的基类字段」看不见「子类的构造参数属性」。真实代价：
  `Pointer.initPtr()` 曾被写成基类的 `private val ptr = initPtr()`，于是 `QuickJS` 的
  `stackSize` / `memoryLimit` / `timeout` 全按 `0` 算 —— 前两个恰好落回 native 默认值
  （看不出来），**`timeout = 0` 直接把死循环中断关掉**（native 侧 `JS_SetInterruptHandler`
  里 `timeoutMs <= 0` 直接放行），症状是**测试挂死而不是报错**。
  ⇒ 需要子类状态才能算出来的东西**别在基类构造期碰**：让基类把这次调用**推迟到子类字段就位
  之后**（`Pointer` 的做法是把求值放进 `by lazy`，即**首次读句柄**时才发生），覆写体
  `initPtr()` 只管算值。`Pointer` 这侧的判据见 skill
  [`quickjs-ownership`](../../quickjs-ownership/references/quickjs-reference-ownership.md)
  规则 5.1。
- **「句柄没兑现过就不用还」写成了只看惰性标志（Kotlin）** → `close()` 静默变成空操作，
  资源**不报错地永久泄漏**。判据必须同时看「句柄是不是构造参数交进来的」：
  `nativePtr == null && !ptrLazy.isInitialized()`。构造参数交句柄的子类（`JSRef` /
  `AvFrame`）手上本来就有资源，`ptr` 读没读过都欠一次归还 —— 只看 `isInitialized()` 时，
  `JSRef.free()` 减到 0 那条路就是「`close()` 返回成功、其实什么都没释放」。
  对照：`PointerTest.closeSkipsReleaseWhenHandleWasNeverBuilt`（覆写型）/ 
  `explicitZeroHandleIsNotTreatedAsMissing`（构造参数型）。2026-09-20 实测踩过。
- **「独占的 dispatcher 交给 `close()` 收」写成了只挂在 `close()` 里（Kotlin）** → `await()` 那条
  路径整个漏掉，线程**不报错地永久泄漏**。`Pointer` 一开始就是这么写的，但 `FFPlayer.closeAsync()`
  走的是 `codec.closeDeferred().await()` —— 每实例一条 `avcodec` 线程照旧攒着，「独占」等于没声明。
  两条约束：① 关的时机是**归还落地之后**（早一步 `shutdown` 会把 `releaseImpl` 一起拒在门外）；
  ② 挂在 `closeDeferred()` 返回的 `Deferred` **完成**上（两条路都覆盖）。对照：
  `PointerTest.ownedDispatcherIsClosedAfterReleaseLands`（await 那条路）/
  `ownedDispatcherIsClosedEvenWhenHandleWasNeverBuilt`（短路那条路：短路分支返回已完成的 Deferred，
  关闭动作当场执行）。2026-09-21 实测踩过。
- **以为「调度器关掉之后投递会抛 `RejectedExecutionException`」（Kotlin）** → 它**兜住了**，
  于是关掉之后的行为有三种，**没有一种是「吵着拒绝」**，其中两种**完全静默**。根因在 kotlinx：
  `ExecutorCoroutineDispatcherImpl.dispatch` 的 `catch (RejectedExecutionException)` 里先
  `cancelJobOnRejection(context, e)`、再 `Dispatchers.IO.dispatch(context, block)`
  （`jvmMain/Executors.kt:133-141`）—— 把块**改投到 `Dispatchers.IO`**。
  2026-09-21 在本工程 `ThreadDispatcher` 上实测（关掉之后、**未加拦截**时）：

  | 调用 | 实测结果 |
  | --- | --- |
  | `d.dispatch(ctx, Runnable)`（ctx 里没有 Job） | 无异常，块**真的跑了**，跑在 `DefaultDispatcher-worker-3` |
  | `withContext(d) { }` / `runBlocking(d) { }` / `submit { }.await()` | `CancellationException("The task was rejected")`，`RejectedExecutionException` 只是它的 `cause` |
  | `launch(d) { }` / 不 `await` 的 `submit { }` | **完全静默**：Job 被取消、块被丢弃、`CoroutineExceptionHandler` 收不到 |
  | `closeDeferred().await()`（归还路径） | 同上抛 `CancellationException`，而 **`releaseImpl` 从未跑** ⇒ 资源静默泄漏 |

  ⇒ 三条：① **别按 `RejectedExecutionException` 写 catch**（`is RejectedExecutionException`
  实测为 `false`，得看 `cause`）；② 对「归属线程」这类抽象，**关掉之后线程归属就不再被保证**
  —— 想「关掉之后一定吵」只能在 `dispatch` 上自己拦；③ 判断一个调度器「还能不能用」要看
  `isAlive` 这类自己的标记，别指望投递会告诉你。

  **本工程的对策（2026-09-21）**：`ThreadDispatcher` 自己拦了一手 —— `dispatch` 在调
  `delegate.dispatch` **之前** `check(!closedFlag)`，关掉之后的任何投递当场抛
  `IllegalStateException`；`close()` 里**先置标记、再 `shutdown()`**。上表那三种表现从此只在
  「绕过 `ThreadDispatcher` 直接用裸 `ExecutorCoroutineDispatcherImpl`」时才复现。两个必守点：
  ① 拦截**必须在 `delegate.dispatch` 之前**（放进去就晚了，kotlinx 已经把异常换成「改投」）；
  ② 标记必须立得比 `shutdown()` 早（否则并发窗口里还有一次漏网）。附带效果：`Pointer.close()`
  在「dispatcher 已被别家关掉」时**同步抛出**，不再静默漏掉归还 —— 炸在肇事现场附近，对照
  `PointerTest.closedDispatcherRejectsFreshDispatch`（三种投递形态各一条）与
  `releaseIsRejectedLoudlyWhenDispatcherAlreadyClosed`（归还路径那条）。
- **动了 submodule** → 改动不报错，但会污染上游源码树、下次同步即丢。`cxx/ffmpeg/ffmpeg/`、
  `cxx/quickjs/quickjs/` 是上游源码树，**不要动**（需要参考实现就直接读本地文件）。
- **`external fun` 移进 `companion object` 却漏了 `@JvmStatic`（Kotlin / JNI）** → 编译、链接都过，
  只在**真的调**的时候抛 `UnsatisfiedLinkError`；而"还没调到"和"配对成功"在测试里长得一模一样。
  JNI 名只由**声明落在哪个类文件里**决定（可见性无关）：放类体里是实例方法、名取外层类名；
  放 companion 里会多一段 `_00024Companion`。历史代价：`AvFrame.closeNative` 长期是空调用，
  `av_frame_free` 从来没跑成过。⚠️ 但 `@JvmStatic` 不是白给的 —— 它让原生方法变成静态、
  **第二个 JNI 实参从实例变成 `jclass`**，native 侧凡用 `thiz` 的那个都必须留在类体里
  （本仓库只有 `AvFormat.initNative`）。两坑的详解与判据见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md)
  的 JNI 一节。2026-09-21 修。
- **拿 `List.remove()` 的返回值当"元素在不在队里"的判据（Kotlin）** → `remove()` 本身就是**出队动作**：
  它返回 `true` 时元素已经摘下来了，此时只把标记置空并不会把它放回去 —— 那一帧**既不在队列里、
  也没有被归还**，于是**不报错地**跳一帧 + 泄漏一块 native 内存。`FFPlayer.updateJob` 的
  `invokeOnCompletion` 原来就是这么写的，症状正是「按一次『前进一帧』跳两帧」+ 每轮漏一个 `AVFrame`。
  判据要写成 `queue?.contains(frame)`，再决定"归还"还是"留队并撤销标记"。
  同一处的通用规则：帧一旦出队就必须 `close()`（出队即所有权归 Java 侧，见 `AvFrame`）；
  而 seek / `closeAsync` 清队时**只关得掉不在飞行中的帧**（`processing == null`）—— 飞行中的那几帧
  归各自的作业收尾，在清队处关就是 use-after-free。2026-09-21 实测踩过。
- **把 `av_read_frame` 读出来的包「就地归还」（Kotlin + FFmpeg）** → **它不是归还，是把这一包从
  码流里删掉**：`av_read_frame` 已经把 demuxer 位置推走了，没有任何"放回去"的 API。⚠️ 口径：
  「删」只对**同一次 pass** 成立 —— 之后的 seek 会把 demuxer 退回关键帧重读那一段，所以拿
  「整份日志里解码时间戳的全集」是查不出来的（实测：四个场景串着跑，一帧不缺也不少）。
  症状不是报错，而是**画面持续花屏、一直错到下一个 IDR**（解码器少一个参考帧 ⇒ 其后每一帧都
  带着错参考解，误差沿参考链一路传下去）。`FFPlayer.resumeImpl` 的读包循环里就出过这么一处
  （2026-09-22 已修，写法见下面的「对策」与「2026-10-01 更正」两段）。

  **为什么这个窗口是真实的**：`getPacket` **跨线程** —— `AvFormat` 的 `Pointer` 归属 dispatcher
  是独立的 `ThreadDispatcher("avformat")`，`withPtr` 会真的 `withContext` 过去。于是「包已读出、
  轮次却在这期间作废」可以命中；而**每一次轮次作废（`pause()` / `stepForward` / `resume` /
  seek）都会走这条分支**。

  **别用这两个判据去排除它**：① `frame.decodeErrorFlags == 0` **不能**说明参考链没断 —— 它只在
  `ff_er_frame_end`（错误掩盖）跑过时才置位（`h264dec.c:786-812`），整帧参考帧没喂进去不走那条路，
  实测全程 0（而这个字段在 native 侧确实接通了，`cxx/ffmpeg/ffmpeg.cpp:226`）；② 原生 stderr
  干净同样不算证据。

  **实测判据（2026-09-22，`test.mp4`，探针 `ZzStepRapidProbeTest`）**：在每个包解出后打一行帧
  时间戳。⚠️ **判据要按 pass / 轮次分段看**：「上一条 `DECODED` 与下一条 `DECODED` 差 2 格
  （16660µs → 33300µs）」这个写法在**换轮处天然成立**（新一轮开头常常只解 1 帧），拿全日志的
  邻接关系去数，会把正常换轮也记成丢包。按轮次分段后实测：70 次轮次作废里 64 次在**该轮**的解码
  序列上留下 2 帧缺口；「60 次顺序步进」那一场景更是**每步解码走 2 帧、显示只走 1 帧**。
  像素侧的判据更硬：seek 到 1/3 处后 **113 帧送显像素与全片 1786 帧的任何一帧都不匹配**，且坏帧
  区间正好从断点起、到**下一个 IDR** 止（全片 GOP 恒 120 帧：`#607 → #719`，`720 − 607 = 113`）；
  对照「不 seek、播放期间不作废轮次」= 0 坏帧。

  ⇒ **对策（2026-09-22 已采用并经复测）**：包**不许 `close()`、也不当场补解**，而是**留着** ——
  `FFPlayer.pendingPacket` 一个字段记着它，**下一轮开头先把它喂给解码器**（包序、帧序都不变）；
  只有**跳转**（包属于跳转前的位置，新位置会自己重读）与**关闭**（不会再有下一轮）两处才真的归还。
  ⚠️ 别改成「就地送解码」：那是在「轮次已作废」的语境里去动解码器与帧队列，多出一件说不清的事。
  **复测（同一探针、同一素材）**：场景 D 未命中 `113 → 0`、场景 F `52 → 0`，且 F 的逐帧命中下标
  从「`596..605` 之后整片 `-1`」变成 `596..657` **完全连续** —— 每步正好一帧，不再跳帧。

  ⚠️ **2026-10-01 更正：`pendingPacket` 字段已整个删除，别再照着上面把它加回来。**
  事后发现**真正的根因是判据本身写错了**，那个字段只是在替它兜底：
  - 原判据 `if (!isPlaying())`，而 `isPlaying = pts == 当前 pts && pts.playing` —— 它把
    **「PTS 对象换了」（= 换了位置，包确实该丢）** 和 **「只是暂停」（= 位置没变，包一点问题没有）**
    混在同一个布尔里，于是后者只能靠一个额外的字段接住。
  - 拆开之后两种情形各归各，一个字段都不需要。这个写法**现在仍是现状**：`FFPlayer` 的读循环
    留在 `resumeImpl` 里，判据 `this@FFPlayer.pts != pts` → `close + break`（中途有一版把它
    换成常驻读循环 + `demuxerPos`，那一版已不在代码里，见下文「第二件」的现状段）：

    ```kotlin
    if (this@FFPlayer.pts != pts) { packet.close(); break }   // 【历史】换了位置 → 包属旧位置，就地归还
    // 否则（含"只是暂停"）：不停在这里，照常送进解码器入队 —— 这就是暂停期间的"预读缓存"
    ```

    这两条**思路**至今成立，别丢：① 判据不掺 `pts.playing`（暂停不是"不要解码"，只是"先别送显"）；
    ② 换位置产生的陈旧包要**就地归还** —— `av_read_frame` 已消费、补不回来。
  - 上面那条「别改成就地送解码」的告诫**依然成立**：这里也没有"就地送解码"，是**照常走正常
    入队路径**（和没暂停时同一行代码），只是不再有一个"先把包留着"的特殊状态。
  - **为什么原来那版会丢帧**：`resume` / `stepForward` 当时会**换 `PTS` 对象**（`PTS.fork()`），
    于是「在飞的解码帧」也被判成"过时"、`decoded.forEach { it.close() }` 丢掉 —— 包已离开
    `av_read_frame`，补不回来。那个 `fork()` 已一并删除（换对象只留在**换位置**的两处：
    `play` / `seekImpl`）。
  - **实测（2026-10-01，探针：6 轮「暂停 → 恢复」后读 `lastFrameTs`）**：
    `jumps=0 stuck=0 backwards=0` —— 帧序列无缺口。旧的 `fork` 版对照是 `discarded=2`（真丢了两批帧）。

  **2026-10-01 第二件（⚠️ 这一版已不在代码里）：读循环整个搬出 `resumeImpl`，成了常驻 `readLoop`**
  —— 退出条件只有 `closed`，seek 改投 `SeekRequest`、陈旧包判据换成局部 `demuxerPos`。
  它要解决的问题是**真的**：`pause()` 的 `playingJob.join()` 等的是**整轮**，而读循环就在那一轮里
  ⇒ 暂停一到它照样退出，「暂停期间继续缓存」实际不存在。实测（探针 `ZzPauseCacheProbeTest`，
  判据 = 私有字段 `frames` 的总长度）：

  | 版本 | 暂停那一刻 | 暂停后 6 次采样 | 结论 |
  | --- | --- | --- | --- |
  | 修前（`readAlive` 版） | 106 | `106,106,106,106,106,106` | **不涨** —— 一个包都不读 |
  | 修后（常驻 `readLoop`） | 100 | `110,110,110,110,110,110` | **涨到 110 就稳** —— 缓存生效，且节流闸管住了上界 |

  **现状（2026-10-02 复核）：「暂停期间继续缓存」改由 `AvFormat` 的 packet 预读通道承担**
  （`PREFETCH_PACKETS` 个包，通道满则读作业停在 `send` 上 —— 那就是回压）。它本来就不在
  `FFPlayer` 里，天然不受 `pause()` 影响；`FFPlayer` 侧回到「读循环留在 `resumeImpl`、
  条件 `while (isPlaying())`」，判据仍是对象身份 + `close + break`。
  - **demuxer 仍然只有读者碰**：`seekTo` 先 `resetChannel()`（cancel 掉通道 ⇒ 读作业在 `send`
    上退出），而这一句与 `seekToNative` 在**同一段不可打断的 `withPtr` 块**里、又都落在
    `avformat` 那条单线程上 ⇒ 没有并发访问者。
  - ⚠️ **`close` 路径的 `resetChannel()` 反过来必须在 `formatDispatcher` 之外**（2026-10-04）：
    `FFPlayer.closeAsync` 在 `playerDispatcher` 上先调它，让 `io.abort()` 就地发出、不排队。
    在归属线程**之内**调（或经 `closeDeferred()` 的 `submit { }` 排进它的队列）就是自锁 ——
    `ThreadDispatcher` 是 `newSingleThreadExecutor`、纯排队，abort 排在它要叫醒的读作业后面，
    `closeAsync` 5 s 都返回不了。**不报错、只是关闭永远转圈**，`pause()` 却毫秒级返回
    （它压根不碰 avformat）⇒ 判据要分别盯这两个，缺一个就漏掉这一段。详见
    [`http-streaming.md`](./http-streaming.md) 第二节。
  - 代价：seek / close 那次 join 要等读作业**从 `send` 上醒来**（靠 cancel），不像常驻读循环
    那样「最多等一次 `getPacket` 返回」；但 `getPacket` 在 EOF 之后是**粘住的**（立刻返回 null），
    所以不会挂死（这正是 2026-10-02 修掉的那个 1~2 GB / 收不掉 的坑）。
  - ⚠️ **`AvFormat.packetChannel` / `readerJob` 刻意不加 `@Volatile`**（2026-10-04）。
    这两个字段确实跨线程读写，但**没有数据竞争** —— 加 `@Volatile` 也修不了什么，
    它只保可见性、不保「读到的是不是这一轮的」这个逻辑判据。访问点与中间挡着的同步点：

    | 访问 | 落在哪条线程 |
    | --- | --- |
    | `getPacket` 建通道、记 `readerJob`、消费 | **调用方线程**（整个函数体不碰 `formatDispatcher`） |
    | 被 `FFPlayer.closeAsync` 调（`resetChannel`） | 同上（`playerDispatcher`），**同线程** |
    | 被 `AvFormat.seekTo` 调（`resetChannel`） | `formatDispatcher`（`withPtr` 块内）⇒ 跨线程 |

    跨线程那一路的可见性靠两个同步点，**都依赖别处的实现、不能当成自己的性质**：

    1. 写（`getPacket`，playerDispatcher）→ 读（`seekTo` 里的 `resetChannel`，formatDispatcher）：
       中间隔着 `FFPlayer.seekTo` 的 `takeOverPlayback()` → `FFPlayer.pause` → `playingJob.join()`，
       而那个 job 正是跑 `getPacket` 的协程；随后 `withPtr` 派发本身又过一次执行器队列。
    2. 反向（写在上面的 `resetChannel`、读在下一次 `getPacket`）：靠 `withPtr` 返回时
       `withContext` 的恢复边。

    ⇒ **这条不变量不局部**：它成立的前提是 `FFPlayer.pause` 里那个 `playingJob?.join()`，
    以及换位置入口**必须**先过 `takeOverPlayback()`。哪天真把那个 join「反正有 join 也不用」
    去掉，两处跨线程读立刻变成真竞态 —— 症状是**不报错的偶发读到上一轮的通道**（通道是粘住的，
    会静默返回 EOF，见 `AvFormat.getPacket`）。真要拆掉这两条前提，得先换成显式的同步原语
    （`AtomicReference` + CAS 建通道），**而不是补 `@Volatile`**。

- **把 `try` 的本体搬走，`finally` 就会立刻执行（Kotlin / 协程）** → 「开关」在没人撑的时候
  静默熄灯，**不报错、只是什么也不发生**。`FFPlayer.resumeImpl` 原先是这样：

  ```kotlin
  val playJobs = pts.streams.map { async(playerDispatcher) { while (isPlaying()) { ... } } }
  try {
    pts.playing = true          // 开灯
    if (!stepMode) playback?.resume()
    while (readAlive()) { ...读包循环（整轮）... }   // ← 本体在这里，它跑多久灯就亮多久
  } finally {
    pts.playing = false         // 熄灯
  }
  playJobs.joinAll()
  ```

  「这盏灯的寿命」= 读包循环的寿命，而这件事**没有任何一行文字写出来**。2026-10-01 把读循环
  搬去常驻的 `readLoop` 之后，`try` 里只剩两行，`finally` 当场跟上来：

  ```
  [DBG] resumeImpl enter, playing=false same=true
  [DBG] playJobs created, playing=false
  [DBG] before joinAll, playing=false     ← try 跑完了，灯却是灭的
  [DBG] playJob start, isPlaying=false    ← 消费侧一个判据都过不去
  ```

  ⇒ 症状是**一帧都不送显**（`FFPlayerStepTest` 超时在「等第一帧上屏」那一步），而不是崩或报错。
  对策：把 `playJobs.joinAll()` 放进 `try` —— 让**消费侧**顶替读循环去撑这盏灯
  （「本轮什么时候结束」本来就是消费侧的事）。

  ⇒ 通用判据：**`try { 开灯 } finally { 熄灯 }` 这种写法，`try` 里必须有一个「撑住整轮的
  挂起点」**（循环 / `await` / `join`）。把本体抽走时，要同时问一句「现在谁在撑这盏灯」。

- **每帧新建 skiko 对象 ⇒ native 内存攒到 GB 级，而 JVM 堆看起来「很干净」**（2026-10-02 实测）。
  桌面送显原来每帧 `Image.makeRaster(...)` + `Image.toComposeImageBitmap()`：两个 3.67 MB 的
  native 对象，**都是 skiko `Managed`、靠 `Cleaner` 回收**。而 Cleaner 要等一次 GC 才会被触发，
  本进程 Java 堆只有几十 MB、上限却是 GB 级 ⇒ **GC 十秒才来一次** ⇒ RSS 涨到 1.3~2.2 GB 再成批
  放掉（锯齿）；而且**回收之后 RSS 也不还** —— free 空间留在 native 堆里。
  症状形状：`jcmd <pid> GC.heap_info` 显示堆只有几十 MB，任务管理器却是 GB 级；
  `GC.class_histogram` 里 `org.jetbrains.skia.impl.CleanableImpl` / `Managed$CleanerThunk` 有几百个
  （wrapper 已经没了、native 还没还）。**改前 900MB↔2.2GB 锯齿，改成复用位图后稳定 280 MB**。
  对策：**别在每帧路径上分配 skiko 对象** —— 只留一块复用 `Bitmap`，每帧把 native 那块像素
  **挂进**位图（`installPixels`，不拷），Compose 侧直接画它（本地做法：
  `DesktopSurfaceContext.currentFrame` / `getBitmap()` 与 `VideoSurface.jvm.kt` 的
  `canvas.skiaCanvas.drawImageRect`）。
  注意是**单块**不是池：尺寸变了才新建并 `close()` 旧的 —— 一路只播一个分辨率，池化纯属多余。
- **`HttpClient.request(...)` 会把整个响应体先读进内存 ⇒ 远程播放「不报错地」先整包下载**（2026-10-03 实测）。
  症状形状：远程源迟迟不出画面（要等整包下完），内存随文件大小走，而**没有任何报错**。
  根因不在引擎，在 **ktor 的入口选择**：`HttpClient.request(...)` → `HttpStatement.execute()` →
  `fetchResponse()`，而它内部有一句 `val result = call.save().response`（源码注释
  "Save the body again to make sure that it is replayable"）⇒ **body 被完整缓冲**。
  ktor 另有不 save 的 `fetchStreamingResponse()`，公开面是 `HttpStatement.execute(block)`
  —— block 里**同时**拿得到 `headers` 与 `bodyAsChannel()`。
  实测（本地服务端把 body 分 10 块、每块隔 50 ms，约 500 ms 发完）：`HttpClient.request(...)`
  **886 ms**、紧随其后的 `bodyAsChannel()` 只 **7 ms**；响应体发一半就停住（连接不关）时它
  **5 s 都不返回**。
  ⇒ 对策：**要边下边用就走本项目的 `Http.request(...)`**（`acg/common/Http.kt`）——
  它内部挂的是 `prepareRequest(...).execute(block)`，即上面那个不 save 的路子。
  上游那句 `HttpStatement.execute()` 整包入口本项目**没有暴露**，别照着 ktor 文档调。
  它要求「读发生在 block 期间」—— `HttpIO` 的做法是把整条会话挂在 block 里（等一个由
  `close()` 或「会话被换掉」完成的信号），于是 headers 与 channel 全程有效。
  对照：`HttpStreamingTest`（第一次读必须**远早于**整包下发完成）+ `HttpReadContractTest`
  （流式之后帧序列仍与本地文件逐帧相同）。
  测这条时**一次只量一个调用**：曾把 `Http.request` 与 `bodyAsChannel()` 混在一个时间差里量，
  又把「下发时长」算错一个因子（写成 `块数×64×间隔`，那个 64 是多余的），得出过「大 body 会
  提前返回」的假结论、来回翻供三次 —— 时长算式要能用两个量级互验。
- **ktor 3.5.2 的 `ByteReadChannel.awaitContent(min)` 参数是「最少字节数」，不是超时**（2026-10-03 实测）。
  症状形状：把毫秒当参数传进去，编译通过、健康流上看似正常（数据到了就返回），但**静默处永远
  醒不来**（在等「凑够 min 个字节」），abort 叫不醒、旗标查不到 —— 整套「超时轮询旗标」的设计
  静默失效。实现是 `source.request(min)`，两个引擎（Java / OkHttp）的 body 通道都是 `ByteChannel`，
  它的 `awaitContent` 挂起等到 ≥min 字节或通道关闭，**没有超时重载**。
  ⇒ 「等一会儿就查旗标」必须自己包 `withTimeoutOrNull(ms) { channel.awaitContent(1) }`：
  `sleepWhile` 走 `suspendCancellableCoroutine`，超时取消是干净的（不 consume 字节），
  事件驱动性也还在（数据一到立刻唤醒）。见 `HttpIO.Response.awaitContent` 的 KDoc。
- **`async` 块里 `throw` 去表达控制流 ⇒ 把**父** `runBlocking` 一起取消，外层 `catch` 根本收不到**（Kotlin / 协程，2026-10-04 实测）。
  症状形状：**不报错，调用方拿到一个 `Int.MIN_VALUE` 之类的"没返回"哨兵值**。现场是 `HttpIO.read`：

  ```kotlin
  // ❌ 这样写，三个 abort 用例全红
  runBlocking {
    val signal = AbortSignal()
    val abort = async { signal.await(); throw Http.AbortedException() }   // ← 哨兵
    handler.onSignal { signal.raise() }
    try { readSuspend(buf) }
    catch (e: Http.AbortedException) { AvFormat.AVERROR_EXIT }            // ← 收不到
    ...
  }
  ```

  哨兵是 `runBlocking` scope 的**子协程**。`async` 里抛出的异常**不是**"留给 `await` 取的值"
  —— 它是**未捕获异常**，会 `parentCancelled` **向上取消父 `Job`**。父 `BlockingCoroutine`
  一进入 `Cancelling`，整个 scope（含 `readSuspend`）全被拆掉，那个
  `catch (Http.AbortedException)` **一次都没跑**，`runBlocking` 直接把取消异常抛给调用方。
  `system-err` 里的形状是：

  ```
  kotlinx.coroutines.JobCancellationException: Parent job is Cancelling;
    job="coroutine#26":BlockingCoroutine{Cancelling}
  ```

  ⇒ **规矩：`async` 块里绝不用 `throw` 表达控制流**，尤其当它挂在你**想保住**的那个 scope 上。
  要触发方去"通知"等待方，用 `目标job.cancel()`（现状写法）或
  `CompletableDeferred.completeExceptionally()` 再让目标 `await()` ——
  总之**别让异常从子协程漏进父 scope**。修后（也是现在的定稿写法）：

  ```kotlin
  val signal = AbortSignal()                       // private 嵌套，CompletableDeferred
  val job = async { readSuspend(buf) }
  val abort = async { signal.await(); job.cancel() }     // ✅ 只 cancel，不作声
  handler.onSignal { signal.raise() }
  try { job.await() }
  catch (e: CancellationException) { buf.size }          // 哨兵叫醒的唯一收场（为何返回正数见 http-streaming.md 第二节）
  finally { abort.cancel(); handler.onSignal(null) }
  ```

  ⚠️ 定稿后**只有一条被叫醒的路**（哨兵 `cancel` ⇒ `CancellationException`）——
  `Http.AbortedException` 连同跨层透传的 `Http.Signal` 已整体删除（见 2026-10-04 日志的
  「收窄成终稿（A 方案）」），所以这里不再需要"两条路分别接"。

  对照：`Http.Response.awaitContent` 里也有 `async { data }`，它**安全**，
  因为那里用 `select` 挑一支、`finally` 把落选支 `cancel()` 掉 —— 异常从来没被"抛出去"过。
  **两处的共同点：`async` 只用来挂等待，不用来报错。**

- **固定常数 ε 做「单帧步退」必然失效（Kotlin / FFmpeg，2026-09-22 实测）**。
  症状形状：seek 到某个关键帧落点上（例如恰好 4.0s、GOP 2 秒）再按「-1f」**不动**，
  连按只是原地重画同一帧 —— **不报错、不卡**，只是没动。
  根因：`av_seek_frame` 会把目标用 `av_rescale`（= `AV_ROUND_NEAR_INF`，四舍五入）折进
  **流的时基**，于是**半格以内一律折回当前帧自己那一格**，容器又给出同一个关键帧，
  一帧都丢不掉。实测半格（= `0.5 × 1e6 × tb.num / tb.den`）：`test.mp4`（tb 1/100000）
  = 5µs、`ramp2.mp4`（tb 1/12288）= 41µs —— **1 微秒必然被吞掉**。
  ⇒ ε 必须同时大于「半格」、小于「一帧间隔」。**边界已实测**（两份内容逐帧相同、只差容器
  时基的素材）：临界 ε 就落在半格上（tb 1/12288 → 40/41µs 之间，半格 40.69µs；
  tb 1/24 → 20833/20834µs 之间，半格 20833.3µs），上界也精确等于一个帧间隔
  （41666µs 有效、41667µs 起会一次退两帧）。
  ⇒ 结论：**固定常数 ε 不成立** —— 1ms 在 tb 1/12288 上够用、在 tb 1/24 上被半格吞掉。
  现状取 **ε = 3/4 × 已缓存的帧间隔**（`FFPlayer.frameIntervalUs`）：3/4 天然落在
  `半格 ≤ 间隔/2 < 3/4 间隔 < 间隔` 这段区间里，用不着把 `time_base` 从 native 暴露出来。

- **`AvIO.read` 在文件尾返回 `0` 而不是 `AVERROR_EOF`（Kotlin / FFmpeg，2026-10-02 实测）**
  → **无限空转**。aviobuf 的 `fill_buffer` 只把 `AVERROR_EOF` / 负数当终止（`aviobuf.c:551-558`），
  返回 `0` 走的是「读空，继续要数据」那条路。
  症状形状：wav / pcm 这类**没有重试逻辑**的 demuxer 播到文件尾后，avformat 线程 **100% CPU**、
  不报错也不返回，GC 被每次 32 KB 的分配拖垮。
  ⇒ `FileIO.read` 必须把 `RandomAccessFile` 的 `-1`翻成 `AVERROR_EOF`。同一族的坑在
  `AvFormat.seek(whence = AVSEEK_SIZE)`：无法确定长度时返回 `-1`（AVERROR），
  **返回 0 会被 ffmpeg 当成「长度为零的流」**，把后续 seek 全判成越界。

- **作废收场翻错误码而不是返回正数（Kotlin / FFmpeg，2026-10-06 实测）** → `closeAsync` **静默拖到
  8 s 量级**。seek / close 作废读作业后，`HttpIO.read` 的 `catch (CancellationException)` 若返回
  `AVERROR_*` 之类负值，demuxer 不就此收手而是**再读一轮**（`ff_read_packet` 的 `continue` 分支，
  demux.c:660/668），那次读没有 abort 可用 ⇒ 开一条新 range 会话干等闲置超时；返回 `0` 更糟，
  avio 的 bypass 直读分支 `size -= 0` 恒真、**原地死循环**。
  ⇒ 必须返回正数 `buf.size`（谎报读满 —— 那条包必然被丢，安全）。逐档推演见
  [`http-streaming.md`](http-streaming.md) 第二节。

- **作废预读通道用 `close()` 而不是 `cancel()`（Kotlin / kotlinx.coroutines）** → **不唤醒**
  停在 `channel.send` 上的读作业：预读灌满、没人消费时读作业就停在那儿，于是作废流程里紧随其后的
  `join` **死等**，症状是 seek / close 挂住不动（不报错、不抛，就是等）。
  ⚠️ `close()` 还会让**已预读**的包投递成功、落进一条没人再收的通道 ⇒ 每次作废漏一个 `AVPacket`
  （ffmpeg 侧表现为丢包，且不报错）。`cancel()` 则以 `CancellationException` 收场、已预读的包由
  `onUndeliveredElement` 归还。
  ⇒ 也正因为走 `cancel()`，**不能**照旧「`close()` + 遍历队列挨个关包」—— 队列已被丢弃。
  现状见 `AvFormat.resetChannel` 的三步固定顺序（第 2 步）。

- **两轮播放同时在飞会串帧（Kotlin / FFmpeg）**。硬约束在 native 侧：**每种流只有一块输出
  缓冲**（`SWContext::videoBuffer`）—— `AvSurfaceContext.postFrame` 整体改写它、后一步
  `AvSurfaceContext.flushFrame` 才把像素抄给平台。两轮同时走到这对调用中间，上一轮的送显就会读到
  这一轮写进去的像素。⇒ 同一时刻只允许一轮在飞（`FFPlayer.takeOverPlayback`）。
  ⚠️ 别拿 `Mutex` 解：**它是排队**，后到的调用等前一轮跑完再上 ⇒ 上一次跳转的结果照样先
  落地一次、再被下一次覆盖（「都做一遍」，不是「后来者顶掉先到者」）；而且它只盖得住被包住的
  那几个入口，`seekTo` / `play` / `resume` 全都裸奔。要的语义是**后到者顶掉先到者**。
  ⚠️ 判据**只看对象身份**（一轮 == 一个 `PTS` 对象，`pts === 当前 pts`），**不要**掺
  `pts.playing` —— 那会把「只是暂停」误判成「位置已换」，白丢解码帧。`[resume]` /
  `[stepForward]` **不换对象**，因为它们不换位置。
  ⚠️ 少了这条判据的另一半后果：被 seek 作废的那一轮会**在下一轮复活** —— 新一轮把
  `pts.playing` 再次置 `true` 时，上一轮正卡在「等视频追上主时钟」里的那个作业会继续往下走、
  补一次 `flushFrame`，而缓冲里此刻已经是**这一轮**的像素 ⇒ 那一帧显示成后面的帧。
  实测（2026-09-22，ramp 素材逐帧反查帧号）：同一帧被 `postFrame` 两次而只 `flush` 一次，
  画面上表现为**重复帧 + 之后整体落后两帧**。

- **同一轮内相邻两帧的送显顺序反了会串一帧（Kotlin / FFmpeg）**。症状形状：纯顺序播放，
  送显的像素**恰好领先自称的时间戳一帧**（实测 mp4：204 帧里 180 帧不符），不报错、不卡。
  根因同上那**一块**共享缓冲：`postFrame` 整体改写它、`flushFrame` 才抄走，所以必须
  **先等上一帧送显完、再写这一帧**。反过来（先 `postFrame` 再 `join`）本帧先覆写了缓冲，
  而上一帧的 `flush` 还等着抄 ⇒ **上一帧显示成本帧的像素**。⚠️ 排队中的下一帧正好趁
  「等视频追上主时钟」那个 `delay`（真挂起点）的空档写进来，所以这是**每一帧**都错位，
  不是偶发。`FFPlayer` 的解码作业里那条 `join` 不能省。

- **seek 后的丢帧收敛必须排在送显之前，且音频也要丢（Kotlin / FFmpeg）**。判据照 ffplay 的
  `frame_drops_early`：`diff = dpts - master_clock`，主时钟 == 收敛目标，
  `|diff| < AV_NOSYNC_THRESHOLD && diff < 0` ⇒ 解码后立刻丢。
  ⚠️ **排在 `flushFrame` 后面等于「先上屏、再决定丢不丢」**：那一路 `flushFrame` 没有返回值
  （恒 -1），而送显就发生在它那里，所以丢帧的检查必须排在**之前**。
  ⚠️ **音频也要丢**：落点是**容器级**的（实测 mp4 seek 到 5.0s，音频首包在 3.90s、视频关键帧
  在 4.0s），音频不丢就会把主时钟按它真正上屏的时间戳重新锚定到目标之前，视频只能干等时钟
  爬上来。丢的是开头**连续**的一段，声卡此刻刚被 flush，所以不会留下可听见的缺口。
  判据用 `AvFrame` 自己的时间戳（native 侧已折算成 `AV_TIME_BASE` 微秒，与 seek 目标同单位）。

