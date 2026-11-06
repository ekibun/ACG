# 构建脚本约定

本文记录 Gradle 脚本里的承重约束：任务图、dll 落位、JVM 参数与打包目录形状。改这些脚本之前先读本文。

## 任务图

- `:desktopApp:buildJni` 是唯一的 native 构建入口（Exec，无 outputs，每次必跑）。它进
  `cxx/` 跑 `build.jni.sh`，产物落在 `cxx/build/bin/`。
- `:desktopApp:stageNativeLibs` 是「库从哪来」的唯一判定点，所有消费 native 库的任务都挂它：
  `:desktopApp:processResources`（IDE 直跑 MainKt 用）、`:desktopApp:syncNativeResources`（run 与
  打包共用）、`:shared:jvmTestProcessResources` 与 `:platform:jvmTestProcessResources`。
  它只在本地编译时挂 `buildJni`；从属 worktree 里不挂任何东西（库直接读主仓的），见下节。
- `:desktopApp:clangCheck` / `:desktopApp:clangFormat` 走 `cxx/format.jni.sh`，范围是 `cxx/` 下三个顶层 C++ 文件。
- `:shared:devToolsAgentJar` 从测试运行时类路径里只挑 `soko/ekibun/DevToolsAgent*` 打成 premain agent jar，
  清单写 `Premain-Class`。
- `:shared:debugPlay` 注入 `-javaagent` 与 `-Dacg.dev.*` 参数；`:desktopApp:hotRun` 跑正式应用，
  不再挂 agent。

## dll 落位

原生库（主仓的 `cxx/build/bin/`；从属 worktree 直接读它，不复制）出现在五个位置：

| 位置 | 供谁用 |
| --- | --- |
| `shared/build/processedResources/jvm/test/` | `:shared:jvmTest` |
| `platform/build/processedResources/jvm/test/` | `:platform:jvmTest` |
| `desktopApp/build/resources/main/` | IDE 直跑 MainKt |
| `desktopApp/build/compose/tmp/prepareAppResources/` | `run` 与打包 |
| `<app-image>/app/resources/` | 打包态 |

- `jar` 任务 `exclude("**/*.dll")`，原生库不进 jar。
- `syncNativeResources` 用 `Sync`（不是 `Copy`）：目标目录是来源目录的镜像，来源里删掉的库跟着消失。

## 原生库复用（worktree 只做桌面端）

worktree 的约定是「只改 Kotlin、UI 哪都能测」，所以只用主仓编好的桌面 dll；**Android 不走复用** ——
那边要 native 与打包，回主仓做。

- 识别：`git rev-parse --git-common-dir` 落在主仓的 `.git`、而 `--show-toplevel` 是别处，就是从属
  工作树，主仓根取 common dir 的上一级。主仓自己、非 git 仓库都不触发。判定只在根
  `build.gradle.kts` 里一次，结果经 `acgNativeBinDir` 交给各模块。
- 主仓 `<主仓根>/cxx/build/bin` 有 dll 就复用：根脚本把这个**目录**交给各模块，消费方
  （`processResources`、`syncNativeResources`、两处 `jvmTestProcessResources`）从它 `from()` 进
  各自的资源目录 —— worktree 里**不复制**一份 `cxx/build/bin`，`buildJni` 也不在任务图里。落位与
  加载顺序不变；主仓重编 dll 后，这里下次构建自动拿到新的。
- 主仓没编过桌面端时退回本地编译并告警（那种情况本来也编不动）。
- 复用方照旧能 `:desktopApp:run`、`hotRun`、打包：只是库的来源不同。
- Android：没有复用路径。worktree 里跑 `:androidApp:externalNativeBuildDebug` / `assembleDebug`
  会因缺 submodule 直接失败，这是预期 —— 打 APK 回主仓。（曾试过让 cmake 把预编译 `.so` 拷进
  自己的产物目录：AGP 9.2.1 在构建阶段会清掉那片输出，零 target 的工程没有东西再重建它，结果是
  `obj` 空、APK 悄悄缺库，所以不走。）

**为什么不校验两端是否是同一份 C**：这条通道的前提就是「开 worktree = 不动 `cxx/`」，由使用者
保证，脚本不做比对。代价要清楚 —— dll 的符号是**不带签名**的名称导出
（[`cxx/AGENTS.md`](../../cxx/AGENTS.md) 的「JNI」），所以在这个 worktree 里改 C 或改 `external fun`
的签名**不会报错**，只会拿着主仓的 dll 跑出参数错位。要改 C 就回主仓编 —— 那边编完，这个 worktree
下次构建自动拿到新的库。

## 打包目录形状

- `appResourcesRootDir` 必须经 `fileProvider(syncNativeResources.map { ... })` 挂上生产任务，
  打包任务才会自动依赖它。
- Compose 插件只读 `appResourcesRootDir` 下的 `common`、`/<os>`、`/<os>-<arch>` 三个子目录，
  并把内容**摊平**到 `<app-image>/app/resources/`。库必须落在 `<os>-<arch>/` 这一层，
  桌面端固定为 `windows-x64/`。
- 运行期经系统属性 `compose.application.resources.dir` 拿到该目录的绝对路径。
- `createDistributable` 读的 `appResourcesDir` 在插件里是 `@Internal`、不进 up-to-date 判据，
  所以要显式 `inputs.dir(nativeResourcesDir)`。

## JVM 参数

- `--enable-native-access=ALL-UNNAMED`：关闭 JDK 24+ 对 `System.load` 的 restricted-method 警告。
  classpath 应用对应匿名模块，取值固定为 `ALL-UNNAMED`。
- `-ea:soko.ekibun...`：打开本包（含子包）的 JVM 断言，用于 `Pointer.ptr` 的同线程断言等。
- `-XX:ErrorFile=<build>/hs_err_pid%p.log`：测试进程崩溃时的现场报告落到 `build/` 下。
- `-Dstdout.encoding=UTF-8` / `-Dstderr.encoding=UTF-8`：`devMediaServer` 的输出编码。
- `MSYS2_BIN` 由 `tasks.withType<JavaExec>` 显式透传：`JavaExec` 继承的是 Gradle daemon 的环境。
- 测试任务不注 `-Dacg.dataDir`，数据目录由各测试类的 `TestPaths` 注入。

## dev 媒体服务器

- `:shared:devMediaServer` 手动常驻启动（`DevServerKt`），工作目录为仓库根。
- `debugPlay` 链路把 agent 挂到验证窗口进程，传 `-javaagent`、`-Dacg.dev.server.port`、
  `-Dacg.dev.classpath`、`-Dacg.dev.pidfile`、`-Dacg.dev.logfile`、`-Dacg.dev.workdir`。
  服务器随窗口进程起落，窗口一关就收场。

## 检查范围

- 根 `build.gradle.kts` 的插件一律 `apply false`：否则同一个插件会在每个子项目各自的 classloader 里再加载一遍。
- ktlint 应用在 `allprojects` 上，覆盖含根项目在内的全部自有项目。
- ktlint 排除 `**/generated/**`。
- IDE 不索引 `cxx/ffmpeg/ffmpeg`、`cxx/quickjs/quickjs`、`cxx/build`。
