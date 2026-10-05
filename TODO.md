# TODO.md

本仓库**未完成的工作与已知缺口**。与 [AGENTS.md](./AGENTS.md) 的分工是死线：

| 文件 | 放什么 | 判据 |
|---|---|---|
| `AGENTS.md` / `cxx/AGENTS.md` | **长期有效的规则与陷阱**（"不读它就会改错"） | 与"做没做完"无关，永远成立 |
| `TODO.md` | **状态**——待修、待实测、待重新核定的东西 | 做完即删条目 |

> **不要往这里加规则。** 规则属于 AGENTS.md；加了会让本文件长成第二个法典，
> 而法典正是 AGENTS.md 要瘦身掉的东西。
>
> **做完一条就删一条**（并顺手把 AGENTS.md 里指向它的说明改对）。条目 ID 保持稳定，提交信息里引用 ID。

---

## B. 已知缺陷，待修

### B4. `commonMain` 里存在平台符号（违反根 AGENTS.md §4 的硬规则）

- **现状**：约定是 `commonMain` 不许出现 `java.*` / `android.*`。**2026-10-02 复核**（全量
  grep import 与代码级符号）：**import 9 处、6 个文件** —— `soko/ekibun/jni.kt` ×2
  （`Executors` / `AtomicBoolean`）、`quickjs/QuickJS.kt` ×3（`Collections` /
  `IdentityHashMap` / `AtomicBoolean`）、`quickjs/JSRef.kt`（`AtomicInteger`）、
  `ffmpeg/FFPlayer.kt`（`Executors`）、`ffmpeg/AvPlayback.kt`（`ByteBuffer`）、
  `acg/engine/JsEngine.kt`（`Charset`）；另有代码级内联：`acg/player/HttpIO.kt:47` 的
  `catch (_: java.io.IOException)`、`JsEngine.kt` 的 `::class.java`（:64）与
  `javaClass`（:117）。
  （2026-09-19 记录的 `ffmpeg/{AvFrame,AvCodec,AvFormat}.kt` 三个文件的 java import 已清掉，
  本条从原「7 个文件 12 处」改写成当前清单；FFPlayer 的 `System.currentTimeMillis` 三处
  已随 B17 换成 `monoTimeMs()` 原语。）
  也就是说 `soko.ekibun.{quickjs,ffmpeg}` 事实上仍按 JVM-only 写。
- **方向已定**（用户 2026-09-16 晚）：**不给这两个包开例外** —— 规则保持，把这些 Java 语义
  逐处提到外面（`expect` 一个最小原语、两端各 `actual`），`commonMain` 里最终不剩平台符号。
- **建议路径**（2026-10-02 评估）：先按 [E6](#e6-把-jni-绑定层抽成-bindings-模块b4-的前置重构)
  把绑定层抽成 `:bindings` 模块（搬移本身零语义变化、也不合法化这些 import），摘除在
  新模块里做 —— 新模块的 API 面就是"纯 common 原语 + 平台 source set 放 Java 细节"。
- **为什么现在没做**：属于独立的一次重构，要和文档改动分开。
- **完成判据**：上述各处全部去掉；`commonMain` 里搜 `java\.` / `android\.` 结果均为 0。
- **完成后必须做的收尾**：删掉 `AGENTS.md` §4 里那句"现状…仍有直接引用…见 `TODO.md` B4"
  的指针（届时规则已无例外），并删掉本条。

### B9. 退帧在「落点正好是 GOP 首帧」时退不动

- **现状**：**已修**（2026-09-22，路线 C）。空探步长由 `1µs` 改成 **3/4 × 已缓存的帧间隔**
  （新字段 `FFPlayer.frameIntervalUs`，在 `resumeImpl` 里靠相邻两帧的差值更新 ——
  必须**另存**，不能跟 `prevFrameTs` 混用：卡住时后者本身就是 `null`）。KDoc 已同步。
  A/B 实测（同一场景、两份只差容器时基的素材，表见下面「A/B 实测」）：改前 `-1f` 返回 `false`、
  画面停在 `4_000_000`；改后返回 `true` 且退到 `3_958_333`（正好一帧），再按一次退到 `3_916_666`。
- **原写法为什么退不动**（`seekTo(maxOf(current - 1, 0))`，**实测不成立**，保留作依据）：
- **确切机制（有出处）**：
  1. mp4/mov 只实现 `read_seek`（不是 `read_seek2`），`avformat_seek_file` 会把 min/max 窗口
     整个丢掉，落点必然是「不晚于 ts 的最近关键帧」（见 `AvFormat.seekTo` 的注释）。
  2. `cxx/ffmpeg/ffmpeg/libavformat/seek.c` 的 `seek_frame_internal`：
     `timestamp = av_rescale(timestamp, st->time_base.den, AV_TIME_BASE * st->time_base.num);`
     而 `av_rescale` = `av_rescale_rnd(..., AV_ROUND_NEAR_INF)`（`libavutil/mathematics.c`），
     `AV_ROUND_NEAR_INF` = 四舍五入、半数远离零（`libavutil/mathematics.h`）。
  3. ⇒ `current - 1` 微秒**折回当前帧自己那一格**（半格以内一律如此），容器给出同一个关键帧，
     收敛一帧都不丢，`lastDropped` 空 ⇒ `prevFrameTs` 空 ⇒ 返回 `false`、画面不动。
- **实测数据**：半格 = 0.5 × 1e6 × tb.num/tb.den —— `test.mp4`（tb 1/100000）= **5µs**、
  `ramp2.mp4`（tb 1/12288）= **41µs**，两条素材都吞掉 1 微秒；`ramp2.mp4` 的关键帧恰好在
  0/2/4/6/8/10s，`seekTo(4_000_000)` 正落在关键帧上，与「卡住」的前提吻合。
- **症状**：seek 到关键帧落点上（例如恰好 4.0s、GOP 2 秒）再按「-1f」不动，连按只是原地重画
  同一帧（探针实测 `连点 后退×5` → `lumas=[105,105,105,105,105]`）。
- **正解的方向**：让探测目标在**流的时基**里严格早于当前帧（约束 `半格 < ε < 一个帧间隔`）。
  容器落到上一个关键帧、收敛落点仍是当前帧（画面不动），而被丢掉的最后一帧恰好是真正的前一帧，
  随后那句 `seekTo(prevFrameTs)` 就是精确落点。**这条链已实测成立**（边界见下面的「实测数据」）。
  **2026-09-22 已拍板走 C**（ε = 3/4 × 已缓存的帧间隔，不碰 native）；三条路线的取舍保留如下：
  - **A（严格，推荐）**：native 暴露 `AvStream.time_base`（`getStreamsNative` 那处手里就有
    `stream->time_base`，加两个 int 即可），ε 按半格算。代价：动 `cxx` ⇒ 要走 `buildJni`
    与 dll 同步。注意「半格」要按 ffmpeg 实际用的那条流算（`seek_frame_internal` 用
    `av_find_default_stream_index` 选出的默认流），保险取各流里最粗的时基。
  - **B（一步到位，语义变更更大）**：目标直接取 `current - 一个帧间隔`，收敛的落点本身就是
    前一帧，第二次 `seekTo` 与 `prevFrameTs` 那套记账都能精简。但帧间隔只能从已观测的相邻帧对
    里学，VFR 下估小会退回卡住、估大会退两帧；严格版同样要 native 暴露（`AVFrame::duration`）。
  - **C（不碰 native）**：ε 取「已观测到的相邻帧间隔 × 3/4」（间隔由相邻两帧的 `lastFrameTs`
    差值缓存而来）。⚠️ **原先这里写的「取间隔 ÷ 2、下界自动满足」已被实测推翻**：÷2 的下界条件是
    「时基频率 > 帧率」，而 `tb == 1/帧率` 的文件（实测 `ramp3.mp4`，`-video_track_timescale 24`）
    让半格**恰好等于**间隔/2 —— 该素材上 ε=20833 失败、ε=20834 才成功，而间隔/2 = 20833.3 正好
    卡在下界上。改取 **3/4**：「半格 ≤ 间隔/2 < 3/4·间隔 < 间隔」，**只要帧在容器里能用整数 tick
    表示（tick ≤ 间隔，否则两帧同戳）就恒在窗口内**；对间隔估计的容差是 `(2/3)·间隔 < Î < (4/3)·间隔`
    （±33%），比 ÷2 那版「差一点点就翻车」宽得多。两个缺口不变：① 卡住时 `prevFrameTs` 本身
    就是 `null`，所以**间隔必须另存**，不能跟它混用一个字段；②「冷启动直接 seek 到关键帧」时
    一个间隔都观测不到，只能回退到常数余量 —— 那就又回到 A 的必要性。

- **⚠️ 两段式与一段式对 ε 的要求正好相反**：**记账（`prevFrameTs`）正是「让不准的估计变得无害」
  的那个东西**，砍掉它等于拿精度换记账。
  - **两段式（保 `prevFrameTs`，= A / C）**：ε ∈ `(半格, 真实间隔)`。**偏小无害** —— 只要过了半格，
    前一帧仍是从「真正解码出来又被丢掉的那一帧」里读出来的，**不是算出来的**，所以估计不准不影响
    结果；偏大到越过前一帧 ⇒ 退两帧。
  - **一段式（砍掉 `prevFrameTs`，直接 `seekTo(current - ε)` 落到前一帧）**：ε **直接决定落点**，
    必须 ∈ `[真实间隔, 真实间隔 + 前一帧的间隔)`。**偏小 ⇒ 画面完全不动**（且不重试放大就不会自己
    变好）；偏大越过再前一帧 ⇒ 退两帧。
  - ⇒ 一段式对间隔估计的要求是**宁可偏大**，两段式是**宁可偏小**；两者都不能零误差免俗。
  - **实测**（两张表一致）：一段式的临界正好是 **ε ≥ 一个帧间隔** —— 41666µs 时画面仍停在
    4_000_000 不动、41667µs 时才落到 3_958_333。所以「砍掉 `prevFrameTs`、取间隔/2」这条路
    **必然退不动**（间隔/2 恒小于间隔）。
- **实测数据**（2026-09-22 探针 `ZzSeekEpsProbeTest`）：两份素材内容逐帧相同、只差容器时基，
  都从关键帧落点 `4_000_000µs`（第 96 帧）上开始，先用 `seekTo(4_000_000 - ε)` 看 `prevFrameTs`
  学到没有，再按一次 `stepBack()` 看最终停在哪一帧（按整帧亮度反查帧号）：

  | 素材 | 容器时基 | 半格 | 学到 `prevFrameTs` 的最小 ε | 退两帧的起点 |
  | --- | --- | --- | --- | --- |
  | `ramp2.mp4` | 1/12288 | 40.69µs | 41µs | 41667µs |
  | `ramp3.mp4` | 1/24 | 20833.3µs | 20834µs | 41667µs |

  两条边界都与推导吻合（下界 = 半格、上界 = 一个帧间隔 41666.7µs）；ε ≤ 半格的每一行
  `stepBack()` 都返回 `false` 且画面停在原帧（症状复现）。**固定常数 ε 因此不成立**：1µs 在两张
  表上都失败，1ms 只在 `ramp2` 上成功、在 `ramp3` 上被半格吞掉。
- **A/B 实测**（2026-09-22 探针 `ZzStepBackEpsTest`，`report7.txt`）：两份素材都先 seek 到关键帧
  落点 `4_000_000µs`（此时 `prevFrameTs` 为空 ⇒ 走空探），然后连按两次 `-1f`：

  | 素材 | 容器时基 | 空探步长 | `-1f` #1 | 落点 `lastFrameTs` | `-1f` #2 | 落点 `lastFrameTs` |
  | --- | --- | --- | --- | --- | --- | --- |
  | `ramp2.mp4` | 1/12288 | 1µs（旧） | `false` | 4_000_000 | `false` | 4_000_000 |
  | `ramp2.mp4` | 1/12288 | 3/4 间隔（新） | `true` | 3_958_333 | `true` | 3_916_666 |
  | `ramp3.mp4` | 1/24 | 1µs（旧） | `false` | 4_000_000 | `false` | 4_000_000 |
  | `ramp3.mp4` | 1/24 | 3/4 间隔（新） | `true` | 3_958_333 | `true` | 3_916_666 |
- **顺带该独立修的一条**：现在「已在第一帧」与「没探出来」共用 `return false`。前者对，后者在
  掩盖问题 —— 返回值应以 `lastFrameTs` 是否变化为准。
- **完成判据**：入库一个用例：seek 落到关键帧上之后 `stepBack()` 返回 `true` 且 `lastFrameTs` 变小。
- **探针与素材**：留在本机 `.workbuddy/ref/step-probe/`（`ZzStepProbeTest.kt.saved`、`ramp2.mp4`、
  `_genmedia.py`、`report4.txt`；ε 扫描的 `ZzSeekEpsProbeTest.kt.saved`、`ramp3.mp4`、
  `_genmedia3.py`、`report5.txt`；A/B 的 `ZzStepBackEpsTest.kt.saved`、`report7.txt`；
  重叠/送显核对的 `ZzOverlapProbeTest.kt.saved`、`report6.txt`），做法与 B8 同源
  （探针跑完移出源集、媒体不进仓库）。

### B10. 送显像素与该帧时间戳不符（花屏）：三处已修，仍有一段未定位

- **已排除的三层**（2026-09-22，探针 `ZzRoundTeardownProbeTest`，`report9.txt` / `report10.txt`）：
  - **渲染侧**：桌面 `Playback.jvm.kt` 把 `Image` 交给 Compose 之后立刻 `close()`，看着像
    use-after-free —— 反编译 `ui-graphics-desktop` 可见 `toComposeImageBitmap()` 内部是
    `allocPixels` + `Canvas.drawImage` + `setImmutable`，Compose 手里那张 `Bitmap`
    **自己独占像素**，所以 `close()` 是安全的；
  - **轮次收尾**：`late=0`（入口返回之后没有迟到的送显）、`foreign=0`（抄走的缓冲就是这一帧
    自己写进去的）、`closedUse=0`（没有已归还却还在用的帧）；
  - **素材**：ffmpeg CLI 独立解码这 288 帧逐帧对亮度，与 `mod(N*5,200)+28` **全部吻合**。
- **已修之一：被作废的轮次会「复活」**（`FFPlayer.resumeImpl`）。`isPlaying()` 原来只判
  `pts == this.pts && pts.playing`，而 `resume()` / `stepForward()` **复用同一个 `PTS` 对象**
  （只有 seek / `play` 才换新的）—— 新一轮把 `playing` 置回 true 时，上一轮正卡在「等视频
  追上主时钟」里的那个作业会继续往下走、补一次 `flushFrame`，而缓冲里此刻已经是**这一轮**
  写进去的像素 ⇒ 那一帧显示成后面的帧。当时（2026-09-22）的修法是判据带上自增轮次号
  （`roundSeq == round`），该号 2026-09-25 已删，见下面的沿革。
  ⚠️ **判据后来换过两次，现状如下**：
  - 2026-09-25：加过一个自增轮次号 `roundSeq`（`roundSeq == round`），同日又删掉，改成
    **一轮 == 一个 `PTS` 对象**，判据回到对象身份 `pts === 当前 pts`。
  - 2026-10-01（现状）：`resume` / `stepForward` **不再换 `PTS` 对象**（原 `PTS.fork()` 已删）
    —— 它们不换位置、手里的包与在飞帧都还在当前位置上，换对象会害它们被判「过时」丢掉
    （包已离开 `av_read_frame`，补不回来 ⇒ 跳帧 + 花屏）。换对象只剩**换位置**的两处：
    `play`（新建 `PTS`）与 `seekImpl`（`pts = newPts`）。闸入口是 `takeOverPlayback()`。
  - `AvFrame.processing` 相应改成**只判空**（不比对是哪个 `PTS`）：判据问的是「有没有人在用」，
    不是「谁在用」—— 上一轮的 `updateJob` 是投给播放线程的排队任务，`pause()` 的
    `playingJob.join()` 等不到它，判 `!= pts` 会把它取走、与本轮作业撞同一帧。
  **证据**：post / flush 两个序列对照，同一帧被 `postFrame` **两次而只 `flush` 一次**。
- **已修之二：EAGAIN 会静默丢包**（`cxx/ffmpeg/ffmpeg.cpp` 的 `sendPacketAndGetFramesNative`）。
  `avcodec_send_packet` 返回 `EAGAIN`（解码器输入队列满）时原代码**不重发、直接往下走**，
  调用方随后把这个包 close 掉 ⇒ 码流里少一帧的数据。2026-09-22 复测时把它从「最多重发 4 次、
  不成就算了」改成 **重发 16 次 + 出声**：
  - `EAGAIN` 耗尽 ⇒ 一行 `AV_LOG_ERROR`（`连续 N 次 EAGAIN，包被丢弃：stream=… size=…`）。
    **这是判「花屏是不是这条路造成的」的一线仪器**：桌面端没装 `av_log` 回调
    （`JNI_OnLoad` 里那份在 `#ifdef ANDROID` 内），所以它走 **stderr** —— `:desktopApp:run` 的
    重定向输出里直接可见（同一条通路已实测：测试 JVM 的原生 `[swscaler …]` 就是这么被抓到的）。
  - 其他 `ret < 0`（非 EOF）同样打 ERROR，并把**本批已解出的帧照样交出去**。
  ⚠️ **它没能消除下面那段残留** —— 丢包必然伤参考链，这条是真缺陷，但**不是**残留的成因。
- **残留（未定位）**：探针里（只有视频流 ⇒ 主时钟没人推，解码跑到约 100fps）偶发：显示的是
  **早 1~2 帧**的像素，而**像素本身是完整帧**（整帧同值、`uni=1.0`，不是半帧被改写也不是
  解码残影）；短段 2~3 帧自愈，长段（20+ 帧）总在**关键帧**（第 144 / 192 帧，`-g 48`）处
  愈合。逐次步进恒为 0。修完上面两条后降到「190 帧里 0~3 帧」，但 `playing + stepForward x3`
  仍偶发 25 帧。
  ⚠️ 探针**没有音频时钟**，这段**可能就是非实时播放造出来的伪影**，真机（音频推时钟）未必复现
  —— 这一点没实测前不要把残留当成真缺陷。
- **已排除之四：送显管线逐字节正确（2026-09-22 复测，带音轨 + 实时节奏）。**
  探针 `ZzFlushPixelProbeTest`（归档在 `.workbuddy/ref/flush-probe/`）驱动**真实连续播放**
  （`test.mp4`，VIDEO+AUDIO，1786 帧 / 1308x736 / 60fps），把每一帧
  `flushVideoBuffer` 拿到的 `ByteArray` 与「串行解码 + 立即转换」的参考逐帧比对
  （整帧哈希 + 逐行哈希）：
  `送显 1786 帧；与参考逐像素完全相同 1786；不匹配 0`。
  ⇒ **解码 → `sws_scale` → `getBuffer` → 送显这一段当前是干净的**，本条的残留不在它上面。
  （报告里的「28 次 ts 非单调」是按内容哈希对齐在**静止画面重复帧**上的歧义，不是顺序错误。）
- **已排除之五：渲染侧第二次确认。** 反编译 `ui-graphics-desktop-1.12.0` 的
  `Actuals.skikoExcludingWeb.kt`：`internal actual fun Image.toBitmap()` 是
  `Bitmap.allocPixels(ImageInfo.makeN32(...))` + `Canvas.drawImage(this, 0f, 0f)` +
  `setImmutable()` —— **自己独占像素**；`toComposeImageBitmap()` 拿到的就是这张独立 Bitmap。
  所以 `Playback.jvm.kt` 里 `frame.close()` 紧跟其后**不构成悬垂引用**。
- **已排除之六：截图的形态学判据。** `QQ20260922-191623.png`（2080x1390）量化结果：
  梯度**不落在** 8/16/32 周期上（比值 0.87 / 0.53，网格线反而比其余位置更平滑）、
  相邻行最佳位移**恒为 0**（无 stride 斜切）、三通道相关性正常（R-G 0.94 / G-B 0.93 / R-B 0.80）、
  各通道取值档位基本齐满（无量化丢位）。
  ⇒ 「宏块对齐的遮错马赛克」「stride 不符」「通道误配」三类**都没有证据**。
  ⚠️ 但那是**整窗**截图（含 UI，主色淡紫 `aea0cc` / `ede4f5`），**无法确认花屏区就是视频区**
  —— 这条判据只说明"截图里看不出那三类结构"，不能反推"花屏不是那三类"。
- **已修之三：失败路径上的帧泄漏**（同一函数）。`ret < 0 && ret != AVERROR_EOF` 时原代码直接
  `return empty;`，而 `out` 里**已经收出来的帧没有 `av_frame_free`** ⇒ 每命中一次泄漏若干帧。
  现在不再提前返回，一律走完 `receiveAll()` 并把已解出的帧交出去。
- **已排除之七：跳转路径本身没有破链点。** 用户 2026-09-22 的判断是「肯定是跳转的问题」，
  于是把 `FFPlayer.seekToRound` 逐行核过：它在 `super.seekTo` **之前**对**每个** codec 调了
  `flush()`（丢参考帧历史与重排缓冲，`FFPlayer.kt:204-208`），`resyncTo` 的丢帧收敛又排在
  送显**之前**、且对每条流都成立；`frames` 清队只关「不在飞行中」的帧，在飞的由各自
  `invokeOnCompletion` 归还。⇒「seek 后解码器还拿着旧参考帧」「先上屏再决定丢不丢」
  「清队与飞行中帧抢所有权」三条**都不成立**，跳转路径里**没有**能解释花屏的破链点。
- **用户实测补充（2026-09-22）**：花屏出现在**视频画面内**（不是 WebView 页面）、
  形态是**持续成块、只有跳转才恢复**、播放源是**本地文件**（不走 HTTP）。
  ⇒ 原「待查方向」③ 已答（是视频）；① 里的 HTTP 通路排除。「持续到下次关键帧才愈合」
  正是**参考链断裂**的特征形状，与「已修之二」的丢包机理吻合 —— 但探针（带音轨、实时节奏）
  一次都没复现出丢包，所以**这条还没有一线证据**，要靠上面那行 ERROR 在真机上抓。
- **已定位（2026-09-22，一线证据）：`resumeImpl` 的读包循环会「吞包」—— 就是花屏的成因。**
  读包循环里 `getPacket` 之后有一条早退分支：`if (!isPlaying()) { packet.close(); break }`。
  要害是 **`av_read_frame` 没有"放回去"这一说**：`getPacket` 走 [AvFormat] 的归属 dispatcher
  （另一条 `avformat` 线程，`withPtr` 会真的 `withContext` 过去），所以「包已读出、轮次却在这
  期间作废」是个**真实窗口**；此时 `close()` **不是归还，是从码流里永久吞掉一个包**
  （**本次 pass 内不可恢复**；跨 seek 会被重读，见下面的口径修正）—— 解码器少一个参考帧，
  其后每一帧都带着错参考解，**一直错到下一个 IDR**（即「继续算会继续花屏」）。
  **实测**（探针 `ZzStepRapidProbeTest`，`test.mp4` seek 到 1/3 处；证据 `report18-test.txt` +
  `probe18-test-stdout.txt`，判读脚本 `.workbuddy/tmp/analyze_drops2.py` / `analyze_drops4.py`）：
  - 70 次 `DROP_PACKET`（`playing=false`，就是本条早退分支）里 **64 次在该次 pass 的解码序列上
    留下 2 帧缺口**（`delta≈33300µs`，正常帧间隔 16660µs）；缺口正好落在最后一次
    `DECODED` 与下一轮第一个 `DECODED` 之间，且 `queued=9` 说明命中的是读前量位置。
  - ⚠️ **口径修正（2026-09-22 复核）**：「吞掉整整一帧」只对**同一次 pass** 成立，**不是全片
    永久缺席**。把整个日志（含 D / A / F / H 四个场景）的 `DECODED` 时间戳去重后与 ffprobe 的
    1786 帧逐个比对：**一帧不缺、也没有多余**（`analyze_drops3.py`）。原因是后面几个场景又 seek
    回同一段、把那一段重读重解了一遍。原先写的「ref 606 从解码序列里消失」措辞过强，已改。
  - **场景 D 的对齐**：seek 落点 = IDR `#480`（8.003s，`av_seek_frame` 只落目标之前的关键帧），
    丢帧收敛后从 `#596` 起显示；被吞的是 `#606`/`#607` 那一格（探针报第一帧坏帧在显示下标 10，
    即 `#606`，与 607 差 1 —— 这一格没核）。全片 GOP **恒 120 帧**、IDR 在
    `0/120/…/600/720/…`，下一个 IDR = `#720`（12.0043s）⇒ `720 − 607 = 113`
    **正好等于探针实测的未命中帧数 113**，坏帧一路错到 IDR 才愈合。
  - 坏帧的像素判据是**「与全片 1786 帧的哈希全不匹配」**（不是「显示成早一帧的完整帧」）
    —— 即解码输出本身就是错的，与「参考链断了」相符。
  - **场景 F（60 次顺序步进）**：逐轮统计解码推进 `#608 → #610 → #612 …`，**每步解码走 2 帧、
    显示只走 1 帧** ⇒ 每步真的少解一帧 —— 这就是用户那句「跳过了一帧解码」在一线上的样子；
    坏帧从 `#606` 起一路到探针停止，从未愈合（没走到 `#720`）。
  - 对照 **H（不 seek、播放期间不作废轮次）= 0 未命中**；场景 A（10 个并发 stepForward，
    被轮次闸收敛成 1 轮）= 1 帧、0 未命中。
  - **花屏定性探针（`decodeErrorFlags` 字段 + `DECODE_ERROR` 日志）已于 2026-09-23 删除**：
    实测全程 `decode_error_flags` 恒为 0、对该场景不敏感，留着只是死代码，故连同
    `AvFrame.kt` 字段、`FFPlayer.kt` 探针、`ffmpeg.cpp` 的 `newAvFrame` 第 5 个构造参数一起撤掉；
    JNI 构造签名 `(JJIII)V` → `(JJII)V`，`ffmpeg.dll` 已重编（见同 commit）。
  ⇒ B10 原先「丢包不是残留的成因」（依据：native 那条 EAGAIN 通路没复现）**结论要改**：
  残留的形态（「长段总在关键帧处愈合」）与这条吞包完全吻合 —— 在 ramp 素材上每帧亮度只差 5，
  错参考解出来的样子就是「像早 1~2 帧的完整帧」（在真实素材 `test.mp4` 上则表现为**像素与全片
  任何一帧都不匹配**，见上面场景 D 的判据）。
- **已修（2026-09-22）**：`FFPlayer` 加了一个字段 `pendingPacket` —— 那条早退分支不再 `close()`，
  而是**把包留着**；读包循环开头**先喂留下的包**、再去 `getPacket`；`seekToRound` / `closeAsync`
  两处才真的归还（那两处它确实会被重读 / 不会再开新轮）。改法由用户给出并定调：**不「就地送解码」**
  —— 那是在「轮次已作废」的语境里去动解码器与 `frames`，多出一件说不清的事；留着、由下一轮按
  正常路径喂，包序与帧序都不变。
  ⚠️ **2026-10-01：该字段已整个删除** —— 事后发现根因是**判据写错了**（`isPlaying()` 把「换了位置」
  与「只是暂停」混在一个布尔里），`pendingPacket` 只是替它兜底。拆成两种情形后不再需要该字段：
  `this@FFPlayer.pts != pts` → `close + break`（换位置），否则**照常送入队**（含暂停 ⇒ 预读缓存）。
  `PTS.fork()` 已一并删除（`resume` / `stepForward` 不再换对象）。实测 6 轮暂停恢复 `jumps=0`。
  ⚠️ **2026-10-02 复核：上面那段（判据只看对象身份、换位置 `close + break`）又变回现状。**
  中途有一版把读循环整个搬出 `resumeImpl`、成了常驻 `readLoop`（`SeekRequest` + `demuxerPos`），
  那一版**没有留在代码里**；现在**预读放在 `AvFormat` 的 packet 通道**（`PREFETCH_PACKETS` 个包，
  通道满则读作业停在 `send`），于是「暂停期间继续缓存」不再受 `pause()` 影响（它本来就不在
  FFPlayer 里），`FFPlayer` 侧回到「读循环留在 `resumeImpl`、条件 `while (isPlaying())`」。
  当初非搬不可的理由仍然成立（`pause()` 的 `playingJob.join()` 等的是**整轮**，读循环就在
  那一轮里 ⇒ 暂停一到它退出、缓存不存在），实测数字（探针 `ZzPauseCacheProbeTest`，
  判据 = `frames` 长度：修前 `[106]×6` 不涨、常驻版 `[110]×6`）留着当参照。细节见
  [`.agents/skills/project-traps/references/silent-failures.md`](./.agents/skills/project-traps/references/silent-failures.md)
  对应 bullet。
- **复测（同一探针、同一素材；提交前核对：三条编译闸门 + 全量 `:shared:jvmTest` + lint 全绿）**：

  | 场景 | 修前未命中 | 修后未命中 |
  |---|---|---|
  | D（seek 后纯播放） | 113 | **0**（送显 1189 → 1190） |
  | F（乱点 10 并发 → 顺序 60 步） | 52 | **0**（送显 62） |
  | A（10 并发 stepForward） | 0 | 0 |
  | H（不 seek 对照） | 0 | 0 |

  F 的「逐帧命中下标」从「`596..605` 之后整片 `-1`」变成 `596..657` **完全连续** ——
  **每步正好一帧，不再跳帧**。证据：`.workbuddy/ref/step-probe/before-pending-fix/`（修前）
  与同目录 `report18-test.txt`（修后）。`decode_error_flags` 修后仍全程为 0，印证了
  「它对这个场景不敏感」，别再拿它当判据。
- **待查方向**（重排后）：
  ① ~~真机带日志复现~~ / ~~改~~ —— **已在一线钉住并修完**（见上面「已定位」「已修」两条）：
     成因是读包循环吞包，与 native 那条 EAGAIN 通路无关；当时的修法是 `pendingPacket`（留着包、
     下一轮开头再喂）。复测：D / F 的未命中 113 / 52 → **0**，F 的命中下标 `596..657` 连续。
     ⚠️ 该字段 2026-10-01 已删（判据拆开之后它多余），见「已修」那条的补充。
  ② 渲染之后那一段（Skia → Swing/Compose → GPU），探针只抓到 `ByteArray`，看不到上屏像素。
  ③ 素材规格（10bit / HEVC / Interlaced / 高码率）。
- **完成判据**：上面①里「出日志」与「不出日志」二者有一个被实测钉住 —— 出日志则顺着日志修；
  不出日志则写明「解码通路已由日志证伪」，把本条收窄到渲染侧。或证明确属探针伪影后降为「不修」。
- **探针与素材**：`.workbuddy/ref/step-probe/`（`ZzRoundTeardownProbeTest.kt.saved`、
  `ZzStepRapidProbeTest.kt.saved` + `report18-test.txt` / `png18-test/`、
  `report9.txt` / `report10.txt`；`_verify_material.py` 是素材的独立核对）、
  `.workbuddy/ref/flush-probe/`（`ZzFlushPixelProbeTest.kt.saved`、`report12.txt`）。

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
  `FileIO` / `HttpIO` 实现（ktor/OkHttp）。所以**「ffmpeg 没有网络协议」是对的**。
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
  （`VideoSurface.kt:10-11`）写明的「界面销毁时以 null 回调 `onPlayback`」在 Android 侧从未发生，
  `AndroidPlayback`（AudioTrack + Surface + SurfaceTexture）随页签切走而漏。现在用状态把 factory
  回调里建的播放器接出来，onDispose 里回调 null + `close()`；`AndroidPlayback.close()` 顺带
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
  提交前核对三条编译闸门。

### B21. 测试配置了空源集

- **现状**（2026-10-02 评估发现）：`shared/build.gradle.kts` 配了 `withHostTest`
  （`isIncludeAndroidResources = true`）与 `withDeviceTestBuilder`，但 `androidMain`
  **没有任何测试源**；`commonTest.dependencies` 声明了 `kotlin-test`，`commonTest`
  **目录不存在**。空配置会误导（以为 Android 单测 / common 测试在跑）。
- **完成判据**：配置与源集一致 —— 要么删掉空配置，要么真补源（`commonTest` 放与
  JVM/native 无关的纯用例）。

### B22. 取包超时改成「继续重试」后，`pause()` 的最坏耗时变成一个取包超时（100 ms）

- **现状**（2026-10-04 改 `AvPlayback.isReadTimeOut` 时落下的）：`FFPlayer.resumeImpl` 的取包
  超时分支从「置 `pts.playing = false` + `break`」改成了「上报 `PACKET_READ_TIMEOUT` +
  `continue`」—— 网络恢复后能接着取包，不必让上层重新 `play`。代价是
  **播放轮次不再因超时自己结束**，于是 [FFPlayer.pause] 的 `playingJob.join()` 要等当前那个
  `withTimeout` 到点才返回（不再是毫秒级）。
  `FFPlayerStallMidPlaybackTest` 的 `pause()` 探测预算 5 s，够用。
- **还要定的**：超时重试的节奏。**已定：固定 10 Hz，不加退避**（2026-10-04 删掉超时分支里
  的 `delay(100)`）—— 一轮就是 `withTimeout(100)` 那 100 ms，那个 `delay` 是白等一倍，
  于是 [FFPlayer.pause] 的最坏耗时也缩到「等当前那个循环轮次走完」，机制上界 = 一个轮次
  （实测是个跨度、七次 7～108 ms，取决于探测那一刻停在哪一格：可能是取包的 `withTimeout`，
  也可能是回压分支的 `delay(1)` ⇒ **下界不是「毫秒级」**；略超 100 是调度与计测开销）。
  ⚠️ **重试不重新进 native**（这条已核实，别再按「每轮一次 native 往返」推）：
  [AvFormat.getPacket] 的 `packetChannel ?:` 在通道存活期走**非空分支、不重建**
  `readerJob`（见 [AvFormat.resetChannel] 的 KDoc，那两个字段为什么没有 `@Volatile`）。
  读作业建一次就始终 park 在 `getPacketNative` 里、跨所有重试（它不是轮次的子作业，
  轮次结束也不等它）。所以每次重试的实际代价只是消费侧 `for (v in channel)` 空等
  100 ms 再被 `withTimeout` 取消 ⇒ **只有 10 Hz 的协程定时器开销，没有 native 往返，
  也不重建 `HttpIO` 会话检查**。原先「长时间停网时空转很贵、加限流」的顾虑不成立。
  剩下的是**响应**问题：网络恢复后最迟 100 ms 就醒过来看一眼，够不够快由产品定。
- **完成判据**（2026-10-04 已做）：`isReadTimeOut` 的 KDoc 已把「轮次不结束、pause 要等当前
  那个循环轮次走完」写死；`FFPlayerStallMidPlaybackTest` 也补了两条定点断言 ——
  探测 `pause()` 之前先取一次 `isReadTimeOut` 快照、确认**那一刻确实处在取包超时进行中**
  （少了它，「`pause()` 返回了」这条断言在**没有**超时时同样成立，量到的耗时证明不了任何事），
  再对耗时钉一个量级上界（1 s 预算，实测跨度 7～108 ms）。
  ⚠️ 它管的是**量级**不是精确值：把 `delay(100)` 加回去会变成 200 ms 上下、仍在预算内 ⇒
  抓不到那种回归；要钉精确值得另写判据。

## C. 事实未实测，文档里暂无据

### C1. Android APK 产物路径

- **现状**：`androidApp/build/outputs/apk/debug/` 是按标准 AGP 默认写的，**本机没实跑过**。
- **完成判据**：真跑一次 `:androidApp:assembleDebug`，按实测结果写进 `AGENTS.md` / skill `build-and-test`。

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
  `AGENTS.md` §1 竟没有）。压长度时**漏了 4 处**：`AvPlayback` 的「写播放倍速。」配
  `fun setSpeedRatio`、「读播放倍速。」的前半句；`VideoSurface` 的「平台相关的视频输出区域。」
  配 `expect fun VideoSurface`；`Playback` 类 KDoc 后半句「音频/视频输出由各平台子类实现」
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

### E2. `:androidApp` 自身编译未纳入构建闸门

- **现状**：闸门是三条任务，`androidApp/` 下的改动不会被拦到。
- **完成判据**：把 `:androidApp:compileDebugKotlinAndroid` 加进闸门并实测通过。

### E3. Android 侧 native 没有构建入口

- **现状**：`androidApp/build.gradle.kts` 无 `externalNativeBuild` / ndk 配置；
  根 `CMakeLists.txt` 的 `if (ANDROID)` 分支（链 `log` 库、不加 `JAVA_HOME` include）没人调用。
- **为什么现在没做**：这是**决策题**，不是 bug——是否要在 Android 上用 native 尚未拍板。
- **完成判据**：决定要做则补 CMake/AGP 配置；决定不做则删掉那条死分支。

### E4. （可选）把 MSYS2 的坑自愈进 `cxx/build.jni.sh`

- **现状**：`MSYS2_BIN` 仍需存在，否则 `exec.cmd` 直接退出 1。
- **完成判据**：让 `:desktopApp:buildJni` 在未设 `MSYS2_BIN` 时给出可读报错或自动探测。

### E5. `viewmodel-compose` 依赖引了但全仓零使用

- **现状**：`shared/build.gradle.kts` 引入了 `viewmodel-compose`，但代码里没有 `ViewModel` / `StateFlow`
  / `collectAsState`。它会误导 agent 以为"项目选了 MVVM"。2026-10-02 复核仍零使用，且同处引入的
  `lifecycle-runtimeCompose` 同样零使用（全仓无 `collectAsState*` / `Lifecycle*` 调用），删依赖时一并评估。
- **完成判据**：确认确实不需要就删掉依赖；需要就先用起来再留。

### E6. 把 JNI 绑定层抽成 `:bindings` 模块（B4 的前置重构）

- **方案**（2026-10-02 评估定案）：新建**单个**模块 `:bindings`（目标 `jvm()` + `android {}`，
  依赖仅 coroutines，**不引 compose**），搬入 `soko/ekibun/jni.kt` +
  `soko/ekibun/quickjs/`（6 文件）+ `soko/ekibun/ffmpeg/`（10 文件）；`shared` 以
  `api(project(":bindings"))` 挂接，**包名不变** ⇒ 业务调用点零改动。
- **边界修正**（与最初设想不同处，评估已定）：
  ① `soko.ekibun.web` **不搬** —— commonMain `WebView.kt` 是 Compose 契约，native 桥
  （`NativeWebView.jvm.kt`）在 jvmMain 本已合法；
  ② `quickjs/Highlight.kt` **先迁出**到 UI 侧（它 import `androidx.compose.ui.*`，是 CodeScreen
  的语法高亮器；对 quickjs.dll 的 native tokenize 依赖经 `api` 传递照常可用）；
  ③ **CInterop 排除** —— JVM 目标没有 CInterop（Kotlin/Native 专属），两端都是 JVM 族，
  JNI 是唯一公共分母（animeko 的 anitorrent 同为手写 JNI）。
- **与 B4 的关系**：搬移本身**不**合法化 java.* import（9 处跟着代码走）；先机械搬移
  （零语义变化），B4 的 expect/actual 摘除在新模块里做。
- **随迁**：jvmTest 的 dll 注入（`shared/build.gradle.kts` 的 `ProcessResources`）与
  `soko.ekibun.{quickjs,ffmpeg}` 的测试文件搬到新模块；`desktopApp` 打包的 dll 落位引用同步改。
- **为什么现在没做**：一次性重构，与业务改动分开。
- **完成判据**：提交前核对：三条编译闸门 + 全量 `:shared:jvmTest` + lint 全绿；`shared` 的源集里不再有
  `soko.ekibun.{quickjs,ffmpeg}` 源文件。

### E7. cxx 构建接进 Gradle（把「dll 手工同步」变成机器闸门）

- **现状**：`shared/build.gradle.kts` 的 `jvmTestProcessResources` 从 `cxx/build/bin` 抓 dll，
  运行位落位靠 skill `dll-sync` 的人肉清单；[project-traps](./.agents/skills/project-traps/SKILL.md)
  把「改过 native 却用着旧 dll」列为头号静默失效。animeko 把 native 做成了 Gradle 模块
  （anitorrent），我们不需要走到那一步。
- **分两档**：
  - **档一（先做，数小时）**：`verifyDll`（比对 `cxx/build/bin` 与各落位的 hash/mtime，
    过期即 fail）+ `syncDll` 两个 task；skill `build-and-test` / `dll-sync` 改为引用 task。
  - **档二（可选，1-2 天）**：gradle task 驱动 cmake 编 Windows 目标，产物直接喂
    jvmTest 资源与 desktopApp 打包 —— 从此「忘记先编 cxx」在构建期就暴露。
- ⚠️ **不要绑 Android NDK** —— 那是 [E3](#e3-android-侧-native-没有构建入口) 的独立决策，别搭车。
- **完成判据**：改了 `cxx/` 不重编时，闸门/测试给出**明确的失败**，而不是静默用旧 dll。

### E8. 领域架构落地（数据结构 + WebView 前后台）

- **现状**：设计定稿于 [docs/architecture.md](./docs/architecture.md)
  （2026-10-02，调研纪要见 [docs/research-2026-10-02.md](./docs/research-2026-10-02.md)），未落地。
- **落地顺序**（architecture.md §5）：`epochTimeMs()` 墙钟原语 + kotlinx-serialization 依赖
  （**改依赖需用户确认**）→ `acg.model` 领域类型 → `acg.catalog.bgm`（DTO + mapper + 限流退避）
  → 持久化分片 + 收藏/历史仓库 → `HtmlParser` 宿主原语 + JS 数据源契约 + Line 绑定 →
  业务四页 → WebView 投前台（悬浮按钮方案，先 Android 验证、桌面动 C++ 摘 `background` 标志）
  → P2：AniList mapper / per-episode 精确进度 / animeko 订阅兼容解释器 / 边播边缓存 / torrent。
- **完成判据**：按 §5 分条验收，每条落地后回来更新本条状态；全部落地后删条目
  （设计文档随实现修订，不删）。

---

*建立于 2026-09-16。条目来源：`AGENTS.md` / `cxx/AGENTS.md` / `.agents/skills/*/references/` 里的
「待修/待办/待补」标记、一次针对代码风格的取证调查、一次针对缩进与注释语言的实测扫描，
以及 `.workbuddy/memory/2026-09-16.md` 的记录。*
