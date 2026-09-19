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
    }
  }
}
