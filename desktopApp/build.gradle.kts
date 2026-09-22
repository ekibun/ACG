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

// dll 不进 jar：它们以「应用资源 / classpath 目录里的真文件」形态存在，打进 jar 只是白占 ~117 MB
// （运行时也用不到 —— jniLoadLibrary 从 app/resources 或 build/resources/main 直接 System.load 真文件）。
// 保留 processResources 把 dll 写进 build/resources/main，是为了 IDE / 直接跑 MainKt 时 step 2
// （file: URL）仍能命中；打包后则由 appResourcesRootDir 提供（step 1）。
tasks.named<Jar>("jar") {
  exclude("**/*.dll")
}

// 打包态的原生库：**不放进 jar**，而是作为「应用资源」随安装包落成一堆散文件。
//
// Compose 插件会把 appResourcesRootDir 下 `<os>-<arch>/` 里的一切放进安装目录（实测落点
// `<app-image>/app/resources/`），运行时用 system property `compose.application.resources.dir`
// 拿到它的绝对路径 —— `jniLoadLibrary` 于是直接 System.load 那个真文件，一次解包都不需要。
// 不这么做的话，每次加载都要把 118 MB 的 ffmpeg.dll 解到 %TEMP%，而 Windows 上那个副本
// **删不掉**（JDK-4171239），一天就能攒出 12 GB。
//
// ⚠️ 目录形状是插件写死的（`configureJvmApplication.kt` 的 `prepareAppResources`）：它只读
// `appResourcesRootDir/common`、`/<os>`、`/<os>-<arch>` 三个子目录，并把三者的内容**摊平**到
// 同一个目标里。所以库必须落在 `<os>-<arch>/` 这一层 —— 直接扔在 root 下不会有任何提示，
// 任务会静默变成 NO-SOURCE，打出一个空的 `app/resources/`。桌面端只按 Windows x86_64 考虑，
// 固定 `windows-x64/`。
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
