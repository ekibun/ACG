# HTTP 流的读取与会话生命周期

`HttpIO`（`acg/player/HttpIO.kt`）与 `Http.Response`（`acg/common/Http.kt`）的实现细节。
**源码里只留契约与指针**，推演过程在这里 —— 那些是排查时才会用到的知识，挂在代码上
只会把真正要紧的约束淹没掉。

本文件的三条主线索，与 `.agents/skills/project-traps/references/silent-failures.md`
是配套的：那一条讲「为什么会静默失效」，本文讲「这套机制为什么长成这样」。

## 一、为什么必须**在 IO 层阻塞等**

`AvIO.read` 的回调来自 native 的 `av_read_frame`，它要求「数据没到就一直等」。但
`read` 的返回值在 aviobuf 里**只有两种合法解**（`HttpReadContractTest` 钉着这条）：

| `read` 返回 | `fill_buffer` 做了什么 | 后果 |
| --- | --- | --- |
| `0` | 走 `else` 分支：`buf_end = dst + 0`，缓冲区仍空、**不置** `eof_reached` | `avio_read` 返回 0 ⇒ `append_packet_chunked` 交出 0 长度的包（`av_shrink_packet(…,0)` 再 `av_packet_unref`，连 `stream_index` 都归 0）；而 `mov_read_packet` 的重试分支只在 `ret < 0` 时走 ⇒ sample 游标已前进、字节流没前进，**这个 sample 同一次 pass 内再也读不到** |
| 负数 | 对**任何** `< 0` 置 `eof_reached = 1; error = len` | `avio_read` 只在「这次一个字节都没读到」时才 `return s->error`（`aviobuf.c:658-659`）⇒ 之后每次**零字节**读都以那个错误码收场，直到 `s->error` 被清 —— 而**没有任何代码清它**（`avio_seek` 只清 `eof_reached`，`:299/:317`）。最阴的一条：**之后真正的 EOF 会冒充成那个旧错误码** |

⇒ 没有「稍后重试」这个返回值，只能**等**。

### 附：为什么闲置超时不装进 `HttpTimeout`

读闲置这件事**没有**交给两端的 `HttpTimeout`，而是自己用 `Response.read` 里那支
`select onTimeout` 计时（`SOCKET_TIMEOUT_MS = 8_000L`）。原因是那个配置项**在两端都是
装饰**：

- 桌面那条 Java engine（`java.net.http.HttpClient`）**没有**「读闲置超时」这个概念 ——
  装上去被**静默忽略**，不报错、也不生效；
- OkHttp **自带 10 s 默认值**，与 ktor 的取值不一定同源，两平台对不上。

⇒ 两边都不装、统一由那支超时信号自己计时。**判据是「两次数据包之间有没有出字节」**，
不是「请求总共花了多久」—— 整条流可以播几小时。

**顺带记一笔**：别想用 `AVERROR(EAGAIN)` + `AVIO_FLAG_NONBLOCK` 绕过去 —— 两者确实存在，
但只长在 `avio.c` 的 `retry_transfer_wrapper` 里，而那一层**只包 `URLProtocol`**
（`h->prot->url_read`）。本项目走 `avio_alloc_context` + 自有回调，不经过它。上游头文件
也把话说明白了（`avio.h` 的 `avio_alloc_context`）：`read_packet` "For stream protocols,
must never return 0 but rather a proper AVERROR code."

## 二、为什么每次 `read` 有一条**私有**的作废信号

「这一轮不要了」（seek / close，见 `AvFormat.resetChannel`）发生在**另一条线程**
（avformat 线程）上，而那次读卡在 `av_read_frame` 里 —— **协程取消够不到它**
（取消是协作式的，阻塞的 JNI 调用里没有挂起点）。所以必须有一条**能被外部叫醒**的路。

形态选择：**一次读一条私有信号**，`Handler` 只持一个投递口（`onSignal` 登记的 `raise`）：

- 有读在等时 `abort()` 投到**那条读**的信号上 ⇒ 建立期 / 等数据 / 白读推进**全被覆盖**
  （它在那一次读内部新建，覆盖那次读的全部等待）；
- 没读在等时（两条分片之间、seek 的间隙）投递口是空的 ⇒ **no-op**；
- 下一次读挂的是**新**信号 ⇒ 收不到上次的作废。

「没人在等时投 = no-op」「下一次读收不到上次的作废」这两条语义，由「信号挂在读上、
生命周期即那次读」**天然给出** —— 不需要配套的清旗标动作。旗标那种**跨轮共享的状态**
必须有人显式清，而「清早了 / 忘了清」都是难查的时序病。

**为什么是 `CompletableDeferred`**，而不是另两种写法：

- **不是 `StateFlow<Boolean>`** —— 那种状态**长期存在**、投过就一直为 `true`，必须有人显式清，
  而「清早了 / 忘了清」都是难查的时序病；
- **不是 `Channel`** —— 这里只需要「投一次、之后一直可见」，`Channel` 反而引入了
  「投信号」与「挂订阅」之间的**漏唤醒窗口**（投的时候还没人挂上，就丢了）。

**单投递口够用的前提**：同一时刻只有一次 `read` 在等 —— HLS 换分片是「上片读到尾并关掉、
才开下片」，native 单线程串行调 `read`，所以旧那条的 `read` 早已返回、投递口已被它摘掉，
不会与新那条并存。**不必攒列表。**

### 叫醒的方式是 `cancel`，不是 `throw`

`read` 的收场（现状写法）：

```kotlin
val signal = AbortSignal()
val job = async { readSuspend(buf) }
val abort = async { signal.await(); job.cancel() }   // ✅ 只 cancel，不作声
handler.onSignal { signal.raise() }
try { job.await() }
catch (e: CancellationException) { AvFormat.AVERROR_EXIT }
finally { abort.cancel(); handler.onSignal(null) }
```

⚠️ **哨兵绝不能 `throw`**。它挂在 `runBlocking` 的 scope 上，是**子协程**；`async` 里抛出的
异常不是「留给 `await` 取的值」，而是**未捕获异常**，会 `parentCancelled` **向上取消父
`BlockingCoroutine`** ⇒ 整个 scope（含 `readSuspend`）全被拆掉，外层那个
`catch (Http.AbortedException)` **一次都没跑**，`read` 直接以 `Int.MIN_VALUE` 收场。
实测形状：`kotlinx.coroutines.JobCancellationException: Parent job is Cancelling;
job="coroutine#26":BlockingCoroutine{Cancelling}`。

⇒ **通用规矩：`async` 只用来挂等待，不用来报错。** 对照：`Http.Response.read` 里也有
`async { data }`，它安全是因为那里用 `select` 挑一支、`finally` 把落选支 `cancel()` 掉
—— 异常从来没被「抛出去」过。

**`finally` 里两句都不能省**：`abort.cancel()`（哨兵正常收场时永远挂在 `signal.await()`
上，不取消它 `runBlocking` 会一直等）、`handler.onSignal(null)`（摘投递口 ——
正常返回 / 作废收场 / 真故障三条路都要摘，否则下一条会话会被上一条的死读误伤）。

## 三、为什么 `abort` **只叫醒**、不掀会话

会话的去留只看「**这条会话还出不出字节**」—— 由正在等的那个读自己判断（`HttpIO.read`
内那支 `select onTimeout`，见 `Http.SOCKET_TIMEOUT_MS`）。这样健康会话跨 abort 复用
（seek 每次作废，不白重开连接），而「掀会话」永远由同一条线程自己做，
**没有跨线程拆对方正在用的东西的竞态**。

⇒ `AbortSignal` 形态的另一半：**abort 不掀会话**，但下一次读面对的还是那条停住的会话，
它会闲置超时到点、自己换一条重试（`HttpIO.readSuspend` 的 `catch (e: Http.IdleTimeoutException)`）。
所以「下一次读能重新发请求并读到数据」压的正是静默自愈那条路
（`HttpAbortTest` 的第二个用例）。

**`close()` 与 `abort()` 是两件事**：`close` 是「这条 IO 到此为止」，无条件释放
（掀通道 + 放 `execute` 的 block 回去，ktor 随即 cleanup）；`abort` 是「这一轮不要了」，
**不可逆的只有 close**。合成一个会把「作废」错当成「关会话」。

## 四、会话 scope 为什么不能挂在调用方 job 上

`Http.requestStreaming` 里那条会话开在 `CoroutineScope(Dispatchers.IO)` 上，
**不挂调用方的 job**：

- 调用方（avformat 线程）是在 `runBlocking` 里等到 `[Response]` 再消费的，而
  `runBlocking` 会等**所有子协程**结束才返回（`BlockingCoroutine.isScopedCoroutine`）
  ⇒ 会话等 `Response.close`、`Response.close` 等 `runBlocking` 返回，**直接死锁**；
- `CoroutineScope(Dispatchers.IO)` 工厂在上下文没有 `Job` 时自己补一个 `Job()`，
  那个 `Job()` 同样**没有父**，`runBlocking` 的结构化等待够不着这棵树；
- **也别挂在本函数自己的 scope 上** —— 本函数是 `suspend`、直接跑在调用方协程里，
  `launch` 上去就是同一个死锁；
- dispatcher 定死 IO 池：建连 / TLS 握手是**真阻塞的 IO**，丢给 `Default`（核数那么大的池）
  会在多路并发时占满。

## 五、白读推进（`getResponseBlocking` 里的 `advance`）

seek 之后网络游标没到位时，要在这条响应上**白读白丢**地往前推。三条约束：

1. **先登记 `cachedRsp`、再推进** —— 下面 `reused` 的判定靠的就是「推进之前
   `cachedRsp === rsp`」，`close` / `seek` 也随时可能从外面看这个字段。若「推完再登记」，
   推进整段期间 `cachedRsp` 都是旧值 ⇒ `reused` 判错，且「这条会话已归本 IO」在半途不成立。
2. **新建立的那条必须自己收干净** —— 抛出时它已经登记进 `cachedRsp`，但连 offset 都
   没走到、不是「可复用的缓存会话」（复用那条的存亡归 `close` / 静默自愈管，顺手关掉
   会伤到还没用完的会话）。建立它的那次 `requestStreaming` 调用早已返回 ⇒ `close` 与
   建立期的 job 两边都抓不住它，不收就是一条永久挂在 `done.await()` 上的 ktor 会话 +
   漏掉的连接（实测：服务端 `entered=3` / `exited=2`）。
3. **每一段都走 `rsp.read`**（内部先等数据到、再读），不要直接用 `readAvailable` ——
   后者在通道空时是裸的 `awaitContent()`，既没有超时、也不在本协程自己的取消点上。

**换会话的判据是「要跳多远」**，不是「已缓冲多少字节」（后者量的是另一件事，且通道没有
公开这个数，`InputStream.available()` 那套问不到）。见 `skipThreshold`。

**附：`Response.offset` 的起点为什么从 header 推**

起点**必须**是服务端在 header 里**亲口说的**那个数（206 的 `Content-Range` 起点；没有这个
头 —— 也就是 200 从头给 —— 就是 0），**不能**由调用方灌进去。灌进去的话，`HttpIO` 建完响应
还得追一次赋值，而漏一处就**静默重开一条会话**、从头重发。

解析刻意走**字符串**（`substringBefore('-')` / `removePrefix("bytes")`）而不是 ktor 的
`parseRangesSpecifier`：后者解析的是**请求侧**的 `Range: bytes=0-`，形态不同。

⇒ 这条也解释了 `getRange` 里那条注释「本函数只管请求，不管记账」的分工。
