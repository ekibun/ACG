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

## A. 用户明确要求，不可留着

### A1. 清理代码里过时/错误的 AI 注释

- **现状**（2026-09-19）：全仓复查已做完（82 个文件 / 2560 行注释，取证见本机
  `.workbuddy/comment-review.md`），**已改 30 处**：
  - 点名样本：`必须 jbr-11`、`HttpIO.offset += ret 是重复累加` 在当前树里**已不存在**；
    `Tao 后端是唯一选择`、`RequestInterceptor 拦不到子资源` 这两条属历史陈述，
    已随下一条**整段删掉**。
  - **对"已移除依赖"的引用 7 处全删**（用户口径：「仓库里没有的东西，注释里就别提」）：
    `Nucleus` / `Tao` / `dev.nucleusframework:composewebview` / `Nsis` 早就不在任何构建配置里
    （`git log -S` 可追：`0a75461` 引入 → `f113a56` 换成自研宿主），注释里"以前用的是 X"
    只会让人以为仓库里还有。涉及 `cxx/webview/webview.cpp` 文件头、
    `desktopApp/build.gradle.kts`、`shared/build.gradle.kts`、`AcgWebView.kt`、
    `BackgroundWebView.kt`、`AcgWebView.jvm.kt`、`NativeWebViewHostTest.kt`；
    现状描述保留（例：`webview.cpp` 文件头直接讲钩子接在 `add_WebResourceRequested` +
    `AddWebResourceRequestedFilter(L"*", ALL)` 上，不再解释"为什么不用 compose-webview"）。
  - 事实错误 18 处全部改掉：`ffmpeg/AvFormat.kt` 的"`AVPixelFormat` 只有 0..13"
    （本地 `libavutil/pixfmt.h` 里 ARGB=25、RGBA=26、BGRA=28 都是合法值，真正原因是
    通道序不同）、`quickjs/QuickJS.kt` 两处、`cxx/webview/webview.cpp` 六处、
    桌面 WebView 的 `AcgWebView.jvm.kt` / `NativeWebView.jvm.kt` 共三处、
    `SeekWindowSemanticsTest` 三处（含一条**恒真断言**已改成真比 `dir`）、
    构建与配置四处（`build.gradle.kts`、`.editorconfig`、`desktopApp/build.gradle.kts`、
    `cxx/quickjs/quickjs.cpp`）。
  - 其中一处**不只是注释错**：`QuickJS.Context.reuseWrapper` 漏了 `dup()`（复用的包装
    没给调用方记票 → 谁先 `free()` 谁就把别人的包装一起销毁）。已修，并同步订正 skill
    `quickjs-ownership` 的契约描述。
- **还没做的**：剩 **10 处「过度断言」**（方向大体不误，但有反例或绝对化，本轮未动）：
  `webview.cpp:36-38`（"事件回调一律不直接干活，唯一例外…"）、`:1293`（"必须不在 COM 回调里调"）、
  `:866`（"永远到不了宿主" → 准确说法是"落在**页面上**的到不了"）、`:847`（"唯一解"）、
  `:103`（"从模块加载算起" → 实为首次调用时起算）、`ffmpeg.cpp:209`（说 `opaque` 是
  "FFmpeg 保留" → 上游写明是给用户的）、`SeekWindowSemanticsTest.kt:26`（"整个丢弃" →
  上游首次失败会拿窗口重试一次）、`NativeWebViewHostTest.kt:28/:37/:347`（"三件/三个用例" →
  现有 5 个）、`:311`（"跨线程失败"）、`HttpSeekSemanticsTest.kt:10-15`
  （"没法在单测里构造真实响应" → 同仓已有真起 `HttpServer` 的用例）。
- **完成判据**：上面 10 处改完，A1 结案。
- **结案后必须做的收尾**（**动手前要先经用户明确确认**）：删掉 `AGENTS.md` **§4 末条**
  （"代码注释可能已过时甚至被证伪…读到先当线索、不当结论"）—— 一条长期成立的"别信注释"规则
  本身是坏味道，会训练 agent 忽略有用信号。
  用户 2026-09-16 晚明确：**注释现在还不完全可信，这条规则先留着**，等他确认后再删；
  2026-09-19 复查后同样建议留（还剩上面 10 处确凿偏差）。

## B. 已知缺陷，待修

### B3. 测试报错 / 日志存在非英文输出

- **现状**：未审计。Windows 控制台按代码页解码，中文输出会变乱码，误导排查。
- **完成判据**：`shared/src/jvmTest/` 下测试的输出全为英文（ASCII）；加一条约定性检查。

### B4. `commonMain` 里存在平台符号（违反根 AGENTS.md §4 的硬规则）

- **现状**：约定是 `commonMain` 不许出现 `java.*` / `android.*`。**7 个文件 12 处**
  （2026-09-19 复核；`QuickJS.kt` 的那次线程安全修复加了 `AtomicBoolean` / `AtomicInteger`
  两个 import，于是从 11 涨到 12）：`quickjs/QuickJS.kt`、
  `ffmpeg/{AvFrame,AvCodec,AvFormat,FFPlayer}.kt`、`acg/engine/JsEngine.kt` 直接 import `java.*`；
  另有 `acg/player/HttpIO.kt:52` 的内联 `catch (_: java.io.IOException)`。
  也就是说 `soko.ekibun.{quickjs,ffmpeg}` 事实上是按 JVM-only 写的。
- **方向已定**（用户 2026-09-16 晚）：**不给这两个包开例外** —— 规则保持，把这些 Java 语义
  逐处提到外面（`expect` 一个最小原语、两端各 `actual`），`commonMain` 里最终不剩平台符号。
- **为什么现在没做**：属于独立的一次重构，要和文档改动分开。
- **完成判据**：上述 12 处全部去掉；`commonMain` 里搜 `java\.` / `android\.` 结果均为 0。
- **完成后必须做的收尾**：删掉 `AGENTS.md` §4 里那句"现状…仍有直接引用…见 `TODO.md` B4"
  的指针（届时规则已无例外），并删掉本条。

### B6. `ViewOptions::script` 是死字段

- **现状**：`cxx/webview/webview.cpp` 的 `ViewOptions::script` 既没有赋值点也没有读取点
  （后台视图用的是 `View::script`，见 `nativeRun`；`configureView` 只读 `opt.url`）。
- **完成判据**：删掉它，或明确它要给谁用。

### B7. 桌面端 `allowNewWindow` 未接

- **现状**：`WebViewConfig.allowNewWindow` 只对 Android 生效（`setSupportMultipleWindows`）；
  桌面宿主的 `nativeCreateView` 没有这个参数，`ViewOptions::allowNewWindow` 恒为默认 `false`。
  用户 2026-09-19 拍板**两端都先取 `false`** —— 所以当前行为是对的，只是这个旋钮在桌面端是死的
  （`AcgWebView.kt` 的 KDoc 已注明）。
- **完成判据**：真要桌面端也能弹新窗口时，给 `nativeCreateView` 加参数打通；否则保持现状。

### B8. seek 后的丢帧收敛没有真路径回归测试

- **现状**：`FFPlayer` 的 `resyncTo` 丢帧收敛（ffplay `frame_drops_early` 的对应物）只有"判据副本"
  级别的测试 —— `SeekWindowSemanticsTest` 里的 `shouldDropEarly` 是手抄的纯函数，锁不住
  "判据接错了输入"。2026-09-21 修的就是这一类失效：判据读 `flushFrame` 的返回值，而视频路径
  **恒返回 -1**（VIDEO 分支只是送显这个副作用），于是**一帧都没丢过**；判据还排在送显之后，
  等于"先上屏、再决定丢不丢"。
- **第二天补的另一半**：收敛只做视频也不够。seek 落点是**容器级**的，音频同样会退到目标之前
  （实测 `ramp.mp4`：目标 5.0s 时 demuxer 给的首包是音频 3.90s / 视频关键帧 4.0s），
  而音频的 `flushFrame` 返回值会把主时钟按"真正上屏的时间戳"重新锚定 → 锚点落在目标之前，
  视频只能干等时钟爬上来（现象：**画面要等声音播到目标才出现**）。所以判据必须**对每条流**成立。
- **完成判据**：入库一个小测试媒体（h264 24fps、关键帧固定 2 秒间隔、整帧亮度随时间线性上升，
  约 120 KB），断言两件事：①"seek 到两个关键帧之间的目标后，送显的第一帧落在目标附近，
  而不是关键帧附近"；②"第一条真正上屏的音频时间戳 ≥ 目标"。判据不要读时钟（播放节流会污染），
  视频读送显像素的亮度 —— 标定见探针。
- **为什么现在没做**：往仓库里加二进制测试资源要用户点头。探针、媒体与生成命令已留在本机
  `.workbuddy/ref/seek-probe/`（`ZzSeekDropProbe.kt`、`ZzSeekDiagProbe.kt`、`ramp.mp4`、`ramp.ts`、
  `land-mp4.txt` / `land-ts.txt` 是 ffprobe 的落点实测；生成日志 `gen2.log` 已随临时目录清理）。
- **⚠️ 探针的已知局限**：探针里的假音频**模拟不了真声卡的实时消费**，所以"主时钟被音频拉回"
  这个现象在探针里复现不出来（实测：假音频下整个 12 秒的文件 100ms 内就被消费完，视频框框全过）。
  音频侧的时间戳只能从 `AvPlayback.flushFrame` 里那行 `println("PTS ...")`（落在
  `build/test-results/jvmTest/*.xml` 的 `<system-out>`）里看。**要真正验收 A/V 交互只能在真机上跑。**

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
  写进去的像素 ⇒ 那一帧显示成后面的帧。现在判据带上轮次号（`roundSeq == round`）。
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
  - **未解的一处**：全程 `decode_error_flags` 恒为 0（`DECODE_ERROR 行数 = 0`），而这个字段
    在 native 侧确实接通了（`cxx/ffmpeg/ffmpeg.cpp:226` 把 `frame->decode_error_flags` 传给了
    `AvFrame`）。若 libavcodec 真的报了 `FF_DECODE_ERROR_MISSING_REFERENCE`，这里应该非 0。
    ⇒ 要么 h264 的「参考帧不存在」这条路不置这个位，要么坏帧成因比「少一个参考帧」更复杂。
    **改完复测时顺手核对这一条**（修复若真有效，113 → 0 就足以定性；这一位仍为 0 则说明它
    对这个场景不敏感，别再拿它当判据）。
  ⇒ B10 原先「丢包不是残留的成因」（依据：native 那条 EAGAIN 通路没复现）**结论要改**：
  残留的形态（「长段总在关键帧处愈合」）与这条吞包完全吻合 —— 在 ramp 素材上每帧亮度只差 5，
  错参考解出来的样子就是「像早 1~2 帧的完整帧」（在真实素材 `test.mp4` 上则表现为**像素与全片
  任何一帧都不匹配**，见上面场景 D 的判据）。
- **已修（2026-09-22）**：`FFPlayer` 加了一个字段 `pendingPacket` —— 那条早退分支不再 `close()`，
  而是**把包留着**；读包循环开头**先喂留下的包**、再去 `getPacket`；`seekToRound` / `closeAsync`
  两处才真的归还（那两处它确实会被重读 / 不会再开新轮）。改法由用户给出并定调：**不「就地送解码」**
  —— 那是在「轮次已作废」的语境里去动解码器与 `frames`，多出一件说不清的事；留着、由下一轮按
  正常路径喂，包序与帧序都不变。
- **复测（同一探针、同一素材；三条编译闸门 + `:shared:jvmTest` 全绿）**：

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
     成因是读包循环吞包，与 native 那条 EAGAIN 通路无关；修法是 `pendingPacket`（留着包、
     下一轮开头再喂）。复测：D / F 的未命中 113 / 52 → **0**，F 的命中下标 `596..657` 连续。
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

## E. 结构性改进（需要先决策）

### E1. webview 的 JNI 绑定迁移

- **现状**：`NativeWebView.jvm.kt` 仍在 `soko.ekibun.acg.web`，与"所有 native 绑定统一收在
  `soko.ekibun.*`"的规则不符（`quickjs` / `ffmpeg` / `jni.kt` 已经在那儿）。
- **完成判据**：迁到 `soko.ekibun` 下。`AGENTS.md` 侧**不用再改** —— 原书写"去掉「待迁移」"，
  但该措辞已随 2026-09-16 的瘦身清掉（状态词计数已归零），§3 那句现在就是最终形态。

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
  / `collectAsState`。它会误导 agent 以为"项目选了 MVVM"。
- **完成判据**：确认确实不需要就删掉依赖；需要就先用起来再留。

---

*建立于 2026-09-16。条目来源：`AGENTS.md` / `cxx/AGENTS.md` / `.agents/skills/*/references/` 里的
「待修/待办/待补」标记、一次针对代码风格的取证调查、一次针对缩进与注释语言的实测扫描，
以及 `.workbuddy/memory/2026-09-16.md` 的记录。*
