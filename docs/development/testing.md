# 测试判据的来由

只收录**已被用例固定下来的判据**：某条断言为什么必须这样写、某个硬编码值为什么取这个数。
测试代码里的注释只写「验什么」，来由记在这里；动手改测试或改被测实现前先读对应节。

播放链路的库级依据见 [playback-invariants.md](playback-invariants.md)，本文只写「测试为什么这么写」。

---

## 一、`Pointer` 与线程归属

### 1.1 关闭断言的是线程没了，不是 `isClosed` 旗标

`isClosed` 只说明「本类不再接活」，旗标置位时归属线程可能仍在退出路上。
⇒ `closeShutsDownOwnerThread` / `ownedDispatcherIsClosedAfterReleaseLands` 断言的是**线程 `isAlive`**。
⇒ 独占线程的关闭挂在 `closeDeferred()` 的完成上而非 `close()` 里，`await()` 回来时线程仍在退出路上，
所以断言前要再 `join` 一次。

### 1.2 关闭之后再投递必须当场抛，不能静默改投

kotlinx 的兜底会把「归属线程已经没了」藏起来：裸 `dispatch` 静默改投 `Dispatchers.IO`（native 调用
就跑到 IO 线程池上），`withContext` 那条抛成 `CancellationException`（看着像协程被取消）。
⇒ 三种形态（`withPtr` / `withPtrSync` / 裸 `dispatch`）各钉一条 `IllegalStateException`。

### 1.3 归还被拒必须响

共享 dispatcher 被误关之后再 `close()`，归还块根本不会跑，native 资源静默漏掉，调用方还拿到一个
「成功」的 `close()`。⇒ 归还走投递失败时异常同步冒到调用方，且断言「归还块一次都没跑」。

### 1.4 `0` 是合法空句柄

借用型的空壳传的就是 `0L`。⇒ `0` 不能兼任「没交句柄」的哨兵：「没交」只由 `null` 表示，
交了 `0` 就不再问 `initPtr`，也不因为 `ptr == 0` 省掉归还。

### 1.5 `initPtr` 的时机与次数

基类构造期子类字段还没有值，那时覆写体读到的是 `0` 且编译器不报错 ⇒ 求值只能发生在**首次读句柄**。
多算一次的后果是多建一个 runtime（`QuickJS` 的覆写体就是 `initContext`），前一个既没句柄也没人销毁
⇒ 必须钉「恰好一次」。
线程归属不是 `by lazy` 给的：惰性只管「首次读才求值、只算一次」。

### 1.6 归还要能直读 `ptr`

归还恰好跑在「已标记、未销毁」的窗口里。若归还原路也查 `isClosed`，自己就被挡在门外
⇒「`close()` 返回成功、其实什么都没释放」。⇒ `releaseImpl` 收到的是构造时给的句柄（参数），
断言 `isClosed` 此刻已为真。

---

## 二、ffmpeg 读路径（`AvFormat` / `AvIO`）

### 2.1 `AvIO.read` 返回 0 是「读空」，不是 EOF

依据见 [playback-invariants.md](playback-invariants.md) §1.2：`avio` 只把 `AVERROR_EOF` 与负数当终止。
⇒ 假 IO（`TestIo`）读到尾必须返回 `AVERROR_EOF`；「打不开」是另一回事，在更早的分支返回 `-1`。

### 2.2 读错误折成 EOF 之后，只能在「不静默」上做断言

任何负返回值都会被 avio 毒化成 `eof_reached`，`getPacket` 在 API 层分不开「出错」与「读完」
⇒ 断言只能落两处：以 `null` 收场（不挂死、不抛），且标准输出上有那条 error 日志。
`AvReadErrorFoldTest` 取 10 秒素材（≈160 KB，5 次 32 KB 读）并把失败点设在第 4 次 read：
前 3 次够 `find_stream_info` 用，错误因此必然落在 `getPacket` 阶段。

### 2.3 打开失败不许抛异常

`io_open` 回调里抛异常会 pending 在 JNI 上炸 JVM。⇒ `AvIO.Handler.open` 打不开时返回恒失败的 IO，
由 `avformat_open_input` 自己失败，Kotlin 侧以 `IllegalStateException` 收场。
泄漏本身从 JVM 观测不到，这条钉的是「失败路径能干净走完、不再崩 native」。

### 2.4 嵌套 `io_open` 失败会二次关闭 pb

HLS 探分片时先自己走一次 `io_close2` 关掉分片 pb，随后 `avformat_open_input` 才返回失败。
native 侧账本若没被 `io_close2` 销账，失败路径就拿着已释放的 pb 二次关闭，`pb->opaque` 读出垃圾当
jobject 交给 `GetObjectClass` ⇒ `EXCEPTION_ACCESS_VIOLATION`（证据见 `cxx/AGENTS.md`）。
⇒ 判据两条：分片那次 open **必须真的发生过**（`openedUrls.size >= 2`），交出去的每个句柄
**恰好**关一次（`opened == closed`，少了是漏、多了是二次关闭）。

### 2.5 时长单位是微秒，而且要等 `find_stream_info`

总时长只在格式层（`AVFormatContext.duration`），HLS 这类容器把分片 EXTINF 求和写在那里、每条流上恒
为 NOPTS；流级时长同样是 `find_stream_info` 之后才有的元信息 ⇒ 断言放在 `getStreams()` 之后，
值域按 `AV_TIME_BASE`（微秒）给。

### 2.6 PCM WAV 的时基是 1 / 采样率

`avpriv_set_pts_info(st, 64, 1, sample_rate)` ⇒ 合成 8 kHz WAV 的 `timeBaseNum = 1`、
`timeBaseDen = 8000`，一格 125 µs。
⇒ 同一个合成源要 1 s 就够（`AvFormatDurationTest` 要更宽的容差窗口，取 2 s）。

### 2.7 送显缓冲必须引用相等

1308×736 RGBA ≈ 3.67 MB/帧，60 fps 播 20 s 就是 3250 MB，堆顶在 `-Xmx` 上。
native 侧只在长度对不上时才新建（`getBufferNative` 对得上就覆写），视频尺寸恒定 ⇒ 拿到的必须是
**同一块数组**。内容相等在「每帧新建、像素恰好一样」时也成立，所以判据只能用 `===`。

### 2.8 音频缓冲只能卡「种类数」

`swr` 每帧输出的样本数不恒定（末帧还是残帧），复用不作保证 ⇒ 判据写成「长度种类数 × 4 ≤ 帧数」，
而不是「恒同一块」。真每帧新建时种类数就等于帧数。

### 2.9 seek 的丢帧收敛

容器级 seek 会把目标**四舍五入**折回流时基，偏移不足半格就落回原格（见 invariants §1.4）
⇒ 单帧步退的步长 ε 取 3/4 个 tick：下界大于半格、上界小于一帧间隔。
`AV_NOSYNC_THRESHOLD` 是 10 秒，而本工程帧时间戳是**微秒** ⇒ 阈值必须写 `10 * 1_000_000`；
写成 `10` 会把所有帧都判成「落后太多」，收敛直接失效。

---

## 三、HTTP 与传输层

### 3.1 服务端的档位各自模拟一种现场

| 档位 | 现场 | 判据要点 |
| --- | --- | --- |
| `frozen` | 播放中途断网 | 停笔但**不 close**：close 等于告诉客户端 body 结束，那是「截断」不是「卡住」 |
| `heartbeat` | 客户端撒手 | body 发完不关、每 50 ms 续 1 字节 ⇒ 服务端**看得见** `exited` 追上 `entered`，不必「睡够猜」 |
| `cutAfterBytes` | body 没发完就断链 | 客户端读会抛；这是截断，不是卡住 |
| `declareLength = false` | 长度未知 | 声明了固定长度却只发一部分是「长度对不上」，客户端抛 `ClosedByteChannelException` |
| `hangFirstResponse` | 卡在请求里 | 只有第一条停住，后续正常发完 ⇒ abort 之后下一次读才能验「重连」 |

### 3.2 `servedLimit` 取 300,000 的理由

下界要**高于开流消耗**（否则连流都打不开，测到的是「打不开流」）：443 KB faststart 素材开流只吃前
约 32 KB（`moov` 全在前 11,381 字节内），实测 `getStreams()` 发 196,608 字节。
上界要**明显低于素材总长** 443,429 字节 ⇒ 断点落在数据流中途，首帧必定出得来。
300,000 = 总长的 66%，还留 34% 没发。改媒体就要按「开流消耗 / 素材总长」重测这个数。

### 3.3 abort 的两种收场

- 有读在等：必须**叫醒**它，且被作废的那次读要返回**正数** `buf.size`（让 demuxer 立刻停下；
  推演见 invariants §1.3），代价是那条会话作废 ⇒ 下一次读重发、请求数 +1。
- 没有读在等：`abort` 必须是 no-op。`FFPlayer` 每次 seek / 单帧步进都要 abort，无条件关响应会让
  每次极小步长操作都白重开一条会话 ⇒ 判据是请求数保持 1。

### 3.4 流式判据看「首读耗时 vs 整包下发时长」

整包下发时长 = 块数 × 块间隔。首读**远早于**整包（< 一半）才说明没有在等整包。
客户端侧的 `availableForRead` 峰值只需不超一个 avio 缓冲：服务端的 `served` 只说「写了多少」，
socket 缓冲里堆了多少与客户端有没有读无关，拿它当「客户端已下载」是错的。

### 3.5 向前 seek 该白读还是该重开会话

判据是 `offset - rsp.offset - rsp.availableForRead`：扣掉通道里已有的部分（那部分白读是纯内存
memcpy、零网络成本）。可观测形态就是服务端请求数：落在存量内不涨，越过存量涨 1。
⇒ 两句断言分别守**收益**与**边界**，扣减不能形同虚设，也不能无条件放行。

### 3.6 关闭的两条路径

调用方显式 `close()` 与 JS 侧 GC 时的 `JavaObject` finalizer 可能并发落到同一条响应上
⇒ 幂等是硬要求：连关三次不抛、`isClosed` 如实反映、关掉之后 `read` 抛 `IllegalStateException`
（不能伪装成「读空了」）。`readAll` 则要求**异常路径也关**，所以实现不能写成「读完再手动 close」，
只能靠 `use`。

---

## 四、播放（`FFPlayer`）

### 4.1 `play()` 挂到整轮结束

`play()` 内部 `withContext` 会等自己的子协程，而轮次就是那个子协程 ⇒ 测试里只能 `launch`
（UI 里也是 fire-and-forget）。凡是要「播到一半再操作」的用例，都必须把播放丢到独立线程 / 协程，
否则冻结永远晚一步。

### 4.2 EOF 之后本轮必须自己收场

`AvFormat` 的预读通道在 EOF 只 `send(null)`、不 `close()`；读循环拿到 null 且队列非空时会
`delay(100ms); continue` ⇒ **第二次 `getPacket` 永久挂起**，本轮永不结束、`pause()` 的
`playingJob.join()` 一直等。症状与「暂停收不掉」一模一样。
⇒ 判据三连：等到 `Event.Frame(null)`、播放轮次自己结束、随后 `pause()` 能返回。

### 4.3 步进 / 退帧的观测点在公开事件上

观测 `Event.Frame`（每显示一帧回调 PTS、播完回调 `null`），不读内部字段。
素材 25 fps / GOP 50 ⇒ 帧间隔恒为 40,000 µs、每 2 s 一个关键帧，序列连续时可直接对期望序列下断言。
像素身份用「同一条流顺序解一遍」的整帧哈希当正本：从 IDR 起解码是确定的，所以「这一帧该长什么样」
不依赖它之前播过什么。

退帧时**空探那一跳会先把当前帧再显示一次**（画面不动）⇒ 只能取这一轮**最后**显示的那帧，
取「唯一一帧」会误判。

### 4.4 冷启动的步长只能靠流时基推

还没量到帧间隔（只显示过一帧就暂停）时，步长取 3/4 个 tick（78 µs 的 3/4 ≈ 59 µs），卡在半格
39 µs 与帧间隔 40,000 µs 之间。取 1 µs 会被折回原格、一帧都不丢 ⇒ 退帧返回 `false`、画面原地不动。
⇒ 该用例把 `speedRatio` 调到 0.01，让第二帧要等 4 秒才送显，「刚好只显示过一帧」才不必跟暂停抢时间；
`count == 1` 本身就是前提，要当场钉住。

### 4.5 stall 现场里探针顺序不能倒

`closeAsync()` 第一件事就是 `pause()`，它返回时 player 已经关掉了；之后再调 `pause()` 只是对已关的
player 置个 flag，毫秒级返回是必然的、测不到任何东西 ⇒ 先探 `pause()`、后探 `closeAsync()`。
`pause()` 的耗时上界只该是「等当前那个循环轮次走完」，给 1 s 预算管的是**量级**（实测 7～108 ms）。
耗时断言成立的前提是「此刻确实在取包超时进行中」，且 `pause()` 返回时网络仍未恢复 ——
少了这两条，断言就是空的。

收场顺序也有讲究：**先掀服务端**（parked 的响应体被 close ⇒ 那次读拿到 IOException、读作业 unwind），
再等 `closeAsync`；反过来先 close 的话播放轮次还卡在读里，`close` 自己就 join 不回来。

---

## 五、WebView 与 quickjs

### 5.1 cookie 跨 webview 共享必须用持久 cookie

manager 属于 profile、共享给同一 environment 下的所有 webview ⇒ 经 `set` 写下的 cookie 会被全新的
webview 在 `document.cookie` 里看到。
⇒ 但用例必须写**带 `max-age` 的持久 cookie**：session cookie 只活在浏览器进程内存里，Chromium 不把
它落进 `Default/Network/Cookies`，两个后台任务之间一个视图都不存在时内存态就丢了，看着像「存储没共享」。

### 5.2 不要自己调 `prepareEnvironment`

伴生对象的 `init` 已用产品那套（浏览器参数 + user data folder）备好环境；再调一次
`prepareEnvironment(null, null)` 会把浏览器参数清空、user data folder 换成默认值 ⇒ 那就不是在测产品配置。

### 5.3 拦截回调「命中即终局」

`WebResourceRequested` 一旦收到 JVM 侧的命中，就回一个空 204 并 `webview_->Stop()`，整轮加载当场中止、
不会再有第二次回调 ⇒ 「命中 → 放行 → 页面跑完」这条时序**不可达**，别把它写成用例；
「全程放行后页面能跑完」与「命中后结果在那一刻定下来」要分开验。

### 5.4 脚本无返回值回来的是字符串 `"null"`

WebView2 把 `undefined` 序列化成 JSON `null` ⇒ Kotlin 侧收到的是字符串 `"null"`；
Kotlin `null` 只表示**失败**（超时 / 脚本抛错）。两者不能混。

### 5.5 `parseSetCookie` 的字段口径

这些字段要喂给 WebView2 的 `AddOrUpdateCookie`，它**没有返回值**、属性不对也不报错，症状是「调用全
正常、cookie 永远读不到」⇒ 端到端用例只能看到「读不回」，定位不到是哪一项拼错，所以字段口径必须单测：
域名默认取 URL host 且不带端口、`Domain` 前导点要去掉（WebView2 拒绝 `.example.com` 形式）、
`Max-Age` 优先于 `Expires`、三种 HTTP 日期格式都要认、错周几不能让整条 cookie 退化成会话 cookie、
`SameSite=None` 必须同时置 `Secure`。

### 5.6 quickjs 的泄漏报告是别处判据的前提

`JS_FreeRuntime` 结尾是 `assert(list_empty(&rt->gc_obj_list))`，只要还有活引用就 abort，进程直接死、
连报告都发不出来 ⇒ `collectLeaks` 必须两件事一起做：把仍在册的对象报告出来，并在销毁 runtime 之前
逐个归还。⇒ 各用例关 runtime 前都要把包装显式还清，否则泄漏报告里混进别人的噪音。

### 5.7 finalizer 只能断言「关掉之后一定被 close」

`JS_RunGCInternal(rt, FALSE)` 里那个 `FALSE` 只管 `remove_weak_objects`，`free_object` 调 finalizer
时不看任何参数；finalizer 也可能更早跑（引用归零后任意一次分配都可能顺带触发 `js_trigger_gc`）
⇒ 不能断言「还活着时没被 close」。

### 5.8 能力桥必须打真派发

桩顶替 `_binding` 验不到派发形状（桩自己拿 `argv`，形状错了不响）⇒ 只能靠真 `JsEngine` 逐个分支锁
`args`。`console` 的 `args[1]` 是**实参数组整体**；`FormData` 的 `__js_proto__` / `__items__` 与每项
`name` / `value` / `type` / `filename` 是 `Http` 构造 multipart 时读的唯一入口，键名错一个字母就静默降级。

---

## 六、宿主体检与跳过

宿主坏掉时用例会卡满超时再报一堆看不懂的失败 ⇒ 先体检、说清原因、记成 skipped。

- 只 `println` + `return` 的话 Gradle 在 XML 里记的是 passed（`skipped="0"`），「全绿」与「一条都没验」
  长得一模一样 ⇒ 必须抛 `AssumptionViolatedException`。
- 体检**必须真跑一次 `about:blank` 导航**：环境建得成、控制器建得成、`Navigate` 也返回 S_OK，但引擎
  完全不动（连 `about:blank` 都到不了 `NavigationCompleted`）；另一种坏法是子进程崩
  （`processFailed` 报 `kind=6/4 reason=3 exit=7`）。两种都像「代码没写对」，而重装 Evergreen Runtime
  通常能好。
- 想看原始失败：加 `-Dacg.test.skipUnavailableHost=false`。

---

## 七、测试基础设施四条纪律

- `:platform` 的测试**不依赖** `:shared`（依赖方向反了）⇒ 页面服务端、cookie 管理、路径注入在两侧各
  一份。两份 `TestPageServer` 的 PNG 与页面形状**必须一致**：改一处漏一处就是隐性的假红。
- `Paths.init` 只能成功一次 ⇒ 注入必须「可见性 + 互斥」且只做一次，多 fork / 并行下也一样。
- 会阻塞、可能永不返回的用例，被测调用必须放**守护线程**跑：主线程挂在 `join` 上会把整个构建吊死。
- 归属线程同名（`quickjs` / `avformat` 等），线程 dump 无法按名区分 ⇒ 摘要里额外打**栈顶帧**，让人认出谁是谁。
