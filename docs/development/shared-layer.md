# shared 层的行为约束与外部依据

`shared/src/commonMain` 的源码注释只写「这个类 / 方法做什么」。本文收被搬出来的那一半：
**写错会静默失效、崩溃、或依赖调用顺序的约束**。动手改这些文件前先读对应节。

通用依据不在这里重复：HTTP 传输层与协程判据见
[playback-invariants.md](./playback-invariants.md) 第四、六节。

---

## 一、HTTP 上的 `AvIO`（`player/HttpIO.kt`）

### 1.1 读必须阻塞在 IO 层

`AvIO.read` 的回调来自 native 的 `av_read_frame`，aviobuf **没有「稍后重试」这个返回值**。

⇒ 数据没到就一直等，不能返回 0 让上层重试；返回 0 在 aviobuf 的 bypass 分支里是原地死循环。

⇒ 阻塞只允许出现在 `HttpIO.read` 一处（`runBlocking`）：`AvIO.read` 要同步签名，而建会话 / 等数据 /
推游标整条链路都是 `suspend`。

### 1.2 叫醒阻塞读的手段是取消协程

读卡在 `av_read_frame` 里，协程取消够不到它，只能由 `AvIO.Handler.abort` 从另一条线程投递信号。

⇒ 信号实现是 `AbortSignal`（`CompletableDeferred`）：投一次即够，没读在等时投递口为空、等于 no-op。

⇒ 旁路哨兵**只能 `cancel` 掉读作业，不能 `throw`**。哨兵是 `runBlocking` scope 里的子协程，抛出的异常
不是留给 `await` 的值而是未捕获异常，会 `parentCancelled` 拆掉整个 scope，`catch (CancellationException)`
一次都不跑，`read` 直接以 `Int.MIN_VALUE` 收场。

⇒ 投递口必须在 `finally` 里摘（`handler.onSignal(null)`），且 `abort` 哨兵要在 `finally` 里取消，
否则正常收场时 `runBlocking` 永远等它。

### 1.3 作废收场返回本次请求的长度

被作废叫醒的那次读必须返回**正数**，取 `len`：

| 返回值 | 后果 |
| --- | --- |
| 负数（`AVERROR_EOF` 等） | demuxer 再读一轮，开新 range 会话干等闲置超时 |
| `0` | aviobuf 原地死循环 |
| `len` | demuxer 当场收手 |

⇒ 不能写 `buf.size`：数组是复用的，容量可能远大于本次请求，报大了会被 native 的 over-ran 兜底 clamp。

⇒ 「谎报读满」安全：这些字节并没有真读出，包也必然被丢（通道已被 `AvFormat.resetChannel` 掀掉）。

### 1.4 作废的代价是那条会话作废

取消阻塞读 = 关掉那条连接（见 playback-invariants.md §4.3），所以作废之后那条会话不能再复用。

⇒ 判据用 `Http.Response.isClosed`：只丢真的被掀掉的那条 —— 作废也可能落在建连上，那时手上这条还活着。

⇒ `close()` 与 `abort()` 不是一件事：`close` 无条件释放，`abort` 只投信号。合成一个会把「作废」错当
「关会话」。

⇒ 作废后不丢弃这条会话，下一次读会拿到一串 `-1`，那是**假 EOF**：上层会以为文件播完了。

### 1.5 换会话的判据

前向跳时判据是 `offset - rsp.offset - rsp.availableForRead > 0`，即「还没从网络上取到的部分不为零」。

⇒ `0` 不是可调阈值：`short_seek_threshold` 以内的前向跳由 aviobuf 自己 `fill_buffer` 吸收，交到 IO 层的
必然是长跳；`<= 0` 说明目标已在本地存量里，重开只白花一次往返。

⇒ 服务端给不出精确起点（`newRsp.offset != offset`）时保留手上这条、退回白读推进。

⇒ 复用会话与新建会话的收场不同：新建那条抛异常时必须自己 `close` 并摘掉 `cachedRsp`，否则下次读会拿
一条没推到位的会话当地基。

### 1.6 闲置超时不向上抛

`Http.IdleTimeoutException` 表示「两次数据包之间超时」，会话多半已死。

⇒ 就地接住、丢掉会话、等一个超时间隔后换一条重试，**不限次数**。抛上去会被 aviobuf 毒化成 EOF，
播放静默「正常结束」；时间闸在 `FFPlayer` 的取包超时那层。

### 1.7 进度只记一处

`rsp.read` 自己推进网络游标，`HttpIO` 只推逻辑游标 `offset`。两处都记会让逻辑游标虚高，下次读白白重开会话。

---

## 二、本地文件的 `AvIO`（`player/FileIO.kt`）

⇒ EOF 必须翻成 `AvFormat.AVERROR_EOF`，不能返回 0：aviobuf 只把 `AVERROR_EOF` / 负数当终止，返回 0 是
「读空，继续要数据」，`wav` / `pcm` 这类 demuxer 会在文件尾无限空转（依据见 playback-invariants.md §1.2）。

⇒ `AVSEEK_SIZE` 拿不到长度时返回 `-1`；返回 0 会被当成「长度为零的流」，后续 seek 全判越界。

⇒ `close()` 跑在 native 的 `io_close2` 回调里，异常逃出去会 pending 在 JNI 上没人接 ⇒ 内部吞掉。

⇒ `file://` 形式的 URL 只剥前缀会剩下 `/D:/a/b.mp4`，Windows 上打不开 ⇒ `urlToPath` 还要去掉盘符前
那个多余斜杠。

⇒ 受 `AvIO.seek` 的 `Int` 签名与 native 侧 `(II)I` 描述符限制，只对小于 2 GiB 的文件正确。

---

## 三、JS 引擎（`engine/JsEngine.kt`）

### 3.1 惰性首建必须加锁

`quickjsDelegate` 是 check-then-act：两个协程并发首访会各建一个 `QuickJS`，被覆盖的那个永不关闭
（runtime + 归属线程一起泄漏）。

⇒ 锁里的 `runBlocking` 不死锁：等的是 QuickJS 的归属线程，而那条线程上的回调都不进这把锁。

### 3.2 `init` 的类型不能放宽

`init.js` 的求值结果是工厂函数，调用完要归还。

⇒ 必须 `as JSFunction` 才能拿到 `AutoCloseable`；写成 `as JSInvokable` 会丢掉 `close()`，每个引擎实例
固定漏一票引用。

### 3.3 归还 JS 实参走 `invokeOnCompletion`

⇒ 不能用协程体里的 `finally`：Deferred 被取消、协程体没跑起来时它不执行，那一票会一直挂在登记表上。
`asyncReleasing` 因此用 `invokeOnCompletion`。

⇒ 形参类型收窄成 `JSRef` 而不是 `AutoCloseable`：`QuickJS` 自己也实现了 `AutoCloseable`，接受它就等于
允许把 runtime 传进来当场销毁。

### 3.4 `callPlugin` 不缓存模块引用

每次调用现 `import()` 一次，导出函数用完即 `close()`。

⇒ 缓存导出表就要有人负责放掉它，那笔账没有归属；现取现还最简单。

⇒ 插件导出基本都是 `async`，Promise 在 `callPlugin` 里收口成 `Deferred` 一并 await。

### 3.5 `webviewAsync` 的终态与骨架

⇒ 终态只取第一个（`CompletableDeferred.complete` 幂等），骨架固定：建上下文 → 配拦截 → 导航 →
等终态 → 超时兜底 → `finally` 销毁。

⇒ 返回值形状由 `init.js` 的 wrapper 判断：命中拦截是 `{ url, headers }`，有脚本是 JSON 字符串，无脚本是
`null`。

⇒ 脚本执行要丢到 `Dispatchers.IO`：`evaluate` 挂起等结果，而平台回调线程（桌面挂着消息泵）不能钉住。

⇒ 脚本自身抛错静默成 `null`，只让环境起不来 / 导航失败 / 超时这三种走 `JSError`。

⇒ 桥不许用 `import()` / `require()`，理由与崩溃点见 playback-invariants.md §6.4。

### 3.6 `openBrowserAsync` 的用法归插件

⇒ `{{callback}}` 换进去的值必须在查询串里百分号编码（`: / ? #` 都要编）。

⇒ 插件侧 `redirect_uri` 取 `split("?")[0]`，参数逐段 `decodeURIComponent`，不能整串解：值里的 `%26`
会被解成 `&`，把参数切开。

⇒ 这里不做 token 交换，也不设超时（用户可能把浏览器挂很久）。

⇒ 百分号编码按字节判 ASCII，非 ASCII 一律进 `%XX`，换进去的值在任何位置都合法。

---

## 四、Bangumi 客户端（`source/BgmSource.kt`）

### 4.1 数字要按两种标签读

QuickJS 桥按内部标签分派 JS 数字（`JS_TAG_INT` → `Long`、`JS_TAG_FLOAT64` → `Double`），而 `JSON.parse`
对 `7` 与 `7.0` 给出的标签不同。

⇒ 同一字段两次请求可能落到不同分支 ⇒ `asLongOrZero` / `asDoubleOrZero` 必须同时认 `Long`、`Int`、`Double`。

### 4.2 字段缺失当「没有」

⇒ 接口加字段是常事，缺字段或类型不符不抛，取默认值；只有「整个应答根本不是对象」才算真出错。

⇒ `score` / `rank` 为 0 表示没有，界面按「没有」画。

### 4.3 设置键的归属

⇒ `bgm.client_id` / `bgm.client_secret` 由宿主写，`bgm.session` 由插件写；两个键名两边各有一份，
改一处就得改两处。归属说明见 [bgm-plugin.md](../design/bgm-plugin.md)。

### 4.4 收藏应答的两层

每项形状是 `{subject: {…}, subject_id, type, rate, ep_status, …}`：条目名 / 封面 / 评分在 `subject` 里，
用户自己的进度 `ep_status` 在**外层**，两边都要读。

⇒ 封面取 `images.grid`。列表里是小图，取 `large` 等于白等一趟大文件。

---

## 五、界面层（`ui/comp`、`ui/screen`）

### 5.1 图片缓存（`ui/comp/RemoteImage.kt`）

⇒ `sizeOf` 必须**具名**传：`lruCache` 的末位参数是 `onEntryRemoved`，尾随 lambda 会绑错位置。

⇒ 预算按解码后字节（`宽 × 高 × 4`）算，不按下载字节：吃内存的是解码位图。

⇒ 只缓存成功的解码结果：一次瞬时失败粘住，那张图在这个进程里就永远画不出来。

⇒ 有 `LazyColumn` 就有反复重建，没有缓存等于每滚一轮重下一遍；`LruCache` 内部有锁，跨线程安全。

### 5.2 `RemoteImage` 的取图

⇒ 用 `remember(url)` 而不是 `produceState`：后者不带 key，换 url 时上一条的图会留在原处（列表里张冠李戴）。

⇒ 必须 `redirect = "follow"`：`Http.request` 缺省不跟随重定向，图床一跳就是 302，响应体解码必失败。

⇒ 取消不等于加载失败：吞掉 `CancellationException` 会让 `LaunchedEffect` 的取消失效，界面停在假的
「已加载」上 ⇒ 原样抛出。

⇒ 共享层没有平台符号，解码入口只能是 `ByteArray.decodeToImageBitmap()`，所以走 `Http` 而不走平台图片库。

### 5.3 收藏页的过期应答（`ui/screen/CollectionState.kt`）

换筛选是连点就能触发的，两轮请求会同时在飞。

⇒ `generation` 每 `reloadList` 自增一次，写回状态前比对「自己那一代是否仍是最新」，不匹配就整段丢弃。

⇒ `logout` 也要自增 `generation`，否则退出后过期的应答会把列表写回来。

⇒ `loadingMore` 与 `loading` 分开：翻页时列表还在，只该在尾巴上加个圈。

⇒ 登录前先存凭据：授权那步会跳出应用，中途放弃不该丢掉刚填的值。

### 5.4 列表与筛选的呈现

⇒ 条目类型与收藏状态都是必选维度（接口按这两个维度分页），没有「全部」这一档：串起五档要跨五个分页
游标，不是「参数留空」能表达的。

⇒ 筛选 chip 加载中不置灰：连点换筛选由 `generation` 兜住，置灰只会让整排闪一下。

### 5.5 预览检查的豁免

`App()` 与三个页签都挂 `@Preview`，同时是真实入口 / 真实页签，不能是 `private`。

⇒ `preview-public-check` 只认「有 `@Preview`」，这几处按误报 `@Suppress` 掉。

### 5.6 桌面端 WebView 要真窗口

⇒ `WebScreen` 里的原生视图是真的 Win32 窗口，靠 JAWT 取 `HWND` 再 `SetParent` 挂进来 ⇒ 桌面端必须跑在
基于 AWT 的窗口里，细节见 [webview2-windows.md](./webview2-windows.md)。

⇒ 页面与插件脚本的后台 WebView 共用同一套 cookie ⇒ 这里登录过的站点，脚本那边也是登录态。
