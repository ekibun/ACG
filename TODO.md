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

### B13. `--disable-network` 与 `--disable-protocols` 是**故意**关的，别开

- **现状（2026-10-04 核实）**：`cxx/ffmpeg/ffmpeg.cmake` 的 configure 参数带  
  `--disable-network`、`--disable-protocols`，产物 `config_components.h` 里  
  `CONFIG_HTTP_PROTOCOL` / `CONFIG_HTTPS_PROTOCOL` / `CONFIG_TCP_PROTOCOL` / `CONFIG_FILE_PROTOCOL`  
  / `CONFIG_TLS_PROTOCOL` **全为 0**（`CONFIG_SCHANNEL 1`、`CONFIG_NETWORK 0` 是无害残留，  
  network 一关那几支根本进不了编译）。
- **这不是缺陷，是设计**：ffmpeg 的 URL 访问被换成 `io_open` 回调 + `AvIO` 抽象  
  （`cxx/ffmpeg/ffmpeg.cpp` 的 `AVFMT_FLAG_CUSTOM_IO`），实际 I/O 全由 Kotlin 侧  
  `FileIO` / `HttpIO` 实现（`HttpIO` 的传输层是 OkHttp，在 `:platform` 的 `jvmShared`）。 所以**「ffmpeg 没有网络协议」是对的**。
- ⚠️ **别被日志误导**：真实源起播时 stderr 会打  
  `https or dtls protocol not found, recompile FFmpeg with openssl, gnutls or securetransport enabled.`  
  与 `Protocol name not provided, cannot determine if input is local or a network protocol…`。  
  两条都出自 `avio_find_protocol_name(s->url)`（`avio.c` 的协议表比对 + `https:` 前缀判断）：  
  协议表为空 ⇒ 一律认不出，与功能无关，**`protocol_whitelist` 也消不掉**（CUSTOM_IO 下顶层  
  `pb` 由我们的 `io_open` 建，白名单不参与）。入口唯一：`mov_read_header` 末尾的  
  `ff_configure_buffers_for_index` ⇒ **只有 MP4/MOV 打这两条**，HLS/ts 不进这条链。  
  另有一层副作用：`proto == NULL` 时它把 avio 缓冲按索引放大到 `pos_delta`（`seek.c`），  
  本地与网络源一视同仁（`file` 协议同样没编进去）。属**噪声治理**而非修 bug ——  
  同一次运行里 `RealUrlProbeTest` 的 `open / 读 / seek` 全部通过（见 B12）。

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
- **完成判据**：定点测试 `JsEngineTest` 加两条 —— ① 渐进读：服务端 3 片写、片间  
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
  **`ffmpeg.cpp` 的 `GetMethodID` 签名字符串必须同步**（改漏即「静默要不到方法」，与同一函数里  
  `open` / `read` 的签名字符串同款）；Kotlin 侧拉密文 + AES-128-CBC 解密。
- **SAMPLE-AES 不在此列**：不走 `crypto+` URL，是 hls 取包后自己用 `av_aes_ctx` 解密  
  （`hls.c:2657` 附近），本项目没有接口，暂不支持。
- **测试材料**：在线 AES-128 流与本地生成法见 skill `build-and-test` 的「在线测试流」一节。
- **完成判据**：AES-128 测试流可播（open / 读 / seek 不再在分片打开处失败）。

### B26. 视频上传：两端都零拷贝（模拟器回落逐帧拷贝）

- **现状（2026-10-29）**：`flushAudioBuffer`/`flushVideoBuffer` 已合并为统一的
  `flushFrameImpl(codecType, frame, getBuffer)`；两端的视频路径都不再逐帧走 JVM 数组：
  - **桌面走零拷贝**：`cxx/ffmpeg/video_output.windows.cpp` 在 Skiko 的 adapter 上建 D3D11 设备，
    `ID3D11VideoProcessor` 把硬解纹理转成 RGBA 写进 NT 共享纹理环，Skia 的 D3D12 设备
    `OpenSharedHandle` 打开同一块显存，`BackendRenderTarget` → blit → 快照 → `drawImageRect`。
    像素全程不落 CPU，也没有每帧的 `Image.makeRaster` 分配。判据与四个坑见
    `docs/development/hardware-decoding.md` §五，用例 `DesktopSurfaceContextTest`。
  - **回退仍在**：Skiko 不是 Direct3D 后端、设备不可共享、或没有硬解时，
    `DesktopSurfaceContext.isHardwareVideo = false`，退回原来的 `makeRaster` 逐帧拷贝
    （native `videoBuffer` → 复用 JVM `buf` → Skia Image → GPU 上传）。
  - **Android 也是零拷贝**：`av_mediacodec_default_init` 把 Surface 交给 MediaCodec，帧是
    `AV_PIX_FMT_MEDIACODEC`，上屏只是 `av_mediacodec_release_buffer(buffer, 1)`，sws 与 memcpy
    都没有了。拿不到 codec / surface 时退回 `copyBufferToSurface` 的逐帧拷贝。
    真机（华为 ANG-AN00 / Android 12）验证过。模拟器上直出同样生效，但它的 codec 垫片在暂停态
    单步不换帧（开宿主 GPU 也一样，同机 mediamp/ExoPlayer 也复现）⇒ 这类交互上真机验，
    不为模拟器加设备类型分支。判据与对照实验见 `docs/development/hardware-decoding.md` §2.5。
- **剩下**：macOS / Linux 桌面仍是逐帧拷贝（mediamp 在那边走 Metal / GLX，本仓库没做）；
  Android 的 10bit/HDR 仍拿不到 P010。

### B27. `DEFAULT_USER_AGENT` 写死 Chrome 131，没有跟内核版本走的机制

- **现状（2026-11-11）**：`shared/.../acg/common/Http.kt` 的 `DEFAULT_USER_AGENT` 是一个常量串，
  `fetch` 与 `webviewAsync` 默认都带它（站点按 UA 认会话，两边必须同一个串，见
  `docs/development/shared-layer.md` §5.9）。
- **风险**：它和真实渲染内核（Windows 的 WebView2、Android 的 System WebView）会越漂越远。
  差几代通常无害，但站点按 UA 分支下发页面（降级页 / 风控）的情况没有实测覆盖。
- **可做**：启动时从宿主取真实 UA（桌面 `CoreWebView2Environment` 的浏览器 UA、Android
  `WebSettings.getDefaultUserAgent()`），拿不到再退回这个常量。真正必须一致的是「同一端内
  webview 与 fetch」，跨端本来就可以不同，所以换成宿主 UA 的前提成立；改完要重跑登录与取数。

## C. 事实未实测，文档里暂无据

### C2. 桌面安装包命令与产物路径

- **现状**：**待补**，还没真实打过一次。`targetFormats` 只留 `Msi`（`Dmg` / `Deb` 已从
  `desktopApp/build.gradle.kts` 移除，桌面端只按 Windows），任务确实存在  
  （`:desktopApp:tasks --all` 里有 `package` / `packageMsi` /  
  `packageDistributionForCurrentOS` / `packageRelease*` / `createDistributable` /  
  `runDistributable` / `packageUberJarForCurrentOS`）。
- **完成判据**：至少跑通 Windows 侧的打包，把命令与产物位置写进 skill `build-and-test`。

### C3. 热重载到底能不能用

- **现状**：`hotRun` / `hotRunAsync` / `hotRunArgfile` / `runHot(Deprecated)` 任务**存在**，  
  但 `--auto` 参数**不存在**（此前记录有误，已纠正）。热重载是否真可用**未验证**。
- **完成判据**：验证一次；可用则写进 skill `build-and-test`，不可用则不问（保持现状不提）。

## D. 文档债

### D2. 调试手册逐条重新核定

- **在哪**：`docs/development/debugging.md`。
- **现状**：同上，排查步骤的命令 / 路径可能已变。
- **完成判据**：同上，核完去掉文件头的「待重新核定」。

### D3. 术语表

- **现状**：`AGENTS.md` 曾有一个「术语（待补）」空节，属未兑现的承诺，已随瘦身删除。
- **完成判据**：整理出项目专有名词表，或明确决定不维护。**若整理，不要放回 `AGENTS.md`**  
  —— 它是"要查的时候才看"的资料，放 `docs/` 下；`AGENTS.md` 只留一行指针。

### D5. 子系统代码地图（学 animeko `docs/contributing/code/`）

- **现状**（2026-10-02 评估）：`.agents/skills/` 覆盖构建 / 风格 / 排查，但缺
  animeko 那种「改子系统前先读它的文档」的入口——FFPlayer 帧队列、web 包两端的窗口挂接链  
  这类知识散在文件头注释里，质量高但没有代码地图，也没有稳定的阅读入口。
- **完成判据**：为 player、web 两个最常动的子系统各出一页代码地图（术语 + 类职责 +  
  关键链路），放 `docs/development/` 下（无独立索引，由 `AGENTS.md` 指入）。

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

- **现状**：设计定稿于 [docs/design/architecture-design.md](./docs/design/architecture-design.md)  
  （2026-10-02，调研纪要见 [docs/design/architecture-research.md](./docs/design/architecture-research.md)），未落地。
- **已落地的第一个切片（2026-10）**：Bangumi OAuth 登录 + 收藏页，形态依据见
  [docs/design/bgm-plugin.md](./docs/design/bgm-plugin.md)。它**不按本文的包名与分层走**：客户端
  叫 `acg.source.BgmSource`（不是 `acg.catalog.bgm`）、服务商知识在 JS 插件里而不在 Kotlin、
  领域模型（`acg.model`）一个都没建、持久化是扁平字符串设置表而不是分片。清单里剩下的
  `epochTimeMs()` / kotlinx-serialization / `model` / 持久化分片 / `HtmlParser` / 限流退避
  **仍在**。已知缺口逐条列在 bgm-plugin.md §六（token 刷新、收藏写操作、条目详情页、限流退避）。
- **落地顺序**（architecture-design.md §5）：`epochTimeMs()` 墙钟原语 + kotlinx-serialization 依赖  
  （**改依赖需用户确认**）→ `acg.model` 领域类型 → `acg.catalog.bgm`（DTO + mapper + 限流退避）  
  → 持久化分片 + 收藏/历史仓库 → `HtmlParser` 宿主原语 + JS 数据源契约（含 B23 流式 response）+ Line 绑定 →  
  业务四页 → WebView 接口重设计（设计定稿于 [docs/design/webview-context-redesign.md](docs/design/webview-context-redesign.md)，已实现）→ WebView 投前台（悬浮按钮方案，先 Android 验证、桌面动 C++ 摘 `background` 标志）  
  → P2：AniList mapper / per-episode 精确进度 / animeko 订阅兼容解释器 / 边播边缓存 / torrent。
- **完成判据**：按 §5 分条验收，每条落地后回来更新本条状态；全部落地后删条目  
  （设计文档随实现修订，不删）。

### E9. 硬解帧的「窗口」：收帧端按 extra_hw_frames 记账（已落地，剩收尾）

- **现状（2026-10-30，桌面零拷贝）**：`AVFrame` 带着解码器的 surface 一路上到送显（收帧不 `unref`），
  而 d3d11va 的池是**静态**的：`参考帧 + 1 张输出帧 + 3 张工作帧 + extra_hw_frames`（H.264/HEVC
  参考帧 16，现值 3；算法见 `libavcodec/decode.c:1099` 与 `:1155`）。`extra_hw_frames` 只覆盖
  「上层同时压住几帧」，是纯增量、不含余量——它本身就是窗口该取的那个数，不要在它上面再加常数。
  「上层压住几帧」没有结构性上界：
  - 读包闸门 `frames.minOrNull() > 3` 按**最空的流**判，音频吃得快时视频队列可以一直长；
  - `sendPacketAndGetFrames` 一次把解码器能给的帧全取回来，片尾冲刷时是整个重排缓冲。
  池子挤爆时 `d3d11va_pool_alloc` 打 `Static surface pool size exceeded` 并返回 NULL
  （`libavutil/hwcontext_d3d11va.c:263`），`avcodec_send_packet` 因此返回 **ENOMEM（-12）**——不是
  EAGAIN，也不是无声丢帧：上层把它当「收下」，那个包的参考链就断到下一个 IDR。EAGAIN 只来自输入侧
  那个单槽（`avci->buffer_pkt`）。实测（2026-10-30，d3d11va、640x360 H.264）：只发不收时前 4 个包
  返回 0、第 5 个起恒 EAGAIN，全程只占 1 张 surface，池子不会满——要撞池子必须「收帧并压住」。
- **文档已对齐（2026-10-30）**：`playback-invariants.md` §2.10 与 `hardware-decoding.md` §5.2 原来写着
  「背压来自 surface 池、取空后 `send_packet` 给 EAGAIN」（错：溢出时 `send_packet` 返 ENOMEM）、
  `extra_hw_frames = 16`（代码是 12），以及把池写成 `1 + 16 + 3 + extra` 却没讲清 `extra` 只覆盖
  上层持有、不含余量——已按源码行号改正，并把「只发不收先到 EAGAIN」的实测口径记进 §2.10。
  `cxx/ffmpeg/ffmpeg.cpp` 收帧处那句「背压来自 ffmpeg 自己的池」同步改掉。剩下的只是下面这个设计缺口。
- **已落地（2026-11-02，只动 native）**：收帧端按 `extra_hw_frames` 守在途硬解帧数。三个函数在
  `video_output.h`（`videoOutputFrameRoom` / `videoOutputHoldFrame` / `videoOutputReleaseFrame`）：
  windows 把计数记在 `ctx->opaque` 指向的输出端上（`sink->lock` + atomic），Android 是
  `INT_MAX` / 空转；`frame->opaque` 存解码器本体，`AvFrame_closeNative` 靠它减回来。到上限就不再
  收，帧留在解码器里 ⇒ `buffer_frame` 非空 ⇒ 下一次 `send_packet` 只塞输入单槽 ⇒ EAGAIN ⇒ 读包
  停下。池里给解码器那一份因此一分没动，**送包端不需要闸门**。取 3（原 12）实测：整片 300 帧
  一帧不丢、12.08 s 实时、无读超时无池溢出（音视频同播 11.79 s）。
- **剩余（都不在池子溢出这条线上）**：
  - `FFPlayer` 的 `sendingPacket` 与 `frames.min() > 3` 闸门还在，现在是纯冗余（判据 ①）。
  - 窗口满 + 解码器输出槽压着一帧时，`send` 恒 EAGAIN、`receive` 恒空数组，两个入口都不会自己
    前进；播放轮靠 playJobs 当场消费帧自己解开，步进轮会空转到轮次结束。**不能靠「轮次结束就
    丢包」来解**——`stepForward` 不 seek，丢一个包下一次步进就跳帧
    （`FFPlayerTest` 会红）。
- **上一版实现**：`git stash list` 的 `帧窗口/逐帧回调重构（未完成，下一轮再说）`（另存了一份
  `/tmp/wt-window-refactor.patch`）。含 `AvCodec.sendPacket(packet, isAlive, onFrame)`、native 收帧改
  一帧一取、native 记账（`videoOutputHasRoom` + `FrameSlots`）的引入与删除、闸门改按视频队列、四个
  用例改新接口。当时可编译、23 个用例类通过、13s 探针 292（音视频）/298（纯视频）帧、无池溢出 ——
  只是改动面太大，先撤回。
- **完成判据**：① `FFPlayer` 里没有 `sendingPacket`；② 压住的帧数 == `extra_hw_frames`（不另引窗口
  常量），片尾冲刷也逐帧吐；③ 探针跑长片与 4K 各一次都不触发 `Static surface pool size exceeded`；
  ④ 文档里的口径与 `extra_hw_frames` 的实值一致；⑤ 送包的 `<0` 按轮次失败结束（不重试、不当收下），
  收帧侧的 `<0` 不再折叠成「没帧」。

### E10. 插件返回的信封 + 全局快照（等收藏页一起做）

- **现状**：约定已定稿在 [shared-layer §5.10](./docs/development/shared-layer.md)，代码一处没落。插件每次
  调用交回 `{ user?, collections?, notify?, session? }`，宿主合并成一份全局快照，界面只读快照。
  现在 `login()` 交的还是 `{ userId, name, avatar, session }`，`me()` 是插件内部函数，收藏页没有数据源。
- **做的时候一起落**：`Snapshot` 类型 + `SnapshotStore`（`StateFlow`；只覆盖出现的字段；切账号整体
  替换）→ `PluginAccountLogin` 折 `user` 子对象 → `bgm.js` 的
  `collections({ session, user, status, type, page })`（watching 档打 `/`，顺带回 `user` / `notify`；
  其它档打 `/anime/list/<user>/<status>`，只有 `collections`）→ 收藏页读快照。
- **完成判据**：账号卡、收藏页、角标三处都由同一份快照驱动；`bgm.js` 里没有多余的认人请求。

---

*建立于 2026-09-16。条目来源：`AGENTS.md` / `cxx/AGENTS.md` / `.agents/skills/*/references/` 里的  
「待修/待办/待补」标记、一次针对代码风格的取证调查、一次针对缩进与注释语言的实测扫描，  
以及 `.workbuddy/memory/2026-09-16.md` 的记录。*
