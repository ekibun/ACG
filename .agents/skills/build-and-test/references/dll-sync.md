# native：重编与 dll 同步

本文件是 **dll 落位的正本**；根 [`AGENTS.md`](../../../../AGENTS.md) 与
[`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 都只留指针，不要另抄一份清单。

## 重编

入口只有 `./gradlew :desktopApp:buildJni`（在 Android Studio 里点桌面端运行也会触发它）。
Gradle 这条路**开箱可用**：它自己进 MSYS2 的 login shell，并把 toolchain 那份带 `include/` 的 JDK
传给 `build.jni.sh`，所以**不用你准备任何 JDK**。唯一还要的本机私有值是环境变量 **`MSYS2_BIN`**
（`cxx/exec.cmd` 靠它进 MSYS2 的 bash；没设的话 exec.cmd 直接退出 1）。

命令与 JDK 的关系见 [`build-and-test.md`](./build-and-test.md)；MSYS2 login shell 为什么工具链自足、
jni.h 怎么从 toolchain JDK 拿到、Git Bash 为什么不能用来编 native —— 见
[`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的「原生构建」。

## dll 要落到四处，**全都由 Gradle 自动拷**

源都是 `cxx/build/bin/<name>.dll`，不需要任何手动操作：

```text
1. shared/build/processedResources/jvm/test/           ← jvmTestProcessResources 自动（跑测试）
2. desktopApp/build/resources/main/                    ← :desktopApp:processResources 自动（IDE 里直接跑；不进 jar，jar 已 exclude *.dll）
3. desktopApp/build/compose/tmp/prepareAppResources/   ← :desktopApp:prepareAppResources 自动（:desktopApp:run 与打包）
4. <app-image>/app/resources/                          ← 打包态：散文件随安装包落盘
```

加载侧 [`jni.jvm.kt`](../../../../shared/src/jvmMain/kotlin/soko/ekibun/jni.jvm.kt) 按这个顺序找，
**前两步拿到的都是磁盘上的真文件，一次都不解包**：

| 顺序 | 认什么 | 何时命中 |
|---|---|---|
| 1 | system property `compose.application.resources.dir` 下的同名文件 | `:desktopApp:run`（指第 3 处）、装好的 App（指第 4 处） |
| 2 | classpath 条目是**目录**时资源 URL 的协议是 `file:` | IDE 里跑（第 2 处）、`jvmTest`（第 1 处） |
| 3 | 库在 **jar** 里 —— 只能解包 | 上面两条都不成立时 |

第 3 步解到 `<java.io.tmpdir>/acg-native/<库名>-<内容哈希>/`：同内容只解一次、旧哈希目录顺手清掉，
所以**不会像改造前那样每加载一次就留一份新副本**（那次实测一天攒了 115 个 113 MB 的 `ffmpeg.dll`
共 12.4 GB —— `deleteOnExit()` 在 Windows 上删不掉已被 `System.load` 映射的文件，JDK-4171239）。
**反复看到走第 3 步就是回归**，先去查前两处为什么没命中。

## 目录形状是插件写死的（`<os>-<arch>/`）

第 3 处的来源是 `desktopApp/build.gradle.kts` 的 `syncNativeResources`，它挂到
`compose.desktop.application.nativeDistributions.appResourcesRootDir`。插件
（`configureJvmApplication.kt` 的 `prepareAppResources`）**只读三个子目录** ——
`appResourcesRootDir/common`、`/<os>`、`/<os>-<arch>` —— 并把它们的内容**摊平**到第 3 处。

⚠️ 库必须落在 `<os>-<arch>/`（本仓库固定 `windows-x64/`）**这一层**。直接扔在 root 下
**不报错**：任务静默变 `NO-SOURCE` → 第 3、4 处都是空的 → 运行期悄悄退回上面第 3 步解包。
2026-09-22 踩过一次，唯一症状是"打包出来 `app/resources/` 是个空目录"。

## 三个会让人白跑一轮的点

- `createDistributable` 的 `appResourcesDir` 是 **`@Internal`**（不进 up-to-date 判据）：第 3 处
  的内容变了它照样可能是 `UP-TO-DATE`，要显式 `:desktopApp:createDistributable --rerun`。
- 四处都依赖 **`:desktopApp:buildJni`** 先跑过；`syncNativeResources` 是 `Sync`（镜像），
  native 侧删掉的库会跟着从第 3、4 处消失。
- **dll 不进 jar 了**：`desktopApp/build.gradle.kts` 给 `jar` 加了 `exclude("**/*.dll")`，
  安装包里同一批库只存在 `app/resources/` 一份（约省 117 MB）。第 2 处（`build/resources/main`）
  仍由 `processResources` 写，是给 IDE / 直接跑 `MainKt` 走 step 2（file: URL）用的，不进 jar；
  打包态由 `appResourcesRootDir` 提供（step 1）。
