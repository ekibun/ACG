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

⇒ 阻塞只允许出现在 `AvIO.read` 一处（`runBlocking`）：它要同步签名，而建会话 / 等数据 /
推游标整条链路都是 `suspend`。`HttpIO` / `FileIO` 各自只写挂起主体 `runSuspend`。

### 1.2 叫醒阻塞读的手段是取消协程

读卡在 `av_read_frame` 里，协程取消够不到它，只能由 `AvIO.Handler.abort` 从另一条线程投递信号。

⇒ 信号实现是 `AbortSignal`（`CompletableDeferred`）：投一次即够，没读在等时投递口为空、等于 no-op。

⇒ 投递口由 `IOHandler` 持有（`onSignal` 挂上 / 摘掉），`abort()` 投的正是它。
所以一个 handler 只能服务一路读：HLS 换分片是「上片读完并关掉、才开下片」，串行成立。

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

⇒ 读同样走 `AvIO.read`（`runBlocking` + 作废时返回 `len`）：本地读不等网络，但收场语义必须与 HTTP
一致，否则 `AvFormat.resetChannel` 的作废对两种实现是两套行为。`FileIO` 只有 `runSuspend` 是自己写的。

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

### 3.5.1 WebView 每次调用都是一个任务

`webview` 不是「开一个窗口取点东西就关」，而是一个**可以被用户看到、被挂起、被关掉的任务**
（`WebviewTask`）：默认在后台跑，界面上缩成右下角一个胶囊，点开能切到前台。

⇒ **超时不在这里掐**。原来是整轮 30 秒硬超时，那会把「用户正在输密码」直接掐断；
现在只有**放到后台满 30 秒**才强制关闭，挂在前台等操作时不计时。

⇒ **前台同时只有一个**（`WebviewTasks.foreground`）。提第二个时第一个自动退到后台并开始倒计时
——两个可见窗口叠在一起，用户分不清该点哪个。

⇒ **`show = true` 的任务没有「后台」**（`WebviewTask.foregroundOnly`）。调用方正 `await` 着它，
收进后台改变不了「没人操作就等不到结果」，只是把失败推迟 30 秒；所以界面上只给刷新与关闭，
用户关掉它就是 `null` —— 和别的任务一样，关闭不是失败。这类任务显示期间独占前台，后来者进不来
——用户正卡在登录页中间，不能被顶掉换成另一个页面，新开的那个直接报错。

⇒ **切前后台、切任务都不重建**：每个任务各有一个常驻 `WebViewHost`，用 `key(task)` 钉住它在
`Column` 里的位置；前台那个是 `weight(1f)`，其余是 `height(0.dp)`。切前台只是改高度——
不建新组件、不换父节点、不 `SetParent`、也不加载第二次。原生窗口跟着缩到 1×1 藏着，
peer 与父子关系都不动。表单内容、滚动位置、登录进度都留在原处，这也是「后台抓页面时被验证码
拦住，用户切上去点一下能接着走」的前提。

这条比「省资源」重要得多：**摘组合 = 销毁 AWT peer = WebView2 的原生窗口被系统一并销毁**，
再挂回去是给一个可能已经不存在的窗口 `SetParent`。收成 0 高就绕开了整条链路。

**手工验法**（没有对应用例：它要真 WebView2 宿主，量的是平台行为而不是我们的代码）：
`./gradlew :shared:debugCode` → 填「示例：后台」→ 点 `Run>` → 右下角出现胶囊后点开切前台，
页面应保留原状态；再点胶囊收回去，来回几次都不该白屏或丢内容。

⇒ **脚本返回 null 表示「还没到位」**，等下一次 `PageFinished` 再跑。少了这一条，登录页刚加载完
就触发 `PageFinished`，脚本一看「还在登录页」返回 null，任务当场就结束了，用户根本没机会输密码。
`null` / `"null"` / 空串都算没结果；`false` 之类是真值，照收。用户在前台手动过验证码后，
跳转触发的下一次 `PageFinished` 照样让脚本收尾——不需要为前后台写两套判定。

⇒ 后台态省资源：Android 关掉 `blockNetworkImage`；桌面缩到 1×1（`fitToParent` 的下限），页面照常活着。

⇒ **摘窗口只是兜底**，位置在 `WebViewCanvas.removeNotify()` 里、`super` 之前。宿主常驻之后这条路径
只在「任务被注销」和「整个窗口关闭」时才会走；前者上下文已经 `close`、`isClosed` 直接跳过，所以
实际只剩退出时跑一次，`submit` 异步投递足够（`detach()`）。

这条**曾经**必须同步：只留一个宿主时，切任务会卸载 canvas，异步投递会晚到，`detach from AWT
(parent alive=0, webview alive=1)` —— `SetParent` 失败，窗口成了没人管的孤儿。现在每个任务各有
常驻宿主，这条路径不会再走到；但如果哪天改回「卸载再挂回」，同步必须跟着回来。

同理 `setVisible(false)` 在桌面上是空实现：缩成 1×1 后再挂回去，尺寸没变化时 `fitToParent` 不会重算。

⇒ **重新挂载点在 `addNotify()`**。`componentShown` / `componentResized` 也会把 `attach` 带出来，
但那是顺便：尺寸没变时 resize 不来，复用的组件也不会再走一次 show。addNotify 是唯一必然发生的点。

⇒ 这条链**没有自动回归**（原来的 `WebviewCanvasRemountTest` 随 jvmTest 精简删掉了，它要真开 AWT 窗口）：
手工验法是 `debugWeb` 里把 WebView 组件挂上、摘掉、再挂回同一个 `Container`，看页面里的
`innerWidth` 是否仍是窗口宽度（窗口没了 `evaluate` 直接返回 null，没重贴则是 1）。

⇒ 任务列表由引擎登记（`WebviewTasks`），界面只读：这样「请求先到、界面后组合」也能追上。

⇒ **任务工具栏的状态（地址 / 标题 / 进度）由 `WebViewEvent` 折出来**（`WebviewPage.reduce`），
不另开通道：`PageStarted` 清进度、`Progress` 给小数、`PageFinished` 收成 1、`TitleChanged` 给标题
（空白当没给，界面回退到地址）。进度条那行高度固定，否则每次开关都会带着 WebView 重新贴边、
页面抖一下。

⇒ **后退 / 前进只给前台任务**（`history = task.foregroundOnly`）：用户正自己操作那个页面，退回去是
正常需求，返回键也走同一条路。后台任务的面板不画 —— 脚本自己在跑，让用户在里面随便逛只会把流程带偏。

`WebScreen` 本身不可直接复用——它自己 `createWebviewContext()`，接待不了外部传进来的任务；
共用的是 `WebViewPane`：工具栏 + 进度 + 承载面 + 后端起不来时的提示，参数只收普通状态，
承载面由调用方以 content 传入（两边拿控制器的途径不同）。

⇒ **覆盖层的点击拦截不必自己写**：`WebViewPane` 是 `Surface`，本身占住命中区域；后台任务面板
高度为 0，点击自然落到页面上。外层再叠一层 `background` + 吞事件的 `pointerInput` 是重复的。

这条**没有自动用例**：它量的是 Compose 的命中测试（兄弟布局 + `Surface` 占位），不是本工程的代码。
手工验：`debugCode` 里后台时点页面该有反应，切前台后点顶栏该没反应（原来那条 `ImageComposeScene`
用例只是把这个框架行为钉了一遍，删掉不丢判据）。

⇒ 工具栏只用 `←` `→` 与中文标签，不引图标库：`⟳` 这类符号在 Android 系统字体里不一定有，
会渲染成方块。按钮可点区域靠 `clickable` + `padding` 撑开，命中范围不随字形宽度缩水。

⇒ **新窗口一律接管到当前页**。右键「在新窗口中打开」、`target="_blank"`、`window.open` 都会触发
WebView2 的 `NewWindowRequested`；不接管的话它会弹一个不受我们管、也不在 AWT 布局里的顶层窗口。
WebView2 没有「只关掉菜单里那一项」的设置，`AreDefaultContextMenusEnabled(FALSE)` 会把整个右键
菜单（复制、粘贴）一起废掉，所以接回调更划算。

### 3.6 `oauth` 的用法归插件

⇒ `{{callback}}` 换进去的是回调地址**原文**，宿主不转义 —— 模板把占位符摆在放得下裸地址的位置
（查询串的值：`:` `/` `?` 在 query 里本来就合法，RFC 3986 §3.4）就是一条合法授权链接，摆哪是插件的事。

⇒ 插件侧 `redirect_uri` 取 `split("?")[0]`，参数逐段 `decodeURIComponent`，不能整串解：值里的 `%26`
会被解成 `&`，把参数切开。

⇒ 这里不做 token 交换，也不设超时（用户可能把浏览器挂很久）。

---

## 四、Bangumi 客户端（`data/` 与 `files/js/module/bgm.js`）

### 4.1 数字要按两种标签读

QuickJS 桥按内部标签分派 JS 数字（`JS_TAG_INT` → `Long`、`JS_TAG_FLOAT64` → `Double`），而 `JSON.parse`
对 `7` 与 `7.0` 给出的标签不同。

⇒ 同一字段两次请求可能落到不同分支 ⇒ `asLongOrZero` / `asDoubleOrZero` 必须同时认 `Long`、`Int`、`Double`。

### 4.2 字段缺失当「没有」

⇒ 接口加字段是常事，缺字段或类型不符不抛，取默认值；只有「整个应答根本不是对象」才算真出错。

⇒ `score` / `rank` 为 0 表示没有，界面按「没有」画。

### 4.3 登录态归账号，不归设置

⇒ 宿主持有账号表（`data/AccountRepository.kt`），每个账号一份会话；插件不写登录态，也不读
`Settings` 里的键。会话的形状、清 cookie 的坑与切账号的做法见 §5.9。

⇒ **插件收的是整个账号，不是拆开的字段**：`openHome(account)` 的唯一参数是
`{ site, id, name, session, avatar }`，由 `Account.toPluginParam()` 摊出，调用点显式转换 ——
桥不认识 `Account`，也不替调用方猜字段（见 [quickjs-lifetime](./quickjs-lifetime.md)）。认 cookie 要
`session`、拼站点路径要 `id`；以后加字段只改这一处。账号表键 `key`（`<站点>:<用户 id>`）由 `site` 与
`id` 算出，不给插件、也不进落盘 JSON。

⇒ **没有「自动识别 `@Serializable`」这条路**：注解是 BINARY 保留、也不生成公共接口，common 里没有
`KClass → KSerializer` 的入口（`serializerOrNull(kClass)` 是 JVM 专属、内部靠反射找 `$serializer`）。
何况「哪些字段能出门」本来就是业务判断 —— 该给插件的只有 `Account` 自己说得清的那几项。

### 4.4 收藏取自两处网页

- **主页追番块**（`.infoWrapper[subject_type]`）：整档一次列全，条目带进度（`6/12`、`5/??`），但没有那行
  灰字；只有书 / 动画 / 真人三类的「在X」，音乐与游戏没有块（`HOME_BLOCK_KEY` 只有三档）。
- **列表页** `<类型>/list/<用户>/<状态>`：一页一页翻，条目带灰字（话数 / 日期 / 制作），但没有进度。

⇒ 那块在主页上只有非空才渲染，所以「解出了动画块、没有三次元块」就是三次元这档空着 —— 三块同一张主页，
缺一块不必再去翻列表页问一次。

⇒ **块里就是这一档的全部，不是「最近更新」的截断**：2026-11-13 用真账号实测，主页解出 21 部动画 / 35 本书，
`/anime/list/<用户>/do` 第一页正好 21 条且 `pageTotal = 1`（没有第二页），两边 id 集合完全相同。所以这三档的
`pageTotal = 1` 是事实，不是妥协 —— 宿主不必也不能再为它们续读。

⇒ **但两处的顺序不同**：主页块按更新时间排，列表页按站点默认序。宿主只走主页那一路（这三档从不翻列表页），
所以不会混；将来若要让「刷新」改走列表页，必须整档替换，不能按页拼。

⇒ **列表页分页本身实测过**：`#browserItemList > li.item` 每页 24 条，`#multipage a[href*='page=']` 取最大值
即总页数（`anime/collect` 24 页、`anime/wish` 4 页、`book/do` 2 页且末页 11 条、`real/do` 0 条无分页条）；
相邻两页 id 无重叠。没有分页条时 `pageTotal` 落回 1。

⇒ 两处各缺一半字段，所以 `CollectionItem` 的 `summary` 与 `progress` 都可空，界面有就画。

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

### 5.3 收藏的状态机（`data/CollectionTable.kt` + `data/CollectionRepository.kt`）

一档的全部状态都在 `CollectionCategory` 里：读到过哪一页（`page` / `pageTotal`）、在飞的是哪一页
（`loadingPage`）、哪一页失败了（`error.page`）。界面不再另记「正在刷新」「刷新失败」这类布尔 ——
转哪个圈、错误显示在整页还是顶上、尾巴要不要续读，全由这三个字段推出来。

⇒ **表与调度分两个文件**：`data/CollectionTable.kt` 是四个数据类加 `begin` / `merge` / `fail` /
`toCollectionCategories` 四条纯规则，不碰协程，测试直接喂它；`data/CollectionRepository.kt` 只剩那个
`object` —— `scope`、`jobs`、`load` / `clear` / `fetch`。分界就是「有没有副作用」。

⇒ **在飞的是哪一页，不是「在不在飞」**：`loadingPage == 1` 且手上有内容就是刷新（转顶上那个圈），
`> 1` 是续读（转尾巴那个圈），`page == 0` 是首读（转整页那个圈）。一个字段覆盖三种，不必再对齐第二份状态。

⇒ **失败同样记页号**：`error.page` 既是「哪一页坏了」，也是「重试该要哪一页」。刷新失败（`page == 1`
且 `data` 非空）与续读失败（`page > 1`）因此是同一个字段的两种取值，界面按位置分开显示，数据层不必
判断「这次算不算刷新」—— 那是界面的事，之前把职责放反了，才会一边写 `error` 一边在 UI 里再记一遍。

⇒ **开始读一页就先落状态，并作废上一次的失败**：`load` 是普通函数（不是 `suspend`），第一行把
`loadingPage` 写上、把旧的 `error` 清掉，再在自己的 scope 里发请求。界面点完刷新当帧就能看到圈，
中间没有「已经点了但还没开始」的空档 —— 那个空档正是尾巴会抢先发出续读的地方。整份读（键表）走同一条
规矩，否则「正在重读」与「上一轮为什么失败」会同时成立，界面只能靠先判 `loading` 才盖得住旧错误。

⇒ **请求的 job 归 Repository**：`load` 不跑在调用方的协程上，取消只影响这张表，不会连带取消调用方的
`launch`。同一档只认最后提交的一次：新的进来先取消上一轮；取消只在挂起点生效，「await 刚返回」那一瞬
拦不住，所以写回前仍比一次 `jobs[key] === self`。

⇒ **换账号整张表换掉**：在飞的全部取消，表换成新账号的空表。界面只认 `table.accountKey == account.key`
的表，换账号那一帧不会把上一个人的档画出来。

⇒ **登出也清表**：删掉当前账号时整张表一并清空（`CollectionRepository.clear()`）。留着的话，同一个人
重新登录时 `accountKey` 一样、档也不空，界面直接摆出上一次的数据，一次都不重读。

⇒ **页号与「在飞」分开**：`page` 是已经并进 `data` 的最后一页，续读只关心它；`loadingPage` 只决定
哪里转圈、以及什么时候可以再要下一页。

⇒ **按 `id` 去重，两条路都去**：跨页拼接时站点可能在两次翻页之间新增一条，后一页把前一页最后那条再给
一次；同一页里插件也可能把并列的条目给两遍。列表拿 `id` 当 key，重复即崩 —— 所以整档覆盖与续读拼接
两边都过一遍 `distinctBy`，不是只在拼接处去。

⇒ 登录前先存凭据：授权那步会跳出应用，中途放弃不该丢掉刚填的值。

### 5.3.1 收藏页不存状态（`ui/screen/home/tab/collection/`）

页面里没有任何 `remember` 的「刷新中 / 刷新失败 / 暂停续读」—— `CategoryBody` 完全按传进来的
`CollectionCategory` 渲染，换档、换账号、取消都不会留下对不上的残留。

⇒ 页面拆成三个文件：`CollectionScreen`（`CollectionPage` 与预览）、`CollectionFilters`（tab + chip）、
`CollectionList`（条目、尾巴、刷新失败条，末尾留「条目」那张预览）。其他 tab 页不动。

⇒ `CollectionScreen` 只是接线：读当前账号、表、选中档，把它们交给 `CollectionPage`；预览也摆 `CollectionPage`，
不必为了看一眼整页真的登录一次。`CollectionPage` 拿 `account` / `table` / `picked` / `load` 四个参数，
没有全局依赖，两个 `LaunchedEffect` 也只在这层（`load` 过 `rememberUpdatedState`，不进 effect 键）。

⇒ 函数就三层：`CollectionScreen`（读三个全局）→ `CollectionPage`（`Scaffold`、两排筛选、一档的装配）→
`CategoryBody`（提示条 + 下拉刷新 + 四条判据）→ 列表与条目。中间不留只做参数转手的层 ——
那种层每多一个，改一个动作就要顺三处改签名。唯一的例外是这层 `CollectionPage`：它换来了「整页可预览」，
而不是把同一个参数原样往下递。

⇒ 它在 `ui/screen/home/tab/collection/` 下，与主页另外三个 tab 同级：自带 `Scaffold` 与顶栏，
但仍是主页正文的一个 tab，不单独占路由。

⇒ 只有列表能下拉：`PullRefreshBox` 只包 `CollectionList` 那一支。空页与错误页没有可滚的东西，手指的位移
进不了嵌套滚动 —— 曾经为它们各套一层撑满视口的 `LazyColumn`（`fillParentMaxSize`），那是为了让「下拉重试」
在这两个状态也成立，代价是每张状态页都挂一个为手势而生的滚动容器。这两态本来就有出口：错误页有「重试」按钮，
空页有顶栏刷新，所以那层容器删掉了。

⇒ 判据先看有没有内容：非空就是列表，刷新中与刷新失败都只叠在它身上，不遮内容。空白时才按
**读 > 失败 > 空** —— 三者同时成立只有「读到过空页、这一轮又失败了」这一种，那时该报没读下来，
画成「还没有收藏」等于把失败说成成功。

### 5.4 列表与筛选的呈现

⇒ 条目类型与收藏状态都是必选维度（站点按这两个维度分页），没有「全部」这一档：串起五档要跨五个分页
游标，不是「参数留空」能表达的。

⇒ 档的键（`动画|在看`）由插件给，宿主原样存、原样传回；界面按 `|` 拆开，**不硬编码类型表**：第一段是
类型（主维度）给成 tab，第二段是状态（次维度）给成 chip。

⇒ tab 的排布自己算断点：`BoxWithConstraints` 量到的是**内容区**，窗口到 600 时导航条换成 80dp 侧栏，
内容区就只剩 520 —— 所以这里的常量是 520，不沿用导航条那个 600。够宽按文字宽靠左
（`PrimaryScrollableTabRow`：起始留白 16dp 与下面那排 chip 对齐、最小宽 48dp），窄了横向均分铺满
（`PrimaryTabRow`）—— 窄屏还按文字宽排，右边会白半行。tab 下面紧跟着状态 chip，分割线去掉。

⇒ 只有一个类型的那排 tab 不出现（只剩状态 chip），插件只给一个默认档时两排都没有，那就是一个平铺的列表。
「有没有这个维度」由插件给的档数决定，不按站点硬编码。

⇒ 筛选 chip 加载中不置灰：同一档的前一轮会被后一轮顶掉（`jobs`），置灰只会让整排闪一下。

⇒ 中文名与原名一字不差时不画第二行：条目按「有没有中文名」判会把同一行字画两遍。2026-11-13 用真账号
实测，音乐那档 1/1 条相同、主页追番块 3/56 条相同 —— 所以判据是两者**不相等**，不是 `nameCn != null`。

### 5.5 预览检查的豁免

`App()` 与 `HomeTabLayout` 的预览都挂在真实入口 / 真实组件上，不能是 `private`。

⇒ `preview-public-check` 只认「有 `@Preview`」，这几处按误报 `@Suppress` 掉。

### 5.6 桌面端 WebView 要真窗口

⇒ `WebScreen` 里的原生视图是真的 Win32 窗口，靠 JAWT 取 `HWND` 再 `SetParent` 挂进来 ⇒ 桌面端必须跑在
基于 AWT 的窗口里，细节见 [webview2-windows.md](./webview2-windows.md)。

⇒ 页面与插件脚本的后台 WebView 共用同一套 cookie ⇒ 这里登录过的站点，脚本那边也是登录态。

### 5.7 返回的分层（`ui/nav/BackHandler.kt`）

Android 的返回键 / 手势与桌面端的 Esc 是同一个事件：`navigationevent` 的 dispatcher，两端已由
Compose 接好（桌面见 `ComposeSceneMediator` 的 `BackNavigationEventInput`，只认 `Key.Escape`；
Android 见 `ComponentActivity` 的 ViewTree owner）。所以只写一份，不分平台。

⇒ **后组合的先收到**（dispatcher 按加入顺序倒着找），所以 `HomeScreen` 那条排在 `App` 的路由栈之前，
最后才是平台默认。两处都靠 `enabled` 开关而不是 `if` 掉调用 —— 组合顺序一变，谁先收到就跟着变。

⇒ **主页那条一次判两件事**：账号面板开着就先关面板，否则不在收藏就回收藏。两件都在宿主这层判，
不压新的栈、也不进导航；tab 的正文状态按 tab 存在 saveable holder 里，切回来输入词之类都还在。

⇒ **弹层里不许放 handler**：Android 的 `Popup` 是独立窗口（`TYPE_APPLICATION_SUB_PANEL`），而返回
事件的 owner 是挂在 Activity 的 `decorView` 上的 ViewTree tag（`ComponentActivity` 的
`setViewTreeNavigationEventDispatcherOwner`）—— 弹层那棵树走不到，`LocalNavigationEventDispatcherOwner`
为 null，`NavigationBackHandler` 的 `checkNotNull` 当场抛。桌面端的弹层是同一份组合下的 scene layer，
反而取得到，所以这个错**只在 Android 炸**。判据一律留在宿主。

⇒ **栈空 / 已在收藏时不拦**：交给平台默认 —— Android 退到桌面（`OnBackPressedDispatcher` 的 fallback），
桌面无动作。

### 5.8 页面状态的 key（`ui/nav/AppRoute.key`）

`rememberSaveableStateHolder` 的 key 必须能进 Bundle：Android 的 `SaveableStateHolderImpl` 在
`SaveableStateProvider` 里先 `require(canBeSaved(key))`，对象 / 数据类当场抛

```
IllegalArgumentException: Type of the key Settings is not supported.
On Android you can only use types which can be stored inside the Bundle.
```

桌面没有这一层，所以又是**只有 Android 炸**。

⇒ 路由因此带 `key: String`；带参数的页面把参数拼进来（`"player/<id>"`），同一页面不同参数各有各的
状态，而不是共用一个槽。

⇒ 枚举当 key 能过（`Serializable`），但那是侥幸 —— 新 key 一律用 String。

### 5.9 账号会话与网页登录

登录就是「在站点自己的页面上登一次」，页面由**插件**用 `webview` 开：

```js
await webview(LOGIN, { show: true, onNavigate: (url) => (staysOnLogin(url) ? false : { url }) });
```

宿主不知道登录页长什么样，只调 `login()` 等一个账号回来。**离开登录页那一下就是收尾点**：
`onNavigate` 返回非布尔值，平台层取消这次导航、`webview()` 立刻兑现那个值 —— 目标页不必再加载。
登录成败交给异步的验人（`login()` 离开登录页后自己抓一次 `/settings` 看页头用户块）：cookie 判据在
`onNavigate` 里做不了，`Cookies.get` 是异步的，而导航回调必须当场返回（`chii_auth` 也可能是 HttpOnly），
所以登录页一离开就再抓一次设置页，认不出人就让登录失败、不建空号（判据见 bgm-plugin §四）。
用户自己关掉页面则兑现 `undefined`，登录按取消处理。

⇒ **验人抓 `/settings`，不抓主页**：两个页面共用同一份页头（`.idBadgerNeue a.avatar` 给出用户名与头像），
昵称在设置页是 `input[name="nickname"]` 的 value、在主页是 `#header` 里那句「Hi! 昵称」，前者是结构化
字段、后者要抠文本，而且实测设置页固定 22 KB，主页随收藏与动态涨到 130–627 KB。

⇒ **「页面加载完成」不是收尾点**：登录页不传 `script`，加载完正是等人输账号的时刻。`webview()`
只在五种情况下兑现 —— `script` 给出非空结果、`onNavigate` 给出结束值、命中拦截、加载失败、用户关闭。
无脚本的任务必须能自己收场（登录靠 `onNavigate`，主页靠用户关闭），否则后台任务满 30 秒被强制关闭。

⇒ **UA 由应用层统一注入**：`shared` 的 `soko.ekibun.acg.common.DEFAULT_USER_AGENT`
（`shared/src/commonMain/.../acg/common/Http.kt`）同时是 `webview` 页面的 UA 和出站请求的 UA，
插件不用写、也写不进两份不一致的串。出站请求走 `Http.requestWithDefaultUserAgent`（宿主
`Http.request` 保持不认识业务默认值），脚本 `fetch`、播放器、图片因此天然是同一个串；webview
页面在 `webviewAsync` 建上下文时设（首屏导航前）。站点按 UA
认会话，同一份 cookie 换个 UA 就只剩登录/注册（实测见 `docs/design/bgm-plugin.md` §四）。
插件仍可覆盖：`webview` 看 `options.userAgent` 或导航 header 里的 `User-Agent`，`fetch` 看
`headers` 里的 `User-Agent`。

⇒ **会话对宿主是不透明的串**：宿主只存与转交（`Account.session`），既不解析也不回放 —— 形状是
插件的事，不一定是 cookie（也可以是 localStorage 里的东西或一个 token）。Bangumi 目前的形态是
`{"cookies":{"https://bgm.tv":"chii_auth=…"}}`，由插件登完自己用 `Cookies.get` 收。

⇒ **应用会话只在「打开页面」这一下发生，并且由插件做**：jar 只有一份，登录过别的账号之后里面未
必还是这个人，所以插件开页面前把会话装进去。宿主一侧没有任何 cookie 代码 —— 它读不懂串，也就不
该动 jar。纯 `fetch` 的插件根本不需要 jar，从账号里取会话就能取数（`bgm.js` 的 `login()` 验人时直接塞
`Cookie` 头）。

⇒ **装会话 = 先清本站点、再逐条写**（`bgm.js` 的 `openHome`）：先按 `Cookies.get` 的结果把本站点
的 cookie 逐条写 `Max-Age=0`，再写会话里的每条，固定带 `Domain=.<站点域>`。域属性不能省：站点的
`Set-Cookie` 全带域属性，jar 里存的就是这条；不带域属性写下去的是另一条 host-only 记录，覆写不
到它（`CreateCookie` 按 name/domain/path 定位）—— 那就是「换了账号还是登录着」。先清一遍的残余
风险是 `Path` 不在这条 URL 视野里的记录（`Cookies.get` 看不见），比留着旧账号的 cookie 干净。
入口是账号面板里的「主页」条目 → `AccountRepository.openHome`（页面由插件开，面板等页面关掉再
收起）。

⇒ **同一个站点加第二个账号不用先退出**：`login()` 开头同样先清本站点的 cookie（见上），登录页不
会直接认出上一个用户；账号按 id 去重，同一个人重登还是同一条。

⇒ **登录失败不需要宿主补 jar**：宿主手上只有不透明串，装不回去；jar 里剩什么也不决定正确性 ——
取数走会话参数，网页要等下一次打开时按上面的办法重新装过。所以取消与失败都直接上抛。

⇒ **登出只删本地那条账号**（`AccountRepository.remove`）：这个人的 cookie 还留在 jar 里，直到下一次
`login`（先清）或 `openHome`（先清再写）把它处理掉。宿主不碰 jar 就没有「顺便退站」这回事。

⇒ **切账号只换内存里的当前账号**（`AccountRepository.select`）：不动 jar、不发请求，界面当场反映，
jar 要到下一次打开页面才知道该是谁。它和 `remove` 一样是 `suspend`（改内存、落盘、返回），由**主页**
在自己的 scope 里 `launch` —— 面板点完就收起，写挂不到它的 scope 上。

### 5.10 收藏的插件契约（`files/js/module/bgm.js` 的 `collections`）

一次调用交回一批档，形状 `{ "<键>": { data, page, pageTotal } }`；值是 `undefined` / `null` 的表示
「这次没读到」。键是插件给的显示文字（`动画|在看`），宿主不解析语义，只按 `|` 拆给界面。

⇒ **不带 `key` 是整份**：插件认识的全部档都列进键表，其中一次主页就能拿全的那几档带着 `data`
（`page: 1, pageTotal: 1`），其余只占位 —— 那是「还不知道」，不是「空」。宿主切到那一档再要。
主页上那三块哪块没解出来，给的就是 `data: []` 的整档：一次主页三块都在，缺的那块是这一类没有
在看在读，不是「没看到」。

⇒ **带 `key` 是一页**：`page` 缺省第一页；插件只给这一页，累积是宿主的事（`page <= 1` 覆盖整档、
`> 1` 接在已有数据后面）。

⇒ **同一次响应里顺带解出的别的档也一起给**：要的档在主页追番块里时，那一次请求已经把另外几档拿在手上，
没有理由丢掉；宿主按档合并，键相同就地更新。

⇒ **主页抓不到就让整次调用失败**：编一份全是 `undefined` 的键表会把「请求挂了」说成「没有收藏」，
界面只剩一个空壳。

⇒ **`pageTotal` 每页重算**：翻的中途有人加收藏、页数变了，最多让宿主多要或少要一页，下一次续读自然对齐。

⇒ **契约里只放有消费方的字段**。像「上次请求时间」「翻页游标」这类宿主不读的东西不进契约 —— 每多一个
字段，插件就多一处必须维护的形状。

### 5.11 下拉刷新只认手指（`ui/comp/PullRefresh.kt`）

材料的 `pullToRefresh` 把内容没消费掉的下拉位移都算成拉动，判据只有 `source == UserInput`；而这个版本里
`NestedScrollSource.Wheel` **就是 `UserInput` 的别名**，滚轮滚到边界剩下的那点也照收。可滚轮不产生 fling，
`onRelease` 永远不来：指示器被拉出来收不回去，也刷不出东西。

⇒ 判据按**输入方式**、不按平台：带触摸屏的 Windows 和接了鼠标的平板都在同一个人手里来回换，写死平台只会
在一半场景里错。输入源全应用一份（`ui/comp/InputSource.kt`）：根上 `trackActiveInputSource` 观察指针，
盒子里只读 `LocalActiveInputSource.current.latest`。

⇒ 拦法是在 `pullToRefresh` **内层**再挂一条嵌套滚动连接，非触摸时把下拉余量吃掉（`Offset(0f, available.y)`）。
post-scroll 由内向外，内层吃掉的量外层看不见，`distanceFraction` 一直是 0。挂在外面不行 —— 那里拿到的已经
是被扣过的量。

⇒ 不用 `enabled = false`：那个要读快照，而快照只能在按下以外的事件里提交（按下时写会重组，可能吞掉点击），
第一次触摸就得等下一次组合。这里读的 `latest` 是普通字段，按下那一刻就是本轮的类型。

⇒ 判断**只观察指针、不消费事件**：`pointerInput` 在 `Initial` 段读类型就够；只有下拉余量那一条走嵌套滚动。

⇒ 指示器不按输入方式隐藏：没人拉它就不会出来（`distanceFraction == 0`）；「刷新中」的圈是两条入口共用的 ——
下拉拉出来的是它，顶栏按钮点下去转的也是它。

⇒ 刷新入口两个、动作一个：手指下拉与顶栏右上角的按钮都直接调
`CollectionRepository.load(account, 当前档, 1)`（没有档时 `key = null` 重读整份，按钮不留空）。不再经过
`SharedFlow` 广播 —— 状态只有表一份，页面照着渲染，不需要「谁通知谁」。按钮不按输入方式隐藏：它本来就是
给没有下拉手势的鼠标与键盘用的，按「见过鼠标没有」决定画不画，会让刚启动、还没动过鼠标的桌面没有任何入口。

⇒ 按钮在**收藏页自己的顶栏**里：`Scaffold` 归各页（`ui/screen/home/tab/collection/CollectionScreen.kt` 自己带
`TopAppBar`，其余三页还没有顶栏），主页只负责切正文，不替谁给外壳、也不转发事件。

⇒ 刷新中再点是有意义的：后一次顶掉前一次（`jobs`），不会因为「已经在读」被静默丢掉。

⇒ `PullRefreshBox` 只包列表：空页与错误页自己滚不动，塞进去也拉不动，于是它们走顶栏刷新与「重试」按钮。
