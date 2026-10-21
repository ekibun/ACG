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
    // Windows 下经 shell 调用 `cxx/exec.cmd`，命令写作 `.\exec`。
    if (OperatingSystem.current().isWindows) {
      commandLine("cmd", "/c", ".\\exec ./build.jni.sh \"$javaHome\"")
    } else {
      commandLine("./build.jni.sh", javaHome)
    }
  }

// clang-format 闸门：进 MSYS2 跑 cxx/format.jni.sh，覆盖 cxx/ 三个顶层 C++ 文件。`clangCheck` 只校验，
// `clangFormat` 就地重写。
val clangCheck =
  tasks.register<Exec>("clangCheck") {
    description = "Check cxx/ C++ files are clang-format clean (mingw64 clang 22.1.8)"
    group = "verification"
    workingDir = rootDir.resolve("cxx")
    if (OperatingSystem.current().isWindows) {
      commandLine("cmd", "/c", ".\\exec ./format.jni.sh check")
    } else {
      commandLine("./format.jni.sh", "check")
    }
  }

val clangFormat =
  tasks.register<Exec>("clangFormat") {
    description = "Format cxx/ C++ files in place with clang-format (mingw64 clang 22.1.8)"
    group = "formatting"
    workingDir = rootDir.resolve("cxx")
    if (OperatingSystem.current().isWindows) {
      commandLine("cmd", "/c", ".\\exec ./format.jni.sh fix")
    } else {
      commandLine("./format.jni.sh", "fix")
    }
  }

tasks.named<ProcessResources>("processResources") {
  dependsOn(buildJni)
  from(rootDir.resolve("cxx/build/bin"))
}

// jar 不含 dll，运行期从 build/resources/main 或 app/resources 直接加载文件。
tasks.named<Jar>("jar") {
  exclude("**/*.dll")
}

// 原生库作为应用资源随安装包落成散文件：Compose 插件把 appResourcesRootDir 下 `windows-x64/` 的内容
// 摊平进 `<app-image>/app/resources/`，运行期经 `compose.application.resources.dir` 取得绝对路径。
//
// 目录形状固定：库必须落在 `windows-x64/` 这一层。
//
// 用 Sync 而非 Copy，使目标目录成为 `cxx/build/bin` 的镜像。
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

// 把 nativeResources 登记为 createDistributable 的输入。
tasks.matching { it.name == "createDistributable" }.configureEach {
  inputs.dir(nativeResourcesDir)
}

// 把 MSYS2_BIN 透传给运行中的应用进程。
// （原生日志的开关见 `docs/development/debugging.md`。）
tasks.withType<JavaExec>().configureEach {
  providers.environmentVariable("MSYS2_BIN").orNull?.let { environment("MSYS2_BIN", it) }
}

// JDK 24 起 `System.load` / `System.loadLibrary` 会打印警告，此处声明 native access 归属
// （classpath 应用对应匿名模块，取 `ALL-UNNAMED`）。
tasks.withType<JavaExec>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // 打开本包的 JVM 断言（`...` 含子包）。
  jvmArgs("-ea:soko.ekibun...")
}

// 标准 compose.desktop 打包 DSL，窗口后端为 AWT（`ComposeWindow` / `ComposePanel`）。
compose.desktop {
  application {
    mainClass = "soko.ekibun.acg.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Msi)
      packageName = "soko.ekibun.acg"
      packageVersion = "1.0.0"
      // 原生库作为应用资源随包走，用 fileProvider 把 syncNativeResources 挂在打包任务上。
      appResourcesRootDir.fileProvider(syncNativeResources.map { it.destinationDir })
    }
  }
}
