# AGENTS.md

Kotlin Multiplatform + Compose Multiplatform 的 **Android / Desktop-JVM 双端**应用，外加三块自研 C++
native（`cxx/`：WebView2 宿主、QuickJS 桥、FFmpeg 解封装+解码+播放）。

本文件的话分两类，**冲突时处置方式不同**：**规则**（"不要…""必须…"）是刚性的，要改就改规则本身，
不要因为代码已经长成别的样子就绕过它；**事实**（任务名、路径、某个能力还在不在）只是对当前代码的描述，
与代码不符时**以代码 + 实跑为准**，并顺手把本文件改对。

**语言**：本文件、代码注释、提交信息用中文；标识符、路径、任务名、命令、报错与日志原文
**保持英文原样**，不要翻译或改写（会被当成"差不多"然后抄错）。本文件与仓库里被提交的文件只写
**要求**，不写本机路径这类因机而异的值。

---

## 1. 会静默失败的几件事

本项目最危险的一类问题**不报错、只失效**，排查时最容易误判成"代码没写对"。先过一眼这节：

- **改过 native 却用着旧 dll** → 不报错，只是参数错位。改 Kotlin 侧签名后，第一件事是确认 dll
  已刷新到运行位置。
- **漏拷 dll** → 症状是"改动没生效、连日志都没有"。dll 要落到三个位置（见 §5）。
- **改 `soko.ekibun.acg.engine` 下的类名** → `JsEngine` 按名字反射实例化它们、JS 侧按名字调用，
  改名/移动**不会报错**，只会让脚本静默失效。
- **在 `init.js` 里用 `import()` / `require()`** → 本桥的模块加载路径会把进程 `abort()`。
  能力必须内联。
- **偶发测试失败**：`QuickJSTest > objectWithVariousTagsRoundTrips` 是**真实竞态**，不是环境问题。
  重跑变绿 ≠ 修好；也不要改产品代码去迁就。
- **动了 submodule** → `cxx/ffmpeg/ffmpeg/`、`cxx/quickjs/quickjs/` 是上游源码树，改了会污染，
  且下次同步即丢。

---

## 2. 项目与边界

只有 **Android + Desktop JVM** 两个 target，**没有 iOS 支持** —— 不要新建 `iosMain`、不要写 iOS 的
`actual`。桌面端**只按 Windows 考虑**：native 宿主是 WebView2，没有别的实现（打包 DSL 里的
`Dmg` / `Deb` 只是模板默认值，不维护）。

- `cxx/` 是**原生源码树，不是 Gradle 模块** —— 别去找它的 build 文件。
- 依赖版本全部集中在 `gradle/libs.versions.toml`，**本文件不写任何版本号**。其中 `material3` 与
  `androidx-lifecycle` 是**刻意**钉在 alpha/beta 上的，不是笔误 —— 不要"顺手"降成稳定版。
- native 绑定统一收在 `soko.ekibun.*`（`quickjs` / `ffmpeg` / `jni.kt` 已经在那儿），
  **新增绑定一律放这里**；`soko.ekibun.acg.*` 是业务层（`web` / `player` / `engine` / `common` /
  `ui.screen`）。
- 注册 Gradle 任务用 `tasks.register<T>("name")`；`by tasks.registering(...)` 已弃用且**编译失败**。

| 找什么 | 去哪 |
|---|---|
| Android 入口 | `androidApp/src/main/kotlin/soko/ekibun/acg/MainActivity.kt` |
| 桌面入口 | `desktopApp/src/main/kotlin/soko/ekibun/acg/main.kt`（`mainClass = soko.ekibun.acg.MainKt`） |
| 共享 UI 入口 | `shared/src/commonMain/kotlin/soko/ekibun/acg/App.kt` |
| JS 引导脚本与模块 | `shared/src/commonMain/composeResources/files/js/` |
| 原生源码 | `cxx/{webview,quickjs,ffmpeg}/` |

`composeResources/files/js/` 下的 JS **会被自动打包进资源**，所以新增 JS 能力就放这里。

---

## 3. 构建与运行

跟 JDK 有关的有**三件互不相同的事**，别混在一起想：

| 用途 | 谁说了算 | 你要做什么 |
|---|---|---|
| **启动 `gradlew`** | 需要一个 `JAVA_HOME`，或 PATH 上的 `java` | 命令行下**必须自己给** —— `gradlew` 不会自己去找 JDK |
| **跑构建 / 测试**（Gradle daemon） | `gradle/gradle-daemon-jvm.properties` | **什么都不用做**，Gradle 自己备好那份，本地没有就下载 |
| **编 native 要的 `jni.h`** | 根 `CMakeLists.txt` 在 configure 期读 `$ENV{JAVA_HOME}` | 走 `:desktopApp:buildJni` 时 Gradle 自动传 toolchain JDK（带 `include/`），你不用给 |

主路径是**在 Android Studio 里直接运行**（不受表格第一行影响）；命令行等价物：

```bash
JAVA_HOME=<任意一份 JDK> ./gradlew :desktopApp:run              # 桌面（会触发 :desktopApp:buildJni）
JAVA_HOME=<任意一份 JDK> ./gradlew :androidApp:assembleDebug    # Android debug 包
```

另外两件本机前提：**MSYS2**（只有编 native 用得到，见 §5）、**Android SDK**（`local.properties`
里的 `sdk.dir`，本机私有、已忽略）。

**结果落盘再读**，别靠终端实时输出下判断：Windows 上 JVM 按控制台代码页编解码，与 UTF-8 的日志
对不上就是乱码。加 `--console=plain` 让输出变成可线性读的纯文本。

---

## 4. 构建闸门与测试

改完代码至少过这三条编译，**任务名别写错**：

```bash
./gradlew :shared:compileAndroidMain :shared:compileKotlinJvm :desktopApp:compileKotlin
#         注意不是 compileDebugKotlinAndroid
```

`:androidApp` 自身的编译暂未纳入这道闸门 —— 改了 `androidApp/` 下的代码不会被这三条拦到。

```bash
./gradlew :shared:jvmTest --console=plain
#   结果读 XML，不看控制台：
#   shared/build/test-results/jvmTest/TEST-*.xml  ->  tests= / skipped= / failures= / errors=

# 单个类 / 单个方法（路径就是真实包名）—— 只改了某一处、想快点验证时用
./gradlew :shared:jvmTest --tests "soko.ekibun.acg.web.NativeWebViewHostTest"
./gradlew :shared:jvmTest --tests "soko.ekibun.quickjs.QuickJSTest.objectWithVariousTagsRoundTrips"
```

- `jvmTestProcessResources` 会把 `cxx/build/bin` 里的 dll 拷进测试资源 →
  **跑 jvmTest 前 native 必须是编好的**。
- 偶发失败的处理见 §1：不要靠重跑掩盖，也不要改产品代码去迁就。
- 测试的报错与日志**全用英文（ASCII）**，避免 Windows 控制台代码页把中文变成乱码。

---

## 5. 原生构建与 dll

```bash
./gradlew :desktopApp:run                           # 跑桌面端；会经 processResources → buildJni 自动编 native
./gradlew :desktopApp:run -x :desktopApp:buildJni   # 跳过 native 重编、只跑 Java 侧（native 已编好时省时间）
./gradlew :desktopApp:buildJni                      # 只编 native、不跑
```

Gradle 这条路**开箱可用**：它自己进 MSYS2 的 login shell，并把 toolchain 那份带 `include/` 的 JDK
传给 `build.jni.sh`，所以不用你准备任何 JDK。唯一还要的本机私有值是环境变量 **`MSYS2_BIN`**
（`cxx/exec.cmd` 靠它进 MSYS2 的 bash；没设的话 exec.cmd 直接退出 1）。

native 编完后 dll 要落到**三处**（源都是 `cxx/build/bin/<name>.dll`）。标准是
**全靠 Gradle 自动拷、不要额外手动操作**：

```text
1. shared/build/processedResources/jvm/test/      ← jvmTestProcessResources 自动
2. desktopApp/build/resources/main/               ← :desktopApp:processResources 自动
3. desktopApp/build/run/main/classpath/classes/   ← 目前无任务写，改完 native 后需手动拷
```

第 3 处是目前唯一的缺口，漏掉就走 §1 那条静默症状。MSYS2 login shell 为什么工具链自足、
jni.h 怎么从 toolchain JDK 拿到、dll 三处的细节，见 [cxx/AGENTS.md](./cxx/AGENTS.md)。

---

## 6. 跨端与契约纪律

- `commonMain` 里**不许**出现 `java.*` / `android.*` / `System.currentTimeMillis` 之类的平台符号。
  需要平台能力就 `expect` 一个**最小原语**，两端各 `actual`；照抄现有粒度，别自创抽象层。
- **平台"要求" ≠ 平台"输出"**：`AvPlayback.audioFormat` / `videoFormat` 表示
  **平台要求 native 输出什么格式**，会传给 native 去配转码器 —— 所以两端各传不同格式是合法的
  （Android 用 `ENCODING_PCM_8BIT` 是有意为之）。改这里之前先确认方向。
- WebView 契约在 `soko.ekibun.acg.web`（Android 用 `android.webkit.WebView`，桌面走自研宿主）。
  **不要引入第三方 KMP WebView 库**，理由见 [cxx/AGENTS.md](./cxx/AGENTS.md)。
  后台页 JS 契约（对齐 BangumiPlugin `assets/modules/http.js`）：

  ```js
  await webview(url, header, script, onInterceptRequest)
  ```

- `soko.ekibun.acg.engine` 下的类名是对外契约（后果见 §1）。
- `cxx/ffmpeg/ffmpeg/`、`cxx/quickjs/quickjs/` 是 submodule，**不要动**；需要参考实现
  （如 `fftools/ffplay.c`）**直接读本地文件，不要联网下载**。
- 代码注释可能已过时甚至被证伪（历史上出现过"必须 jbr-11"这类错误结论）—— 读到先当线索、
  不当结论，去看调用点或跑一遍。

---

## 7. 边界与完成标准

**操作范围只在本仓库**：只改仓库目录及其子目录下的文件；仓库之外（用户级配置、`~/.workbuddy/`、
系统目录、别的仓库）一律不动 —— 需要时只读，不写、不建、不删。

**可以自主做完、不必中途问**：读代码与文档；改业务代码与文档；跑编译与 `:shared:jvmTest`；
修**由本次改动引起的**失败并重跑；把新踩的坑写回本文件、就近的 `cxx/AGENTS.md`
或 [`.agents/`](./.agents) 下对应的 skill；**未完成的工作不写进指令文件**，一律进
[TODO.md](./TODO.md)（文档体例见 [`.agents/README.md`](./.agents/README.md)）。

**必须先问**：`git commit` / `push`；删文件；改依赖版本；动 submodule；其他不可逆、
或影响面明显超出本次任务的操作。

**"做完"的定义**：§4 的三条编译过 + 与本次改动相关的 `jvmTest` 通过（失败属本次改动就自己修到过）；
**碰了 native 还要确认 dll 已落到运行位置**（§5）。
不要在第一轮实现完就停下来问"要不要 review" —— 先自查一遍再报。

---

## 8. 提交

- Co-author 署名固定，别自创 `WorkBuddy <noreply@...>` 之类：

  ```
  🤖 Generated with [CodeBuddy Code]

  Co-Authored-By: CodeBuddy Code
  ```

- 多行提交信息写进文件再 `git commit -F`（别用 heredoc 套双引号，`$` / 反引号会被提前展开）。

---

## 9. 按需查阅

参考资料与技能都在 [`.agents/`](./.agents/) 下，**按需读，不要预先全读**。
目录分工、**文档体例**（什么内容进指令文件、什么进 `TODO.md`）、与 WorkBuddy 的接线方式、
怎么加新技能：见 [`.agents/README.md`](./.agents/README.md)。
若宿主已把下面的技能加载进来，用技能名触发即可；否则直接读对应文件 —— **两条路径指向同一份文件**。

| 主题 | 什么时候看 | 去哪 |
|---|---|---|
| 未完成的工作、已知缺口 | 想动手修东西之前 | [TODO.md](./TODO.md) |
| Kotlin / Compose / C++ / Gradle 的写法 | 新增或改动上述代码时 | skill `coding-style` → [`.agents/skills/coding-style/SKILL.md`](./.agents/skills/coding-style/SKILL.md) |
| 原生构建、JNI 约定、各库硬约束、dll 三处细节 | 改 `cxx/` 或 native 绑定、native 行为不对时 | [cxx/AGENTS.md](./cxx/AGENTS.md) |
| WebView2 / Win32 窗口 / 键盘焦点 / 白屏与 GPU 崩溃循环 | 桌面端渲染、焦点、崩溃 | skill `webview2-windows` → [`.agents/skills/webview2-windows/SKILL.md`](./.agents/skills/webview2-windows/SKILL.md) |
| QuickJS 引用计数与所有权（`JSRef`、`dup`/`free`、泄漏转异常） | 动 `JS_FreeValue` / `JS_DupValue` 之前 | skill `quickjs-ownership` → [`.agents/skills/quickjs-ownership/SKILL.md`](./.agents/skills/quickjs-ownership/SKILL.md) |
| 项目历史踩坑清单 | 动不熟悉的模块前扫一眼 | skill `project-traps` → [`.agents/skills/project-traps/SKILL.md`](./.agents/skills/project-traps/SKILL.md) |
| 调试（崩溃 / 挂死 / 开日志） | 出了问题在排查时 | skill `debugging` → [`.agents/skills/debugging/SKILL.md`](./.agents/skills/debugging/SKILL.md) |
| 产品概述 | 想先了解这个项目是干什么的 | [README.md](./README.md) |
