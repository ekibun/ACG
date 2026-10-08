# AGENTS.md

Kotlin Multiplatform + Compose Multiplatform 的 **Android / Desktop-JVM 双端**应用，外加三块自研 C++
native（`cxx/`：WebView2 宿主、QuickJS 桥、FFmpeg 解封装+解码+播放）。

本文件的话分两类，**冲突时处置方式不同**：**规则**（"不要…""必须…"）是刚性的，要改就改规则本身，
不要因为代码已经长成别的样子就绕过它；**事实**（任务名、路径、某个能力还在不在）只是对当前代码的描述，
与代码不符时**以代码 + 实跑为准**，并顺手把本文件改对。

**语言**：本文件、代码注释、提交信息用中文；标识符、路径、任务名、命令、报错与日志原文
**保持英文原样**，不要翻译或改写（会被当成"差不多"然后抄错）。本文件与仓库里被提交的文件只写
**要求**，不写本机路径这类因机而异的值。

**本文件只放规则与陷阱，不放操作手册**：命令、步骤、清单一律在
[`.agents/`](./.agents/) 下的技能里（索引见 §7），此处只留"要做什么"加一个指针。

---

## 1. 行为准则

通用工程准则，与后面各节的项目规则**叠加**生效。体例学自
[multica-ai/andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills) 的 CLAUDE.md ——
每条一句底线，后接能自查的清单。

**想清楚再动手** —— 不许假设，不许含糊过去，把取舍摆到桌面上。
先说出你的假设，不确定就问；一个需求有多种解释就全列出来让人选，不要默默挑一个；
有更简单的做法就说，值得反驳就反驳；卡住就停，**说清哪里不清楚**再问。
"必须停下来问"的项目硬边界见 §5。

**最简实现** —— 能解决问题的最小代码，多一行都不要。
不做没要求的功能与"可配置性"；不为只用一次的代码造抽象；不给不可能发生的场景写错误处理；
写了 200 行而 50 行够用，就重写成 50 行。
自查一句话：**资深工程师会不会说这过度设计了？** 会就砍。

**外科手术式改动** —— 只碰必须碰的，只收拾自己弄乱的地方。
不"顺手改进"旁边的代码、注释、格式；不重构没坏的；发现无关的死代码只**提一句**、不要删；
风格照抄现状，哪怕你会写得不一样；只清理**自己的改动**造成的孤儿（没用了的 import / 变量 / 函数）。
验收：**每一行改动都能直接追溯回本次需求。**

**注释讲现状，不讲历程** —— 直接描述当前设计、职责、行为与约束；不用「不再…」「改为…」
等措辞叙述开发过程，也不记录未上线方案、被纠正的假设或对话历史（照
[animeko AGENTS.md](https://github.com/animeko-org/animeko/blob/main/AGENTS.md)）。
⚠️ 也不写「对齐某某项目 / 照某某的形态」—— 要对齐的是**技术目标**（时间戳、UA、沉浸效果），
不是来源仓库；未跟踪的参照目录在别人的 clone 里不存在，指向它的注释等于指向一个找不到的东西。
⚠️ 但**契约签名要留**（如 `webview(url, header, script, onInterceptRequest)`）——
那是"该实现什么形状"，属代码事实；「已删除 + 出处」的防回归警告同理。
**注释不写「代码在做什么」的复述** —— 判据是**删掉这行注释，读者的理解有没有变化**；
没变化就是在翻译代码，删（`/** 写播放倍速。 */` 配 `fun setSpeedRatio` 是典型）。
「这是什么」指的是**契约与职责**，不是把标识符翻成中文。
**用 import，不写全限定名**（同 animeko）—— `extensions.configure<KtlintExtension>` 而不是
`extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension>`。
**源码正文不用 emoji 符号** —— `.kt` / `.kts` 的注释里不写 `⚠️` / `✅` / `❌` / `📌` / `💡`。
animeko 2913 个 kt/kts、60866 行注释里含 emoji 的只有 30 行（0.05%），且全是**测试数据里的字符串**，
不是注释标记；它的 `⚠️` 只出现在 3 个设计文档 `.md` 里，是文档的状态标记。
⚠️ **这条管的是「源码」**：`.md` 文档里的 `⚠️` 不受此限（`comments.md` 有明确例外——
「已删除 + 出处」的防回归说明是刻意留的警告）。符号只是标记，**约束本身照写**，
删符号别删句子；约束描述一大段机制、或带实测数据时，正文留一句裸约束 + 指到 `.agents/` 下
对应文档的哪一节。
承重的历史知识抽进 [`.agents/`](./.agents) 下对应 skill 的 `references/`，代码里只留指针；
写法细则见 skill `coding-style`。

**注释太长时搬、不删** —— 判据是「这段在讲**约束**，还是在讲**机制**？」：带实测数据
（探针名、样本表、ffmpeg 源码行号）或在推演「为什么长成这样」的段落 ⇒ 搬进 `.agents/` 下对应
`references/`，正文留一句裸约束 + 指到哪一节；约束就贴在那个函数旁边 ⇒ 留裸约束句、删推演过程。
**约束本身一个字都不能少**，搬走的是推演过程。别拿「注释行 / 代码行」的比例当判据 ——
分母是代码行，小文件密度天然冲高（animeko 自己就有 92% 的小文件超 35%）。落点表见
`comments.md` 第六节。量测口径与它本身的三个坑见 `comments.md` 第六节。

**目标驱动执行** —— 先定可验证的成功标准，再循环到它通过。
把需求翻译成可验证的目标："加校验" → 先写非法输入的测试、再让它过；"修 bug" → 先写复现它的测试。
多步任务先给一个短计划，每步都带验证方式：

```text
1. [做什么] → verify: [怎么查]
2. [做什么] → verify: [怎么查]
```

标准够硬就能自己往下走；标准糊（"能跑就行"）就得不停来回问。

**别在乱码上做判断** —— 终端、日志、脚本输出里的中文乱码**不报错**，只会让人拿着一份错的文本去改代码、
写文档。看到乱码**先把编码改成 UTF-8**（该设控制台代码页就设、该指定输出编码就指定、结果一律落盘再读），
改对了再看；**不要将就着读**。

**这节是否在生效**：diff 里无关改动变少、不因过度设计返工、澄清性的问题出现在动手之前而不是踩坑之后、
没有一条结论建立在乱码文本上。

---

## 2. 不报错、只失效

本项目最危险的一类问题**不报错、只失效**，排查时最容易误判成"代码没写对"。逐条症状、涉及模块与
处置全在 [`silent-failures.md`](./.agents/skills/project-traps/references/silent-failures.md)，
**动到下列任一区域之前先读它**：

- 改过 native 却用着旧 dll（重编与落位已全自动，见 [`cxx/AGENTS.md`](./cxx/AGENTS.md)「原生构建」）
- 改 `soko.ekibun.acg.engine` 下的类名
- 在 `init.js` 里用 `import()` / `require()`
- 动了 submodule（规则见 §4）
- 在 JS 线程之外碰 `JSRuntime`（症状是偶发崩、重跑就变绿）
- 改 native 绑定的声明落点（类体 / `companion object`）、或动 `AvFormat.initNative`
  （详解见 [cxx/AGENTS.md](./cxx/AGENTS.md) 的 JNI 一节）
- 动 `FFPlayer` 的帧队列与帧归还（`frames` / `processing` / `invokeOnCompletion`）

---

## 3. 项目与边界

只有 **Android + Desktop JVM** 两个 target，**没有 iOS 支持** —— 不要新建 `iosMain`、不要写 iOS 的
`actual`。桌面端**只按 Windows 考虑**：native 宿主是 WebView2，没有别的实现（打包 DSL 里的
`Dmg` / `Deb` 只是模板默认值，不维护）。

- `cxx/` 是**原生源码树，不是 Gradle 模块** —— 别去找它的 build 文件。
- 依赖版本全部集中在 `gradle/libs.versions.toml`，**本文件不写任何版本号**。
- 平台层收在 **`:platform` 模块**，住 `soko.ekibun.{jni,quickjs,ffmpeg,web,common}`：
  各包的 JNI 面、webview 契约与两端实现、播放器视图（`ffmpeg` 包的 `SurfaceContext` /
  `VideoSurface`）、IO 原语（`common` 包的 `File` / `Http` 与 ktor 引擎）——**新增绑定、
  平台视图集成与平台原语一律放这里**；`:shared` 的生产源码只剩 **commonMain**，住
  `soko.ekibun.acg.*`（`engine`、`player` 的 `FileIO`/`HttpIO`、`ui.screen`、`ui.comp`
  其余组件）。`ui.screen` 放**整页**、`ui.comp` 放**跨页复用的组件**。包名即模块边界：
  `soko.ekibun.*` 在 `:platform`，`soko.ekibun.acg.*` 在 `:shared`。
- 注册 Gradle 任务用 `tasks.register<T>("name")`；`by tasks.registering(...)` 已弃用且**编译失败**。

| 找什么 | 去哪 |
|---|---|
| Android 入口 | `androidApp/src/main/kotlin/soko/ekibun/acg/MainActivity.kt` |
| 桌面入口 | `desktopApp/src/main/kotlin/soko/ekibun/acg/main.kt`（`mainClass = soko.ekibun.acg.MainKt`） |
| 共享 UI 入口 | `shared/src/commonMain/kotlin/soko/ekibun/acg/App.kt` |
| JS 引导脚本与模块 | `shared/src/commonMain/composeResources/files/js/` |
| 原生源码 | `cxx/{webview,quickjs,ffmpeg}/` |
| 平台层（native 绑定 + web / player 视图 / IO 原语） | `platform/src/**` |

`composeResources/files/js/` 下的 JS **会被自动打包进资源**，所以新增 JS 能力就放这里。

---

## 4. 跨端与契约纪律

- `commonMain` 里**不许**出现 `java.*` / `android.*` / `System.currentTimeMillis` 之类的平台符号。
  需要平台能力就 `expect` 一个**最小原语**，两端各 `actual`；照抄现有粒度，别自创抽象层。
  这条**不打折**：判据是 import 与代码级符号（注释里提到平台类名不算）。
- **平台"要求" ≠ 平台"输出"**：`AvSurfaceContext.audioFormat` 表示
  **平台要求 native 输出什么采样格式**，会传给 native 去配转码器 —— 所以两端各传不同格式是合法的
  （Android 用 `ENCODING_PCM_8BIT` 是有意为之）。改这里之前先确认方向。
  **视频没有这个自由度**：转码目标在 native 侧写死 `AV_PIX_FMT_RGBA`，Kotlin 侧已无像素格式
  常量与 `videoFormat` 参数（见 [cxx/AGENTS.md](./cxx/AGENTS.md) 的 ffmpeg 一节）。
- WebView 契约在 `soko.ekibun.web`（Android 用 `android.webkit.WebView`，桌面走自研宿主）。
  **不要引入第三方 KMP WebView 库**，理由见 [cxx/AGENTS.md](./cxx/AGENTS.md)。
  后台页 JS 契约（对齐 BangumiPlugin `assets/modules/http.js`）：

  ```js
  await webview(url, header, script, onInterceptRequest)
  ```

- `soko.ekibun.acg.engine` 下的类名是对外契约（后果见 §2）。
- `cxx/ffmpeg/ffmpeg/`、`cxx/quickjs/quickjs/` 是 submodule，**不要动**；需要参考实现
  （如 `fftools/ffplay.c`）**直接读本地文件，不要联网下载**。

---

## 5. 边界与完成标准

**操作范围只在本仓库**：只改仓库目录及其子目录下的文件；仓库之外（用户级配置、`~/.workbuddy/`、
系统目录、别的仓库）一律不动 —— 需要时只读，不写、不建、不删。

**可以自主做完、不必中途问**：读代码与文档；改业务代码与文档；跑编译闸门、**只跑与本次改动相关的定点测试**；
修**由本次改动引起的**失败并重跑；把新踩的坑写回本文件、就近的 `cxx/AGENTS.md`
或 [`.agents/`](./.agents) 下对应的 skill；**未完成的工作不写进指令文件**，一律进
[TODO.md](./TODO.md)（文档体例见 [`.agents/README.md`](./.agents/README.md)）。

**必须先问**：`git commit` / `push`；删文件；改依赖版本；动 submodule；其他不可逆、
或影响面明显超出本次任务的操作。

**编译、测试、lint 三样默认都不跑**：这三样只在**用户确认提交时**跑一次（见 §6），或用户**明确要求**时跑；
改代码的验收阶段只跑**定点测试**——跑得通就说明改动没影响到别处，编译一致性不靠它证明。

- **测试跑定点的**：只跑**与本次改动直接相关**的测试（平台层用例在 `:platform:jvmTest`、
  其余在 `:shared:jvmTest`，`--tests "soko.ekibun.<包>.<类>"`，必要时再窄到方法）；
  **不跑全量 `:shared:jvmTest`**。
  理由是本工程的测试里有大量**超时兜底**（`@Test(timeout = …)` 加 `withTimeout` 护栏，见
  [`.agents/skills/build-and-test/references/build-and-test.md`](./.agents/skills/build-and-test/references/build-and-test.md)
  的「测试」一节）—— 全量跑一遍慢，且失败时**分不清是超时到点还是真回归**，容易把结论带偏。
- **编译闸门不跑**：四条编译（`build-and-test` 里的闸门）一条都不跑。定点 `jvmTest` 自身会编到
  jvm 侧，够挡住"改坏了自己写的那处"；跨端一致性与 Android 侧的编译错误**留给提交前那次**。
- **lint 不跑**：`ktlintCheck` / `ktlintFormat` / `clang-format --dry-run` 一律不跑。
  尤其别用 `ktlintFormat` 顺手格式化 —— 它会改到本次改动之外的行，直接违反 §1 的「外科手术式改动」。

**"做完"的定义**：**与本次改动相关的定点 `jvmTest`** 通过（失败属本次改动就自己修到过）；
**碰了 native 还要确认 dll 已落到运行位置**。
不要在第一轮实现完就停下来问"要不要 review" —— 先按 §1 自查一遍再报。

---

## 6. 提交

- **提交前把三样全核对一遍**：用户确认提交时，依次跑
  **四条编译闸门**（`./gradlew :shared:compileAndroidMain :shared:compileKotlinJvm
  :desktopApp:compileKotlin :androidApp:compileDebugKotlin`；前三条里的 `:shared` 注意不是
  `compileDebugKotlinAndroid`，第四条恰恰就叫它）、
  **全量测试**（`./gradlew :shared:jvmTest :platform:jvmTest --console=plain --rerun`，结果读
  `shared/build/test-results/jvmTest/TEST-*.xml` 与 `platform/build/test-results/jvmTest/TEST-*.xml`）、
  **lint**
  （`./gradlew :shared:ktlintCheck :platform:ktlintCheck --continue` 与 `clang-format --dry-run --Werror`）、
  把结论一并报出来再提交。超时兜底导致的失败按 skill `project-traps` 的
  [`silent-failures.md`](./.agents/skills/project-traps/references/silent-failures.md) 甄别，别靠重跑掩盖。
  （`.githooks/pre-commit` 当场就会拦一遍 lint，所以那两次通常是空跑。）
- Co-author 署名固定，别自创 `WorkBuddy <noreply@...>` 之类：

  ```
  🤖 Generated with [CodeBuddy Code]

  Co-Authored-By: CodeBuddy Code
  ```

- 多行提交信息写进文件再 `git commit -F`（别用 heredoc 套双引号，`$` / 反引号会被提前展开）。

---

## 7. 按需查阅

参考资料与技能都在 [`.agents/`](./.agents/) 下，**按需读，不要预先全读**；目录分工、文档体例、
与宿主的接线方式、怎么加新技能，见 [`.agents/README.md`](./.agents/README.md)。

下表是**唯一的技能索引**。宿主若已把技能加载进来，用技能名触发；否则直接读对应文件 ——
**两条路径指向同一份文件**。

| 主题 | 什么时候看 | 去哪 |
|---|---|---|
| 未完成的工作、已知缺口 | 想动手修东西之前 | [TODO.md](./TODO.md) |
| 跑构建 / 打包 / 测试、判断"做完没有" | 改完代码要验收、或要跑起来时 | skill `build-and-test` → [`.agents/skills/build-and-test/SKILL.md`](./.agents/skills/build-and-test/SKILL.md) |
| native 重编与 dll 落位 | 改过 `cxx/` 之后 | [`cxx/AGENTS.md`](./cxx/AGENTS.md) 的「原生构建」 |
| Kotlin / Compose / C++ / Gradle 的写法 | 新增或改动上述代码时 | skill `coding-style` → [`.agents/skills/coding-style/SKILL.md`](./.agents/skills/coding-style/SKILL.md) |
| 原生构建细节、JNI 约定、各库硬约束 | 改 `cxx/` 或 native 绑定、native 行为不对时 | [cxx/AGENTS.md](./cxx/AGENTS.md) |
| WebView2 / Win32 窗口 / 键盘焦点 / 白屏与 GPU 崩溃循环 | 桌面端渲染、焦点、崩溃 | skill `webview2-windows` → [`.agents/skills/webview2-windows/SKILL.md`](./.agents/skills/webview2-windows/SKILL.md) |
| QuickJS 引用计数与所有权（`JSRef`、`dup`/`free`、泄漏转异常） | 动 `JS_FreeValue` / `JS_DupValue` 之前 | skill `quickjs-ownership` → [`.agents/skills/quickjs-ownership/SKILL.md`](./.agents/skills/quickjs-ownership/SKILL.md) |
| 历史踩坑清单、"不报错只失效"的那一类 | 动不熟悉的模块前扫一眼 | skill `project-traps` → [`.agents/skills/project-traps/SKILL.md`](./.agents/skills/project-traps/SKILL.md) |
| 调试（崩溃 / 挂死 / 开日志） | 出了问题在排查时 | skill `debugging` → [`.agents/skills/debugging/SKILL.md`](./.agents/skills/debugging/SKILL.md) |
| 产品概述 | 想先了解这个项目是干什么的 | [README.md](./README.md) |
