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
- **2026-09-19 又见一次，是另一种表现**（同一批用例、同一条命令，紧挨着跑两次结果相反）：
  第一次**进程级 abort** —— 先打印 `Object leaks:`，泄漏物是一个 `Promise { }`
  （`1a6ecbba6c8    1   0*  …  Promise {  }`），随即
  `Assertion failed: list_empty(&rt->gc_obj_list), file .../quickjs.c, line 2464`，
  `:shared:jvmTest` 以 exit 3 结束（"finished with non-zero exit value 3"），
  测试结果目录里只有 `InitJsTest` 的半份 XML（`tests=4 skip=1`）；
  第二次（`--rerun-tasks` 原样重跑）**59/59 全绿**。
  当次日志是临时件（`log/` 下），2026-09-19 已按该目录「用完清空」的约定删除；结论即上文。
  这个 assert 与 `init.js` 文件头记的那个**是同一个**（quickjs.c:2464）—— 只要进程里还有
  没释放的 JS 对象，`JS_FreeRuntime` 就直接 abort，不会报错。当次改动只有注释，可排除。
  另注意第一次跑时 `InitJsTest` 是 `tests=4 skip=1`，第二次是 `tests=5 skip=0`：
  **用例数会随环境浮动**，别拿单次 XML 当基线。
- **2026-09-19 下午（F4 注释统一那批）换了个崩点，命中率还很高**：本轮**只改注释**
  （已用 `.workbuddy/comment-audit/check-code-identical.py` 机械证明：10 个代码文件剥掉注释后
  代码骨架**逐 token 相同**，见该目录 `code-identical-check.txt`）。全量 `:shared:jvmTest`
  跑了 4 次 —— **崩 3 次、绿 1 次**。
  崩的形态与上面那条不同：不是 `JS_FreeRuntime` 的泄漏断言，而是
  `EXCEPTION_ACCESS_VIOLATION`；三次的故障帧 **pc 偏移各不相同**
  （`quickjs.dll+0x227c2` ×2、`+0x17dfb`），访问地址也从「读 `0x18`」变成「写 `0x8`」。
  但三次的 **Java 栈完全一致**：`WebviewJsTest.loadInit` → `JSInvokable.invoke` →
  `JSFunction.invoke` → `QuickJS$Context$JSValue.jsCall` → `QuickJS.jsToJava`，
  只是落在不同用例上（`interceptCallbackCanBeInvokedFromKotlin` /
  `interceptResultIsUnwrapped` / `nonJsonScriptResultIsReturnedAsIs`）。
  **决定性对照**：同一条命令单独跑 `--tests "soko.ekibun.acg.engine.WebviewJsTest"`
  是 **7/7 全绿**（含前两个"崩过"的用例）。
  ⇒ 指向**用例顺序 / 跨用例残留导致的堆破坏**（pc 每次都变、访问方向也变，是 use-after-free
  的典型特征），不是某个用例自身写错。
  证据原件（`log/` 下的门禁输出、`shared/hs_err_pid*.log`）都是临时件，
  2026-09-19 已清理；复现用上面那条全量命令即可，不需要其他材料。
- **一条线索（证据不足，别急着动）**：崩点路径 `jsToJava` 恰好经过 `f50f52a` 给
  `QuickJS.Context.reuseWrapper` 补 `dup()` 的那个复用分支。但**同一次提交之后**的全量测试
  当时是 59/59 绿的，所以不能据此断定是它引入的。要动先做对照实验（在 `f50f52a^` 上重复跑全量），
  **别直接把 `dup()` 撤掉**。

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

### B5. `JsEngine` 收到 JS 参数后不归还

- **现状**：`webviewAsync` 的 `header` / `onInterceptRequest`、`fetchAsync` 的 `options`
  各持一票 JS 引用，**都不归还**，每次调用因此在 `Context.refs` 上留 1~2 笔。
  `QuickJS.Context.reuseWrapper` 补上 `dup()` 之后，"由被调用方归还"本身已经安全，
  不还的唯一理由只剩 `onInterceptRequest` 要活到 WebView 任务结束。
- **为什么现在没做**：归还时机得放在 `async` 块的 `finally`，而 Deferred 被取消时那个回调
  可能还在飞 —— 是时序改动，与注释清扫分开做。
- **完成判据**：这三处按正确时机归还；`:shared:jvmTest` 全绿且没有新的 `reference leak` 报告。

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
