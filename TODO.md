# TODO.md

本仓库**未完成的工作与已知缺口**。与 [AGENTS.md](./AGENTS.md) 的分工是死线：

| 文件                            | 放什么                       | 判据             |
| ----------------------------- | ------------------------- | -------------- |
| `AGENTS.md` / `cxx/AGENTS.md` | **长期有效的规则与陷阱**（"不读它就会改错"） | 与"做没做完"无关，永远成立 |
| `TODO.md`                     | **状态**——待修、待实测、待重新核定的东西   | 做完即删条目         |

> **不要往这里加规则。** 规则属于 AGENTS.md；加了会让本文件长成第二个法典，  
> 而法典正是 AGENTS.md 要瘦身掉的东西。
>
> **做完一条就删一条**（并顺手把 AGENTS.md 里指向它的说明改对）。条目 ID 保持稳定，提交信息里引用 ID。

---

## B. 已知缺陷，待修

### B11. FFmpeg 的 x86 asm：桌面端已开（2026-09-22），Android 与「差异分层」仍未做

- **已完成**：`cxx/ffmpeg/ffmpeg.cmake` 只在 `ANDROID` 分支传 `--disable-asm`，桌面端  
  （`--target-os=mingw32`）开启 ⇒ `HAVE_X86ASM=1`，`libavcodec/x86` 139 个 `.o`、`libswscale/x86`  
  16 个、`libavutil/x86` 15 个（开启前这三处**连目录都不存在**）。构建机需装 `nasm`  
  （MSYS2 的 `mingw-w64-x86_64-nasm`，实测 3.02 可用；yasm 上游已弃用，`configure` 只探测 nasm）。
- **实测收益**：串行解码 + YUV420P→RGBA 整段 1786 帧素材（1308x736）13.95 s → 12.09 s，**约 −13%**；  
  swscale 的 `No accelerated colorspace conversion found from yuv420p to rgba.` 由每上下文 1 条降为 **0 条**。
- **代价与判据改写（重要）**：同一素材逐帧 RGBA 与纯 C 路径比对 —— 帧数（1786）、时戳、尺寸、  
  **alpha 通道完全一致**；RGB 有 **1~3 LSB** 差（46% 字节；R 通道系统性 −1、G/B 双向）。  
  即**不是逐比特一致**，但属舍入 / dither 层面差异，不是数据损坏。原判据「像素哈希与修前一致」  
  **作废**，改为上面这一组。
- **未做 / 待查**：
  - **差异分层未分离**：属 H.264 解码层还是 swscale 转换层还没定。FFmpeg 没有控制 CPU flags 的  
    环境变量，要分离得加一个最小 native 入口调 `av_force_cpu_flags(0)`，在同一份 dll 里同轮对比  
    C 与 SIMD 两条路径。
  - **Android 侧搁置**：x86/x86_64 的手写汇编要 nasm 而 **NDK 不自带**；arm64 走 `.S`（gas）本不需要，  
    但 configure 的 `--disable-asm` 是全局的 ⇒ 现在整体关着。真要开得分架构处理。
- **证据**：探针正本 `.workbuddy/ref/asm-probe/ZzAsmPixelProbeTest.kt.saved`；两轮逐帧哈希  
  `.workbuddy/tmp/asmprobe-asm-{on,off}.txt`、原始像素 `asmprobe-pixels-asm-{on,off}.bin`；  
  asm-off 的 dll 备份 `.workbuddy/tmp/dll-asm-off-baseline/`。
- **顺带修掉的放大器**（早于本条，规则已进 [cxx/AGENTS.md](./cxx/AGENTS.md)）：`postFrameVideo` 的  
  `_srcVideoFormat` 漏回写使 sws 上下文逐帧重建 —— 同一探针下警告从 4996 条降到 5 条，  
  证据 `.workbuddy/ref/swscale-probe/`。

### B13. `--disable-network` 与 `--disable-protocols` 是**故意**关的，别开

- **现状（2026-10-04 核实）**：`cxx/ffmpeg/ffmpeg.cmake` 的 configure 参数带  
  `--disable-network`、`--disable-protocols`，产物 `config_components.h` 里  
  `CONFIG_HTTP_PROTOCOL` / `CONFIG_HTTPS_PROTOCOL` / `CONFIG_TCP_PROTOCOL` / `CONFIG_FILE_PROTOCOL`  
  / `CONFIG_TLS_PROTOCOL` **全为 0**（`CONFIG_SCHANNEL 1`、`CONFIG_NETWORK 0` 是无害残留，  
  network 一关那几支根本进不了编译）。
- **这不是缺陷，是设计**：ffmpeg 的 URL 访问被换成 `io_open` 回调 + `AvIO` 抽象  
  （`cxx/ffmpeg/ffmpeg.cpp` 的 `AVFMT_FLAG_CUSTOM_IO`），实际 I/O 全由 Kotlin 侧  
  `FileIO` / `HttpIO` 实现（ktor/OkHttp）。所&#x4EE5;**「ffmpeg 没有网络协议」是对的**。
- ⚠️ **别被日志误导**：真实源起播时 stderr 会打  
  `https or dtls protocol not found, recompile FFmpeg with openssl, gnutls or securetransport enabled.`  
  与 `Protocol name not provided, cannot determine if input is local or a network protocol…`。  
  这两条来自 `avformat_open_input` 对 CUSTOM_IO 的**协议探测**分支，**不影响功能** ——  
  同一次运行里 `RealUrlProbeTest` 的 `open / 读 / seek` 全部通过（见 B12）。想消掉就得  
  `av_dict_set(&opts, "protocol_whitelist", …, 0)` 或改传内联选项串，属**噪声治理**而非修 bug，  
  已记在 B12 的完成判据之外。

### B12. HTTP 源读空：契约已定并改掉 `HttpIO`，剩下真远程源与 FFPlayer 口径没验

- **已修**（2026-10-02）：`HttpIO.read` 原来在 ktor 缓冲还没追上 `offset` 时返回 0  
  （注释写着「让 avio 稍后重试」——不成立），现在改成**在 IO 层阻塞等数据**：  
  `getResponseBlocking()` 只在真读到流尾时返回 null（`read` 把它翻成 `AVERROR_EOF`），  
  推进改用新的 `advanceTo()` 逐段真读丢弃 —— 不再用 `InputStream.skip`（它允许少跳，  
  少跳一次 `offset` 的记账就与真实位置错开）。
- **链条与「为什么既不能返回 0 也不能返回负数」**（逐行核过本仓 `cxx/ffmpeg/ffmpeg/`：  
  `fill_buffer` → `avio_read` → `append_packet_chunked` → `mov_read_packet` →  
  `av_read_frame`）：正本写在 `HttpIO.getResponseBlocking()` 的 KDoc 里，**别再抄一份到这里**。  
  一句话：返回 0 ⇒ 「sample 游标前进、字节流没前进」外加把 0 长度的包交给上层；  
  返回负数 ⇒ `s->error` 被置上且**没有任何代码清它**（`avio_seek` 只清 `eof_reached`），  
  之后每次**零字节**读都以那个旧错误码收场，**真正的 EOF 也会冒充成它**。  
  ⚠️ **`AVERROR(EAGAIN)` + `AVIO_FLAG_NONBLOCK` 这条路  
  对我们不存在** —— 它们只长在 `avio.c` 的 `retry_transfer_wrapper` 里（只包 `URLProtocol`），  
  我们走的是 `avio_alloc_context` + 自有回调，不经过那一层；头文件也写着 `read_packet`  
  "must never return 0"（`avio.h` 的 `avio_alloc_context`）。
- **新用例**：`shared/src/jvmTest/.../player/HttpReadContractTest.kt` —— 起本地服务喂入库  
  素材（body 分块下发 + 块间 sleep），判据是**解出的视频帧序列与本地文件逐帧相同**；  
  认 Range（206）与不认 Range（一律 200）各一个。  
  **A/B 实测**：还原成旧实现后，「向前 seek」那个用例**根本不返回**（整轮构建被 SIGTERM）；  
  新实现两个用例 8 秒过。
- **作废读轮重构**（2026-10-03）：「本轮作废」收敛进 `AvFormat.resetChannel` 一个函数  
  （io.abort 投「作废」事件 → cancel 通道 → join 读作业），`takeOverPlayback` 只停  
  轮次不掀读作业，作废由换位置入口（play / seekTo / stepBack）在**调用方线程**上先行调  
  resetChannel —— 事件必须赶在读作业占住归属线程之前投出（排到它后面的调用永远轮不到执行）。  
  stepForward 不换位置、不掀读作业（预读包与在飞的读都有效）。`HttpIO.read` 的等待用 `select`  
  让「等数据」与「闲置超时」（`SOCKET_TIMEOUT_MS`）两支竞速（ktor 3.5.2 的 `awaitContent(min)`  
  参数是**最少字节数**不是超时，见 `project-traps/references/silent-failures.md`），  
  超时由正在等的读换会话重试。
- **真远程源已验（2026-10-04，`RealUrlProbeTest`）**：`soko.ekibun.acg.player.RealUrlProbeTest`  
  连 `https://media.w3.org/2010/05/sintel/trailer.mp4`（**真实 HTTPS，走了系统代理**）实测通过 ——  
  `initNative` 拿到 2 条流（854x480 视频 + 音频）、`duration=52208333`；读 38 包 0.5 s；  
  读 20 包后 `seekTo(20_000_000)` 再读 28 帧 2.0 s 全过。**结论：真实网络下的  
  open / 顺序读 / Range seek 三条路都通**，①里「真远程源没试」这一条可以划掉  
  （剩下的是「高延迟 / 断流重连」的极端形态，仍没专门构造）。
- **还剩**：① ~~只对本地 HTTP 服务验过~~**已补真远程源**（见上一条），  
  高延迟 / 断流重连的极端形态仍未专门构造；  
  ② 走 `FFPlayer` 真播一个 HTTP 源（本条原文的「播放」口径）**部分完成**：  
  `FFPlayerHttpStallSeekTest`（@Ignore）已把场景稳定构造出来（WAV + 分段停摆服务端），  
  但暴露了**先于本次改动就存在的编排竞态** —— play 挂在 takeOverPlayback 的 pause-join 上，  
  轮次被别人的 resetChannel / closeAsync 杀掉后 join 恢复，play 继续在已作废的轮次上跑  
  seekImpl（takeOver 的 `pts === before` 挡不住 close / 外部作废，因为 close 不换 pts）。  
  修法方向：轮次被外部作废时 takeOver 也要能判假（close 与外部作废都作废 pts，或等价闸）。  
  修完解开 @Ignore 补 wake 真路径断言。测试脚手架经验（stall server 设计）已写进该用例 KDoc。  
  ③ 「IO 层超前下载」的窗口仍没动 —— 前置条件（没数据怎么表达）已定，窗口本身没做。  
  ④ WAV/PCM 类流有个与播放器无关的坑先记着：`find_stream_info` 对「开着不关」的连接会  
  无限读下去（WAV 的包没有 duration，时长分析窗凑不齐，实测喂 480KB 也不停，上限是  
  probesize 5MB），`wav_read_header` 末尾还会回卷到数据起点重读一次 —— 停摆服务端必须  
  把这两次读全量供给，open 才能完成。
- **完成判据**：真连一个远程 HTTP 源播放，不再出现「读空 sample」。

### B19. Android 端 VideoSurface 生命周期收口（待真机实测）

- **代码已补**（2026-10-02）：`VideoSurface.android.kt` 此前没有 onDispose —— common 契约  
  写明的「界面销毁时以 null 回调 `onSurfaceContext`」在 Android 侧从未发生，  
  `AndroidSurfaceContext`（AudioTrack + Surface + SurfaceTexture）随页签切走而漏。现在用状态把 factory  
  回调里建的播放器接出来，onDispose 里回调 null + `close()`；`AndroidSurfaceContext.close()` 顺带  
  release 自己包出来的 Surface（TextureView 那侧 `onSurfaceTextureDestroyed` 恒 false、不代放）。
- **为什么还留着**：完成判据里的「真机实测」没做过 —— 本机没跑 Android 端。
- **完成判据**：真机（或模拟器）实测：进播放页 → 播放 → 切到别的页签，AudioTrack 已 release  
  （无持续占用的音频会话）。

### B20. 版本目录里 ktor 的版本别名名不副实

- **现状**（2026-10-02 评估发现）：`gradle/libs.versions.toml` 的版本别名  
  `ktorClientOkhttp = "3.5.2"` 同时喂给 `ktor-client-core` 与 `ktor-client-java`  
  （`version.ref = "ktorClientOkhttp"`）—— 别名以某个具体 artifact 命名，读到  
  `ktor-client-core` 挂着 `ktorClientOkhttp` 会误判升级口径。
- **完成判据**：别名改名 `ktor`（`[versions]` 一处 + `[libraries]` 三处引用同步），  
  提交前核对四条编译闸门。

### B23. JS 侧流式 response（缓做，随 E8 数据源契约落地）

- **现状**（2026-10-05 评估）：`JsEngine.fetchAsync` 走 `Http.request(options).execute()` 再  
  `bodyAsChannel().toByteArray()` 整包缓冲，body 的读取**没有任何超时** —— 服务端把响应头  
  发完就停住不吐 body 时，脚本的 `await fetch(...)` 永远挂着（协程与连接一起漏，不报错）。  
  流式执行已备好（`Http.request(...)` 备好 statement 后 `executeStreaming()`，目前仅播放器  
  `HttpIO` 在用），JS 侧未接。
- **方案**（2026-10-05 定稿，被否的备选一并记录）：
  - 贴 web 标准、**默认**流式化：`fetchAsync` 改走 `executeStreaming()`，响应头到达即返回；  
    不做 opt-in 标记（web 标准里没有 `{stream: true}`）、**不造** `getReader`。`body` 是  
    `{read, close}` 两个 `JSInvokable` 闭包（不是 id 注册表）：`read` 每次开  
    `CoroutineScope(Dispatchers.IO).async` 返回 `Deferred<ByteArray?>`（过桥自动变 Promise），  
    `n <= 0` 映射 null = 流尾；8s 闲置超时（`IdleTimeoutException`）自然 reject，重试留给  
    脚本（不搬播放器 `HttpIO` 的换会话机制）。单次 read 缓冲 64KB。
  - `arrayBuffer() / text() / json()` 循环 read 聚合到流尾、`finally` 里 close —— 现有脚本  
    这三种用法行为不变，回归面因此很小；`clone()` 对流式 body 是共享同一条流的浅拷贝  
    （非 tee）；read 需 `await` 串行（不设并发防）。
  - **被否**：`openStreams` 登记表 + `reset()` 收口 —— 生产路径没人调 reset、测试里  
    `server.stop(0)` 本身兜底，登记表买不到东西；改在契约注释里写死「不读到底就 close，  
    否则这条连接挂到进程结束」（无 GC 钩子，忘 close 是正常用法不是病态）。
- **为什么缓做**：近期无脚本需要增量读 —— 内置 JS 只有 init.js 与 crypto.js，现有套路全是  
  整包消费；播放链路不走 JS（`HttpIO` 直连 Kotlin 契约）；全员收益只有「治 fetch 卡死」  
  一项（低频故障）。E8 的「JS 数据源契约」是可预见的消费方，随它一起落地。
- **完成判据**：定点测试 `JsEngineDispatchTest` 加两条 —— ① 渐进读：服务端 3 片写、片间  
  sleep 250ms，断言「fetch 返回 → 读完」的时间差 ≥ 150ms（整包缓冲版 ≈0 会红）、聚合总数  
  == 发出总数、reads ≥ 2（64KB cap 保证，TCP 合片也不误判）、close 后 read 为 null；② 跨  
  chunk 多字节 UTF-8："a中" 的字节拆两片写，`text()` == "a中"；既有 `fetchBranchIsReachable`  
  （走 `json()`）不改断言保持绿。

### B24. AES-128 加密的 HLS 不可播（`crypto+` 前缀 + key/iv 在 io_open 的 options dict 里被丢弃）

- **现状**（2026-10-07 核实）：AES-128 加密的 HLS 源起不来——m3u8 本身能解析，分片打开必失败。  
  机制（出处都在本仓 submodule）：ffmpeg 的 hls demuxer 把 AES-128 分片的 URL 改写成  
  `crypto+<url>`（分片 URL 无 `://` 时 `crypto:`，`libavformat/hls.c:1443-1445`），key/iv  
  （各 16 字节，`ff_data_to_hex` 转 32 个 hex 字符）以 `key` / `iv` 两项放进传给 `io_open` 的  
  options dict（`hls.c:1449-1450`）；标准路径由 crypto 协议消费——剥前缀（`crypto.c:116-117`）、  
  hex 字符串按 `AV_OPT_TYPE_BINARY` 选项转回二进制（`crypto.c:66-67`）、拉密文本地 AES-128-CBC  
  解密。本项目 `ffmpeg.cpp` 的 `io_open` 拦截一切嵌套打开且**忽略 options 参数** ⇒  
  `crypto+https://…` 原样进 Kotlin `AvIO.open`，`HttpIO` 不认识该 scheme，按契约（打不开不许抛  
  异常）返回恒失败的 IO。
- **缺省 IV 不缺**：播放列表没写 IV 属性时 `hls.c:1035-1036` 已按 RFC 8216 用 media sequence  
  填满（8 字节 0 + 64 位大端 sequence）——dict 里的 iv 恒为 16 字节，接手方不用再推缺省。
- **key 文件今天就能拉**：key URL 经 `hls.c:1386`（read_key）走同一个 `io_open`，是普通 URL，  
  HttpIO 原样可用；缺的只是「把 key/iv 从 dict 接出来」与「解密」。
- **方向**：`ffmpeg.cpp` 的 io_open 里 `av_dict_get(*options, "key"/"iv")` 读出 hex 串（到  
  io_open 这一层 dict 里仍是 hex 字符串，二进制转换发生在 avio_open2 应用 dict 时）、识别并剥掉  
  `crypto+` / `crypto:` 前缀，把（真实 URL、key、iv）传给 Kotlin —— 扩 `Handler.open` 签名，  
  **`ffmpeg.cpp` 的 `GetMethodID` 签名字符串必须同步**（改漏即「静默要不到方法」，与  
  `AvIO.getBufferSize` KDoc 警告同款）；Kotlin 侧拉密文 + AES-128-CBC 解密。
- **SAMPLE-AES 不在此列**：不走 `crypto+` URL，是 hls 取包后自己用 `av_aes_ctx` 解密  
  （`hls.c:2657` 附近），本项目没有接口，暂不支持。
- **测试材料**：在线 AES-128 流与本地生成法见 skill `build-and-test` 的「在线测试流」一节。
- **完成判据**：AES-128 测试流可播（open / 读 / seek 不再在分片打开处失败）。


## C. 事实未实测，文档里暂无据

### C2. 桌面安装包命令与产物路径

- **现状**：**待补**，还没真实打过一次。任务确实存在  
  （`:desktopApp:tasks --all` 里有 `package` / `packageMsi` / `packageDeb` / `packageDmg` /  
  `packageDistributionForCurrentOS` / `packageRelease*` / `createDistributable` /  
  `runDistributable` / `packageUberJarForCurrentOS` / `notarizeDmg`）。
- **完成判据**：至少跑通 Windows 侧的打包，把命令与产物位置写进 skill `build-and-test`。

### C3. 热重载到底能不能用

- **现状**：`hotRun` / `hotRunAsync` / `hotRunArgfile` / `runHot(Deprecated)` 任务**存在**，  
  但 `--auto` 参数**不存在**（此前记录有误，已纠正）。热重载是否真可用**未验证**。
- **完成判据**：验证一次；可用则写进 skill `build-and-test`，不可用则不问（保持现状不提）。

## D. 文档债

### D1. 禁区清单逐条重新核定

- **在哪**：`.agents/skills/project-traps/references/forbidden-zones.md`。
- **现状**：条目来自历史踩坑记录，依赖当时实现细节，可能已随代码演进失效（文件头已标）。
- **完成判据**：每条对照当前代码 + 实跑核定。**失效的删、与 `AGENTS.md` / `cxx/AGENTS.md` 重复的删**  
  （现在重复度较高），核完把文件头的「待重新核定」去掉。

### D2. 调试手册逐条重新核定

- **在哪**：`.agents/skills/debugging/references/debugging.md`。
- **现状**：同上，排查步骤的命令 / 路径可能已变。
- **完成判据**：同上，核完去掉文件头的「待重新核定」。

### D3. 术语表

- **现状**：`AGENTS.md` 曾有一个「术语（待补）」空节，属未兑现的承诺，已随瘦身删除。
- **完成判据**：整理出项目专有名词表，或明确决定不维护。**若整理，不要放回 `AGENTS.md`**  
  —— 它是"要查的时候才看"的资料，放 `.agents/` 下；`AGENTS.md` 只留一行指针。

### D4. 注释的长度与符号（规则已入 `AGENTS.md`，剩下分期改）

- **已落地**（2026-10-04）：三条写法规则进了根 `AGENTS.md` §1 —— ①注释讲现状不讲历程、  
  ②用 import 不写全限定名、③源码正文不用 emoji 符号（附 animeko 的实测依据与例外边界）。  
  根 `build.gradle.kts` 那处全限定名（规则②的现行反例）已改；本次改动涉及的 10 个文件里  
  30 处 ⚠️ 已删（符号去、句子留）。
- **长度：判据改成「按语义搬，不按数字卡」**（同日复核后调整）。原先落的是  
  **注释/代码 ≤ 35%、单块 ≤ 25 行**两个数字，还写了个量测脚本挂进 `.githooks/pre-commit`；  
  **2026-10-04 复核后全部撤掉**（脚本已删，hook / `AGENTS.md` / `SKILL.md` / `comments.md`  
  的引用一并清干净）。**撤的理由**：「注释行 / 代码行」的分母是代码行 ⇒ 文件越短密度越容易冲高，  
  那是算术必然。实测 animeko 485 个主源码 `.kt`：**代码行 < 30 的文件里 92% 超 35%**，  
  中位密度 90%——拿 35% 当普适阈值是把「只在长文件上成立的观察」当成了规则。  
  （顺带修正两个错数：animeko 全量是 **14.5%** 不是 23.3%；代码行 ≥ 60 的文件上中位 10.5%、  
  p90 35.7%，35% 只是那里的上界。）  
  ⇒ 现行判据写在 `comments.md` 第六节：**带实测数据或「为什么长成这样」的段落搬进  
  `.agents/` 下对应 `references/`，正文留一句裸约束 + 指向；约束本身一个字都不能少。**
- **已压掉的**（搬出去的内容都有落点，信息一条没丢）：
  - `Http.kt` 的 `availableForRead` 36 行实测数据 → `http-streaming.md` 第六节；
  - `AvFormat.resetChannel` **57 行 → 17 行**：归属线程的论证 `http-streaming.md` 第二节  
    已有，`packetChannel` / `readerJob` **不加 `@Volatile`** 那 22 行（含访问点表与  
    「拆掉 `FFPlayer.pause` 的 join 就变真竞态」）搬进 `silent-failures.md`；
  - `AvIO.getBufferSize` 的 ffmpeg 常量来由（`aviobuf.c:36` / `:43` + 官方注释）→  
    `cxx/AGENTS.md` 的 ffmpeg 一节；
  - `AvPlayback.isReadTimeOut` **29 行 → 14 行**：耗时特性（pause 的 join 上界 = 一个轮次、  
    实测 7～108 ms）搬进 `debugging.md`；
  - `HttpIO.skipThreshold` **16 行 → 9 行**：判据论证 `http-streaming.md` 第五节已有全文；
  - `FileIO` 类 KDoc **14 行 → 10 行**：wav / pcm 在文件尾 **100% CPU 空转**的实测与  
    `aviobuf.c:551-558` 的判据搬进 `silent-failures.md`；
  - `Http.requestStreaming` 内 5 组行内注释（done/ready、session scope、catch、await、finally）  
    各压到 2～3 行，死锁推导只留 `http-streaming.md` 第四节的指针；
  - `FFPlayer` 类 KDoc / `takeOverPlayback` / `resume` 各压一轮 —— 这份文件的注释**基本都是  
    真约束**（是「该留」的好样板），再压就要删判据了。
- **复述是独立于长度的另一维**（2026-10-05 补，§四那条重话原先只在 `comments.md` 正文里，  
  `AGENTS.md` §1 竟没有）。压长度时**漏了 4 处**：`AvSurfaceContext` 的「写播放倍速。」配  
  `fun setSpeedRatio`、「读播放倍速。」的前半句；`VideoSurface` 的「平台相关的视频输出区域。」  
  配 `expect fun VideoSurface`；`SurfaceContext` 类 KDoc 后半句「音频/视频输出由各平台子类实现」  
  （`abstract class` + 同目录两个 `Playback.*.kt` 已经说明）。⇒ 判据改成**两步**：  
  **先剔复述**（删掉这行注释，理解有没有变化？没变化就删），**再问约束还是机制**。  
  写进 `comments.md` 第六节与 `AGENTS.md` §1。
- **长度基线：三层，建议性**（2026-10-05 实测 animeko 2913 个 kt/kts 后补进 `comments.md` 第六节）。  
  中位 / p90：类 KDoc 3/9、函数 KDoc 3/7、属性 KDoc 3/5、**函数体内 `//` 1/2**  
  （animeko 那 4779 组行内注释里 83% 是单行、93% ≤ 2 行）。建议上限取 p90 向上取整：  
  **类 8 / 函数 6 / 属性 5 / 函数体内 2**。⚠️ 与「别拿注释行 ÷ 代码行当判据」不冲突 ——  
  那条反对的是比例（分母随文件大小漂移、算术必然），这里量的是**单个注释块的行数**（与文件大小无关）。  
  超了按两步走：先剔复述，再把机制搬进 `references/`；剔完还超，问一句「这段是不是**数据**  
  （规则表、样例、状态机）」—— 数据长是应该的。
- **长度已收的**（2026-10-05）：
  - `Http.kt` **全篇 9 处**：类 KDoc 去掉与 `delegate` 重复的参数段、`contentLength` 7→4、  
    `offset` 8→6、`private` 的 `channelOrNull` **8→1**、`applyOptions` 去掉签名复述、  
    删掉「移除自动带上的 Cookie」这类复述，`requestStreaming` 体内三处各压 1 行；
  - **函数体内 `//` ≥ 5 行的 11 处全部收掉**（最长 `QuickJS.kt` 12→2、`HttpIO.kt` 9→4、  
    `NativeWebViewHostTest.kt` 8→3、`JsEngine.kt` 7→3 与 5→3、`FileIOTest` 5→3、  
    `AvFormat` 5→3 等），**现在 ≥ 5 行为 0**；行内注释 ≤ 2 行的占比 80% → 81%（animeko 93%）。
- **剩下不动的**（密度高但内容该留）：
  - `player/Playback.kt`：另 4 行是短契约句与 `updateAspectRatio` 那个「避免每帧写快照」的  
    判据；
  - `ui/comp/VideoSurface.kt`（原 `ui/screen/`，随组件迁移）：三条回调的时机与 null 语义是  
    **对外契约**，内容留；形式已由 `@param` 改成正文 + `[onPlayback]` 链接（`comments.md` §三 禁标签）。
- **剩下的存量**（按「动到哪个改哪个」分期）。**扫描口径**：按注释块分组数行数，KDoc 比上表上限、  
  行内注释 ≥ 3 行即算越界（复算脚本是会话里的临时产物，不在仓库里）。  
  2026-10-05 复算：
  - **函数体内 `//`**：3 行 **50 处**、4 行 **10 处**（另有 1 处 6 行是 `HttpIO.kt` 的 ASCII 示意图，  
    属「数据」不动）。3-4 行多是「一个块里三条独立约束」，硬压到 2 行会丢约束 ⇒ 逐处判断。
  - **KDoc 超建议上限 1-2 倍 106 处、超 2 倍以上 44 处**：前几名是 `jni.kt` 的 `Pointer` 基类 KDoc  
    **147 行**（全仓最长，两张表 + 三个 `##` 小节）、`FFPlayerStallMidPlaybackTest` 类 KDoc **97 行**、  
    `SeekWindowSemanticsTest` **40 行**、`JSRef` **33 行**。`jni.kt` 那个压法特殊：它讲的是  
    「哪一步必须同步」，搬走就丢「在哪个函数上」⇒ **留在原地压短**。
  - **符号**：**27 处 / 5 个文件** —— `jni.kt` 19、`QuickJS.kt` 4、`AvFrame.kt` 2、  
    `AvStream.kt` 1、`jvmTest/.../PointerTest.kt` 1。删符号、句子照留；`jni.kt` / `QuickJS.kt`  
    与 skill `quickjs-ownership` 的 `ability-bridge.md` 有交叉，删之前先确认那句约束在文档侧有落点。
- **完成判据**：`shared/src/**/*.kt` 与 `*.kts` 的注释里 `⚠` 命中数为 0，  
  **复述已剔净**（逐条问「删掉后理解有无变化」，无变化就删），  
  且 `jni.kt` 那 147 行块压到只剩裸约束句（`→` 不计入，它是 `comments.md` §五点名允许的  
  ASCII 示意图）。

### D5. 子系统代码地图（学 animeko `docs/contributing/code/`）

- **现状**（2026-10-02 评估）：`.agents/skills/` 覆盖了构建 / 风格 / 陷阱 / 调试，但缺  
  animeko 那种「改子系统前先读它的文档」的入口——FFPlayer 帧队列、web 包两端的窗口挂接链  
  这类知识散在文件头注释里，质量高但没有代码地图，也没有稳定的阅读入口。
- **完成判据**：为 player、web 两个最常动的子系统各出一页代码地图（术语 + 类职责 +  
  关键链路），放对应 skill 的 `references/` 下，`AGENTS.md` §7 索引表加指针。

## E. 结构性改进（需要先决策）

### E4. （可选）把 MSYS2 的坑自愈进 `cxx/build.jni.sh`

- **现状**：`MSYS2_BIN` 仍需存在，否则 `exec.cmd` 直接退出 1。
- **完成判据**：让 `:desktopApp:buildJni` 在未设 `MSYS2_BIN` 时给出可读报错或自动探测。

### E5. `viewmodel-compose` 依赖引了但全仓零使用

- **现状**：`shared/build.gradle.kts` 引入了 `viewmodel-compose`，但代码里没有 `ViewModel` / `StateFlow`  
  / `collectAsState`。它会误导 agent 以为"项目选了 MVVM"。2026-10-02 复核仍零使用，且同处引入的  
  `lifecycle-runtimeCompose` 同样零使用（全仓无 `collectAsState*` / `Lifecycle*` 调用），删依赖时一并评估。
- **完成判据**：确认确实不需要就删掉依赖；需要就先用起来再留。

### E8. 领域架构落地（数据结构 + WebView 前后台）

- **现状**：设计定稿于 [docs/architecture.md](./docs/architecture.md)  
  （2026-10-02，调研纪要见 [docs/research-2026-10-02.md](./docs/research-2026-10-02.md)），未落地。
- **落地顺序**（architecture.md §5）：`epochTimeMs()` 墙钟原语 + kotlinx-serialization 依赖  
  （**改依赖需用户确认**）→ `acg.model` 领域类型 → `acg.catalog.bgm`（DTO + mapper + 限流退避）  
  → 持久化分片 + 收藏/历史仓库 → `HtmlParser` 宿主原语 + JS 数据源契约（含 B23 流式 response）+ Line 绑定 →  
  业务四页 → WebView 投前台（悬浮按钮方案，先 Android 验证、桌面动 C++ 摘 `background` 标志）  
  → P2：AniList mapper / per-episode 精确进度 / animeko 订阅兼容解释器 / 边播边缓存 / torrent。
- **完成判据**：按 §5 分条验收，每条落地后回来更新本条状态；全部落地后删条目  
  （设计文档随实现修订，不删）。

---

*建立于 2026-09-16。条目来源：`AGENTS.md` / `cxx/AGENTS.md` / `.agents/skills/*/references/` 里的  
「待修/待办/待补」标记、一次针对代码风格的取证调查、一次针对缩进与注释语言的实测扫描，  
以及 `.workbuddy/memory/2026-09-16.md` 的记录。*
