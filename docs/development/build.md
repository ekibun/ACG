# 构建脚本约定

本文记录 Gradle 脚本里的承重约束：任务图、dll 落位、JVM 参数与打包目录形状。改这些脚本之前先读本文。

## 任务图

- `:desktopApp:buildJni` 是唯一的 native 构建入口（Exec，无 outputs，每次必跑）。它进
  `cxx/` 跑 `build.jni.sh`，产物落在 `cxx/build/bin/`。
- 所有消费 native 库的任务都挂 `dependsOn(":desktopApp:buildJni")`：
  - `:desktopApp:processResources`（IDE 直跑 MainKt 用）
  - `:desktopApp:syncNativeResources`（run 与打包共用）
  - `:shared:jvmTestProcessResources` 与 `:platform:jvmTestProcessResources`
- `:desktopApp:clangCheck` / `:desktopApp:clangFormat` 走 `cxx/format.jni.sh`，范围是 `cxx/` 下三个顶层 C++ 文件。
- `:shared:devToolsAgentJar` 从测试运行时类路径里只挑 `soko/ekibun/DevToolsAgent*` 打成 premain agent jar，
  清单写 `Premain-Class`。
- `:shared:hotRun` / `hotRunAsync` 由 `:shared` 侧跨项目配置（`evaluationDependsOn(":desktopApp")`），
  注入 `-javaagent` 与 `-Dacg.dev.*` 参数。

## dll 落位

`cxx/build/bin/` 下的原生库出现在五个位置：

| 位置 | 供谁用 |
| --- | --- |
| `shared/build/processedResources/jvm/test/` | `:shared:jvmTest` |
| `platform/build/processedResources/jvm/test/` | `:platform:jvmTest` |
| `desktopApp/build/resources/main/` | IDE 直跑 MainKt |
| `desktopApp/build/compose/tmp/prepareAppResources/` | `run` 与打包 |
| `<app-image>/app/resources/` | 打包态 |

- `jar` 任务 `exclude("**/*.dll")`，原生库不进 jar。
- `syncNativeResources` 用 `Sync`（不是 `Copy`）：目标目录是 `cxx/build/bin` 的镜像，native 侧删掉的库跟着消失。

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
- `hotRun` 链路把 agent 挂到应用进程，传 `-javaagent`、`-Dacg.dev.server.port`、
  `-Dacg.dev.classpath`、`-Dacg.dev.pidfile`、`-Dacg.dev.logfile`、`-Dacg.dev.workdir`，
  可选 `-Dacg.dev.media`。

## 检查范围

- ktlint 应用在 `allprojects` 上，覆盖含根项目在内的全部自有项目。
- ktlint 排除 `**/generated/**`。
- IDE 不索引 `cxx/ffmpeg/ffmpeg`、`cxx/quickjs/quickjs`、`cxx/build`。
