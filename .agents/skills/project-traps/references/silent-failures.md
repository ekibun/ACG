# 不报错、只失效

> 条目原样移自根 [`AGENTS.md`](../../../../AGENTS.md) §2（原题「会静默失败的几件事」），
> **未逐条对照代码重新核定**。§2 现在只留索引，正本是本文件。

本项目最危险的一类问题**不报错、只失效**，排查时最容易误判成"代码没写对"。动到下面任一条之前先读它：

- **改过 native 却用着旧 dll** → 不报错，只是参数错位。改 Kotlin 侧签名后，第一件事是确认 dll
  已刷新到运行位置（落位见 skill `build-and-test` 的
  [`dll-sync.md`](../../build-and-test/references/dll-sync.md)）。
- **漏拷 dll** → 症状是"改动没生效、连日志都没有"。dll 要落到三个位置，见同一份 `dll-sync.md`。
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
