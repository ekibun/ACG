import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

dependencies {
  implementation(project(":shared"))

  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutinesSwing)

  implementation(libs.compose.uiToolingPreview)
}

val buildJni =
  tasks.register<Exec>("buildJni") {
    description = "Build native libs (webview/ffmpeg/quickjs) for the desktop JVM"
    group = "build"
    workingDir = rootDir.resolve("cxx")

    val javaHome =
      javaToolchains
        .compilerFor {}
        .get()
        .metadata.installationPath.asFile.absolutePath
        .replace("\\", "/")
    if (OperatingSystem.current().isWindows) {
      commandLine("cmd", "/c", "exec ./build.jni.sh \"$javaHome\"")
    } else {
      commandLine("./build.jni.sh", javaHome)
    }
  }

tasks.named<ProcessResources>("processResources") {
  dependsOn(buildJni)
  from(rootDir.resolve("cxx/build/bin"))
}

// dll 不进 jar：它们以「应用资源 / classpath 目录里的真文件」形态存在，打进 jar 只是白占 ~29 MB
// （运行时也用不到 —— jniLoadLibrary 从 app/resources 或 build/resources/main 直接 System.load 真文件）。
// 保留 processResources 把 dll 写进 build/resources/main，是为了 IDE / 直接跑 MainKt 时 step 2
// （file: URL）仍能命中；打包后则由 appResourcesRootDir 提供（step 1）。
tasks.named<Jar>("jar") {
  exclude("**/*.dll")
}

// 打包态的原生库：**不放进 jar**，而是作为「应用资源」随安装包落成散文件 —— Compose 插件把
// appResourcesRootDir 下 `<os>-<arch>/` 的内容摊平进 `<app-image>/app/resources/`，运行时经
// system property `compose.application.resources.dir` 拿到绝对路径，`jniLoadLibrary` 直接
// System.load 真文件、一次解包都不需要。不这么做的话，每次加载都要把 ffmpeg.dll 解到 %TEMP%，
// 而 Windows 上那个副本**删不掉**（JDK-4171239），一天就能攒出 GB 级。
//
// ⚠️ 目录形状是插件写死的（`configureJvmApplication.kt` 的 `prepareAppResources`）：只读
// `appResourcesRootDir/common`、`/<os>`、`/<os>-<arch>` 三个子目录并**摊平**到同一目标 ——
// 库必须落在 `<os>-<arch>/` 这一层，扔在 root 下**不报错**、任务静默变 NO-SOURCE，
// 打出一个空的 `app/resources/`（桌面端只按 Windows x86_64 考虑，固定 `windows-x64/`）。
//
// 用 Sync 而不是 Copy：目标目录是 `cxx/build/bin` 的**镜像**，native 侧删掉的库要跟着消失。
val nativeResourcesDir = layout.buildDirectory.dir("nativeResources")
val syncNativeResources =
  tasks.register<Sync>("syncNativeResources") {
    description = "把 cxx/build/bin 的 dll 备成打包用的应用资源（appResourcesRootDir）"
    group = "build"
    dependsOn(buildJni)
    from(rootDir.resolve("cxx/build/bin")) {
      include("*.dll")
      into("windows-x64")
    }
    into(nativeResourcesDir)
  }

// createDistributable 读的 appResourcesDir 在插件里是 @Internal、不进 up-to-date 判据：
// 不补这条输入的话，nativeResources 变了它照样 UP-TO-DATE，打出带旧 dll 的包（2026-10-02 实测）。
// 把源目录显式登记成输入，内容一变就重打。插件是懒注册，tasks.named 会在配置期扑空，用 matching 挂。
tasks.matching { it.name == "createDistributable" }.configureEach {
  inputs.dir(nativeResourcesDir)
}

// 把原生日志的两个开关透传给跑起来的 App。
//
// `JavaExec` 继承的是 **Gradle daemon** 的环境，命令行上现设的变量进不去 —— 所以这里
// 显式透传一次，否则想看 `webview.cpp` 的日志只能去改代码。
//
//   ACG_WEBVIEW_DEBUG=1               → 日志走 stderr（控制台直接可见）
//   ACG_WEBVIEW_LOG=D:/xxx/acg.log    → 追加到文件
//
// 例：`ACG_WEBVIEW_DEBUG=1 ./gradlew :desktopApp:run -x :desktopApp:buildJni`
tasks.withType<JavaExec>().configureEach {
  listOf("ACG_WEBVIEW_DEBUG", "ACG_WEBVIEW_LOG", "MSYS2_BIN").forEach { key ->
    providers.environmentVariable(key).orNull?.let { environment(key, it) }
  }
}

// JDK 24 起（JEP 472）调用 `System.load` / `System.loadLibrary` 这类 native 方法会打印警告：
//
//   WARNING: A restricted method in java.lang.System has been called
//   java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader ...
//   Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
//
// 警告里的调用方是 Skiko（加载 skiko-awt 自带的 dll），但**本项目自己也会 `System.load`**
// —— `jniLoadLibrary` 就是这么加载 webview/quickjs/ffmpeg 三个 dll 的，所以把它归给 Skiko
// 去修是修不完的。未来 JDK 会从"警告"升级成"直接拦截"，因此这里主动声明。
//
// 加在不带模块名的 classpath 应用上，对应的就是匿名模块，所以值必须是 `ALL-UNNAMED`。
// JDK 21 不认识这个选项也不会报错（实测 21.0.11 与 25.0.4.1 都正常），故无需按版本分支。
// 走 `jvmArgs(...)`（追加）而不是 `jvmArgumentProviders`：Compose Hot Reload 插件在
// `configureJavaExecTaskForHotReload` 里读 `getJvmArgs()` 再 `plus` 自己的调试参数，追加语义能
// 与它叠加；实测 `allJvmArgs` 里能稳定看到本参数。
tasks.withType<JavaExec>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // 打开**本包**的 JVM 断言：`Pointer.ptr` 的同线程断言、`initPtr` 的归属线程断言都在
  // -ea 下才生效。测试任务默认就开着；这里给 run / hotRun 补上。按包收窄（尾部的
  // `...` 含子包）而不是裸 `-ea`，免得把 ktor / compose 等第三方库的断言一起激活。
  // 生产打包的启动器不带 JVM 参数，断言关闭 —— 每次读只剩一次静态标志判断。
  jvmArgs("-ea:soko.ekibun...")
}

// 标准 compose.desktop 打包 DSL。
//
// 后端必须是 **AWT 的**（`ComposeWindow` / `ComposePanel`）：可见 WebView 是个真的
// Win32 子窗口，靠 JAWT 从 AWT 组件（`SwingPanel` 里的 Canvas）取 HWND 再
// `SetParent` 挂上去 —— 没有 AWT 就没东西可挂。所以这里刻意不用任何自绘标题栏的
// 窗口后端，窗口装饰交回系统。
compose.desktop {
  application {
    mainClass = "soko.ekibun.acg.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "soko.ekibun.acg"
      packageVersion = "1.0.0"
      // 原生库作为应用资源随包走（见上面 syncNativeResources）。
      // ⚠️ 必须用 fileProvider 把生产任务挂上去，打包任务才会自动依赖它；直接写
      // layout.buildDirectory.dir(...) 就断开了这层关系，打包时可能拿到一个空目录
      // —— 不报错，只是库变成从 jar 里现解（回到 $TEMP 那一套）。
      appResourcesRootDir.fileProvider(syncNativeResources.map { it.destinationDir })
    }
  }
}
