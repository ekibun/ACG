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

- **现状**：代码里大量注释是 AI 写的且已被证伪。已知样本：
  `必须 jbr-11`、`Tao 后端是唯一选择`、`HttpIO.offset += ret 是重复累加`、
  `RequestInterceptor 拦不到子资源` 相关的反编译结论，以及
  `ffmpeg/AvFormat.kt:26` 的"`AVPixelFormat` 只有 0..13"（本地 `libavutil/pixfmt.h` 里
  25 = `AV_PIX_FMT_ARGB` 是合法值，真正原因是通道序不同；2026-09-16 复核）。
- **为什么现在没做**：属于独立的一次清扫，与文档改动不能混在一起。
- **完成判据**：上述样本逐处修正或删除；全仓复查一遍同类注释。
- **完成后必须做的收尾**（**动手前要先经用户明确确认**）：删掉 `AGENTS.md` **§4 末条**
  （"代码注释可能已过时甚至被证伪…读到先当线索、不当结论"）—— 一条长期成立的"别信注释"规则
  本身是坏味道，会训练 agent 忽略有用信号。
  用户 2026-09-16 晚明确：**注释现在还不完全可信，这条规则先留着**，等他确认后再删。

## B. 已知缺陷，待修

### B1. dll 落位第 3 处仍要手动拷

- **现状**：`desktopApp/build/run/main/classpath/classes/` **没有任何 Gradle 任务写**。
  第 1 处（`shared/build/processedResources/jvm/test/`，`jvmTestProcessResources`）与
  第 2 处（`desktopApp/build/resources/main/`，`:desktopApp:processResources`）已自动。
- **为什么现在没做**：补任务本身是代码改动，本轮只改文档。
- **完成判据**：补一个 `dependsOn(:desktopApp:buildJni)` 的 copy 任务，把第 3 处并进 Gradle 工具链；
  之后 skill `build-and-test` 的 `references/dll-sync.md` 与该条一起简化为"三处全自动"。
- **影响**：漏拷的症状是"改动没生效、连日志都没有"，最容易被误判成代码问题。

### B2. `QuickJSTest > objectWithVariousTagsRoundTrips` 偶发失败

- **现状**：疑似多线程/时序竞态，怀疑桥接层 per-context 线程安全（具体竞态未定位）。
- **为什么现在没做**：需要先定位真因，不能用改产品代码去迁就的方式打补丁。
- **完成判据**：定位到具体竞态并修掉；连续多次全量 `:shared:jvmTest` 稳定绿。
- **注意**：重跑变绿**不等于**修好。

### B3. 测试报错 / 日志存在非英文输出

- **现状**：未审计。Windows 控制台按代码页解码，中文输出会变乱码，误导排查。
- **完成判据**：`shared/src/jvmTest/` 下测试的输出全为英文（ASCII）；加一条约定性检查。

### B4. `commonMain` 里存在平台符号（违反根 AGENTS.md §4 的硬规则）

- **现状**：约定是 `commonMain` 不许出现 `java.*` / `android.*`。**7 个文件 11 处**（2026-09-16 复核）：
  `quickjs/QuickJS.kt`、`ffmpeg/{AvFrame,AvCodec,AvFormat,FFPlayer}.kt`、`acg/engine/JsEngine.kt`
  直接 import `java.*`；另有 `QuickJS.kt:327` 的内联 `java.util.concurrent.atomic.AtomicBoolean`
  与 `acg/player/HttpIO.kt:47` 的内联 `catch (_: java.io.IOException)`。
  也就是说 `soko.ekibun.{quickjs,ffmpeg}` 事实上是按 JVM-only 写的。
- **方向已定**（用户 2026-09-16 晚）：**不给这两个包开例外** —— 规则保持，把这些 Java 语义
  逐处提到外面（`expect` 一个最小原语、两端各 `actual`），`commonMain` 里最终不剩平台符号。
- **为什么现在没做**：属于独立的一次重构，要和文档改动分开。
- **完成判据**：上述 11 处全部去掉；`commonMain` 里搜 `java\.` / `android\.` 结果均为 0。
- **完成后必须做的收尾**：删掉 `AGENTS.md` §4 里那句"现状…仍有直接引用…见 `TODO.md` B4"
  的指针（届时规则已无例外），并删掉本条。

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

## F. 风格基线的落地（`.agents/skills/coding-style/`）

### F1. 三处「官方未规定」的默认值待拍板

- **现状**：`references/kotlin.md` 末尾给了三条**建议值**，但那是建议、不是规定：
  通配符 import 不用 / 行长软上限 120 字符 / 调用处多行参数一律加尾随逗号。
- **完成判据**：拍板后把「建议」改成「本项目定」，并删掉这一条 todo。

### F2. 引入格式化与静态检查配置（缩进基准 = 2 空格）

- **现状**：仓库里**没有** `.editorconfig` / ktlint / detekt，也没有任何 lint 插件，风格只能靠人守。
- **为什么现在没做**：引入了就会产生大量存量 diff，得单独安排一次提交。
- **完成判据**：加 `.editorconfig`（**`indent_size = 2`**、`indent_style = space`、UTF-8、LF），
  并选定 ktlint 或 detekt 在提交前 / CI 跑。

### F3. 存量缩进统一到 2 空格

- **现状**（实测扫描，已排除 submodule 与 `cxx/webview/sdk/`）：59 个自有源文件里
  **34 个是 2 空格、24 个是 4 空格、1 个无缩进**。4 空格那批：根 `build.gradle.kts` 与
  `settings.gradle.kts`、`androidApp/{build.gradle.kts,MainActivity.kt}`、
  `desktopApp/{build.gradle.kts,main.kt}`、`shared/build.gradle.kts`、`App.kt`、
  `ui/screen/{CodeScreen,PlayScreen,WebScreen}.kt`、
  `web/{AcgWebView,BackgroundWebView}.kt` 与它们的 `.android.kt` / `.jvm.kt`、
  `web/NativeWebView.jvm.kt`、`jni.android.kt` / `jni.jvm.kt`、
  `common/{Http.android.kt,Http.jvm.kt}`、`NativeWebViewHostTest.kt`、`cxx/webview/webview.cpp`。
- **为什么现在没做**：纯格式化会产生上万行 diff，必须**独立提交**，不能混进功能改动。
- **完成判据**：范围内块缩进基准全部为 2 空格（续行仍为 4），改动只含空白；
  完成后同步改 `SKILL.md`「缩进」一节里的存量那句话。

### F4. 存量注释统一到中文 + 统一格式

- **现状**（同一份扫描）：自有代码注释行**含中文 1519、纯 ASCII 788**
  （含 `*` / `*/` 这类块结构行，会略微高估 ASCII 一侧）。分布：`.kt` 982 / 480、
  `.cpp` 515 / 302、`.kts` 22 / 6。最脏的是 `cxx/quickjs/quickjs.cpp`（2 中文 / 102 ASCII，近乎全英文）；
  反向样本是 `cxx/webview/webview.cpp`（499 / 190，以中文为主）。
- **为什么现在没做**：全仓翻译注释是一次独立清扫，且与 A1（清理**错误**注释）容易互相掩盖。
- **完成判据**：范围内注释一律中文（标识符、术语、报错与日志原文保持英文），形式按
  `.agents/skills/coding-style/references/comments.md`。
- **建议与 A1 合并成同一次清扫**：先按 A1 删掉被证伪的注释，再按本条把剩下的译成中文并统一形式。

---

*建立于 2026-09-16。条目来源：`AGENTS.md` / `cxx/AGENTS.md` / `.agents/skills/*/references/` 里的
「待修/待办/待补」标记、一次针对代码风格的取证调查、一次针对缩进与注释语言的实测扫描，
以及 `.workbuddy/memory/2026-09-16.md` 的记录。*
