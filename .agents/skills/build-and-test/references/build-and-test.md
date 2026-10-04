# 构建、运行与验收闸门

本文件是**构建与验收的正本**；根 [`AGENTS.md`](../../../../AGENTS.md) 只留"改完要过闸门"这一句与指针。

## JDK：三件互不相同的事，别混

| 用途 | 谁说了算 | 你要做什么 |
|---|---|---|
| **启动 `gradlew`** | 需要一个 `JAVA_HOME`，或 PATH 上的 `java` | 命令行下**必须自己给** —— `gradlew` 不会自己去找 JDK |
| **跑构建 / 测试**（Gradle daemon） | `gradle/gradle-daemon-jvm.properties` | **什么都不用做**，Gradle 自己备好那份，本地没有就下载 |
| **编 native 要的 `jni.h`** | 根 `CMakeLists.txt` 在 configure 期读 `$ENV{JAVA_HOME}` | 走 `:desktopApp:buildJni` 时 Gradle 自动传 toolchain JDK（带 `include/`），你不用给 |

## 跑

主路径是**在 Android Studio 里直接运行**（不受上表第一行影响）；命令行等价物：

```bash
JAVA_HOME=<任意一份 JDK> ./gradlew :desktopApp:run                          # 桌面（触发 :desktopApp:buildJni）
JAVA_HOME=<任意一份 JDK> ./gradlew :desktopApp:run -x :desktopApp:buildJni   # 跳过 native 重编、只跑 Java 侧
JAVA_HOME=<任意一份 JDK> ./gradlew :desktopApp:buildJni                      # 只编 native、不跑
JAVA_HOME=<任意一份 JDK> ./gradlew :androidApp:assembleDebug                 # Android debug 包
```

另外两件本机前提：**MSYS2**（只有编 native 用得到，见 [`dll-sync.md`](./dll-sync.md) 和
[`cxx/AGENTS.md`](../../../../cxx/AGENTS.md)）、**Android SDK**（`local.properties` 里的 `sdk.dir`，
本机私有、已忽略）。

**结果落盘再读**，别靠终端实时输出下判断：Windows 上 JVM 按控制台代码页编解码，与 UTF-8 的日志
对不上就是乱码。加 `--console=plain` 让输出变成可线性读的纯文本。

## 闸门：三条编译（**改代码时不跑**，提交前核对）

根 [`AGENTS.md`](../../../../AGENTS.md) §5 的规则：验收阶段**一条都不跑**，只跑定点 `jvmTest`；
这三条只在**用户确认提交时**跑一次。

**任务名别写错**：

```bash
./gradlew :shared:compileAndroidMain :shared:compileKotlinJvm :desktopApp:compileKotlin
#         注意不是 compileDebugKotlinAndroid
```

三条各自的覆盖面（`desktopApp` 只有一个 45 行的文件，另外两个才是大头）：

| 任务 | 编到哪 |
|---|---|
| `:shared:compileAndroidMain` | `shared/src/androidMain` + `commonMain` |
| `:shared:compileKotlinJvm` | `shared/src/jvmMain` + `commonMain` |
| `:desktopApp:compileKotlin` | `desktopApp/src` 自己（不含 shared） |

⚠️ `:androidApp` 自身的编译暂未纳入这道闸门 —— 改了 `androidApp/` 下的代码不会被这三条拦到。
⚠️ `commonMain` 的 `expect` / `actual` 是否两端都成立，只有**前两条一起跑**才查得出来；
只跑一条时，跨端签名错要等提交前才暴露。

## 格式化 / lint（**改代码时不跑**，提交前或用户明确要求时才跑）

根 [`AGENTS.md`](../../../../AGENTS.md) §5 的规则：验收阶段**不跑**下面任何一条。

```bash
./gradlew :shared:ktlintCheck --continue      # Kotlin（标准规则 + compose-rules 规则集）
./gradlew ktlintFormat --continue             # 同上，能自动修的它直接修掉
clang-format -i cxx/webview/webview.cpp       # C++；改哪个点哪个，就那三个文件
```

⚠️ **`ktlintFormat` 会改到本次改动之外的行** —— 拿它"顺手格式化"直接违反 §1 的「外科手术式改动」，
所以默认不跑。只想看自己这次写的行合不合规，就临时跑一次 `ktlintCheck --continue`
（它**只校验、不改文件**）。

规则正本是根目录的 `.editorconfig`（Kotlin）与 `.clang-format`（C++，Google 基线，只偏离
「行尾统一 LF」一处），两边的取值必须一致。`clang-format` **不在 PATH 上**，
本机那份的绝对路径见 `.workbuddy/memory/host-env.md`（本机专属、不入库）。

**执行点（git hook）**：`.githooks/pre-commit` 在 `git commit` 当场拦一次 ——
暂存区里有 `.kt` / `.kts` 就跑 `ktlintCheck --continue`，有 `cxx/{webview,quickjs,ffmpeg}/` 下的
源文件就跑 `clang-format --dry-run --Werror`；两类都没有就直接放行（改个 `.md` 不该等 Gradle）。
它**只校验、不改文件**。

装法（每个 clone 做一次；`.git/config` 是本机私有的，不入库）：

```bash
git config core.hooksPath .githooks
```

⚠️ 它**不会静默放行**：Kotlin 那半找不到 `JAVA_HOME` / `java`、C++ 那半找不到 clang-format
（可用 `ACG_CLANG_FORMAT` 指定，其次看 PATH 上的 `clang-format`）时，都会**明确报错并拒绝提交**。
真想放行一次用 `git commit --no-verify`。

⚠️ **hook 拿到的 PATH 可能是被裁剪过的**（2026-09-17 实测，Git for Windows 2.55，从非 MSYS 终端提交）：
git 传给 hook 的 PATH 里有 Git 的 `cmd/`（只有 git.exe），**没有 Git 自带的 `usr/bin/`** ——
`grep` / `sed` / `uname` / `xargs` / `cygpath` 全在那儿。两个后果都很难看：`gradlew` 自己就跑不起来
（`xargs` 缺失时它只吐一句 `xargs is not available`）；更糟的是**若用 `grep` 之类去筛暂存区文件名**，
`grep` 找不到 → 文件列表变空 → 脚本判定「没有要检查的文件」→ `exit 0` **静默放行**，
提交照常成功、其实什么都没查。所以 `.githooks/pre-commit` 现在：筛文件名只用 shell 内建（`case`），
并从 `git --exec-path` 推出 Git 自带的 `usr/bin` 补进 PATH（只补 `./gradlew` 那一条命令）。
另有一个 MSYS 的坑：**PATH 搜索不认 `C:/…` 这种 Windows 风格条目，只认 `/c/…` / `/usr/bin` 风格**，
而负责转换的 `cygpath` 恰好在那个还没进 PATH 的目录里 —— 归一化只能用内建的 `cd -P` + `pwd`。

验证 hook 不用真提交：**`git hook run pre-commit`** 走的就是 `git commit` 的那条调用路径。

⚠️ **执行位**：`.githooks/pre-commit` 在仓库里已按 `100755` 记录（Windows 上 `core.fileMode=false`，
直接 `git add` 出来的会是 `100644`，所以当初用 `git update-index --chmod=+x` 显式写进了索引）。
克隆下来一般就带着；若在 Linux / macOS 上发现丢了，`chmod +x .githooks/pre-commit` 一次 ——
git 对**没有执行位的 hook 是静默跳过**的。

> 2026-09-17 用过一版 GitHub Actions，按用户要求撤掉了：CI 要推到远端之后才跑，
> 拦不住「改动进仓库」这个动作。

⚠️ 看结果时要分清是哪个 task 挂的：`ktlintCheck` **按 sourceSet 拆成多个任务**，
不加 `--continue` 会在第一个失败的模块就中止 —— 那时报出来的违规数只覆盖了跑过的模块，
会被误读成"违规不多"。

⚠️ `.editorconfig` 的 `max_line_length` 现在是 **120**（不再是 `off`）—— 这会让 ktlint 的
**整套换行类规则**同时生效；改这个值预期会扩散到多处代码，细节见
[`coding-style/references/kotlin.md`](../../coding-style/references/kotlin.md) 的「官方未规定」一节。

## 测试

**默认只跑定点的**（根 [`AGENTS.md`](../../../../AGENTS.md) §5 的规则，理由见本节末尾）：

```bash
./gradlew :shared:jvmTest --tests "soko.ekibun.web.NativeWebViewHostTest" --console=plain
./gradlew :shared:jvmTest --tests "soko.ekibun.quickjs.QuickJSTest.objectWithVariousTagsRoundTrips"
```

全量只在**用户确认提交时**跑一次（§6）：

```bash
./gradlew :shared:jvmTest --console=plain --rerun
#   结果读 XML，不看控制台：
#   shared/build/test-results/jvmTest/TEST-*.xml  ->  tests= / skipped= / failures= / errors=
```

- **要重复跑（偶发问题、改过 native 之后复查）必须加 `--rerun`**。不加的话第 2 轮起任务是
  `UP-TO-DATE`，XML 还是上一轮的残留 —— 写着"跑了 12 轮"，实际只跑了 1 轮。
  写验收循环时把"任务真的执行了"也做成一条判据（`grep 'Task :shared:jvmTest UP-TO-DATE'`）。
- `jvmTestProcessResources` 会把 `cxx/build/bin` 里的 dll 拷进测试资源 →
  **跑 jvmTest 前 native 必须是编好的**（见 [`dll-sync.md`](./dll-sync.md)）。
- 偶发失败的处理见 skill `project-traps` 的
  [`silent-failures.md`](../../project-traps/references/silent-failures.md)：
  不要靠重跑掩盖，也不要改产品代码去迁就。
- 测试的报错与日志**全用英文（ASCII）**，避免 Windows 控制台代码页把中文变成乱码。

### 为什么默认不跑全量

jvmTest 里有 **11 个 `@Test(timeout = …)` 兜底**（`HttpRequestStreamingTest` 3、
`HttpStreamingTest` 3、`HttpAbortTest` 2、`RealHttpReadTest` 2、`HttpAbandonedSessionTest` 1，
上限 60 s ~ 180 s），另有一批 `withTimeout` 护栏（10 s ~ 60 s）。后果是：全量一轮慢；
失败时**分不清是超时兜底到点、还是真回归**，容易把结论带偏。

真回归的判据仍然是定点测试 —— 只跑改动相关的类 / 方法，失败必然与自己有关。

