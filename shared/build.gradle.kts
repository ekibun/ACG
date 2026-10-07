import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

kotlin {
  jvm()

  android {
    namespace = "soko.ekibun.acg.shared"
    compileSdk =
      libs.versions.android.compileSdk
        .get()
        .toInt()
    minSdk =
      libs.versions.android.minSdk
        .get()
        .toInt()

    compilerOptions {
      jvmTarget = JvmTarget.JVM_11
    }
    androidResources {
      enable = true
    }
    withHostTest {
      isIncludeAndroidResources = true
    }
    withDeviceTestBuilder {
      sourceSetTreeName = "test"
    }.configure {
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
  }

  sourceSets {
    androidMain.dependencies {
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.compose.uiTooling)
      implementation(libs.ktor.client.okhttp)
    }
    commonMain.dependencies {
      // JNI 绑定层（soko.ekibun.{jni,quickjs,ffmpeg}）抽在 :bindings，包名未变。
      // api：业务层直接用它的类型，desktopApp / androidApp 也要看得见。
      api(project(":bindings"))
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.ktor.client.core)
      // 跨端 WebView 两端都自己实现：Android 用 android.webkit.WebView，
      // 桌面用自研的 cxx/webview（见 soko.ekibun.acg.web）。
    }
    jvmMain.dependencies {
      implementation(libs.ktor.client.java)
      implementation(libs.logback.classic)
      // 桌面端可见 WebView 的宿主：整个 AWT 组件（SwingPanel 里的 Canvas）+ JAWT
      // 取 HWND + SetParent，全在自己这边，不需要任何第三方窗口库。
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      // dev 控制条（DevStrip）的**编译期** Compose API：ComposePanel / material3 在 ui-desktop
      // 与 desktop 变体里。运行时它经子 ClassLoader（parent-first）用的是**应用自己的**
      // Compose 拷贝 —— 这份依赖只喂编译，不进任何生产打包。
      implementation(compose.desktop.currentOs)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

// jniLoadLibrary() 从 classpath 资源里取 native 库，测试时需要能拿到 cxx 的构建产物。
// buildJni 挂进任务图：改了 cxx/ 只跑测试也会先重编 native，杜绝「测试静默用旧 dll」。
tasks.named<ProcessResources>("jvmTestProcessResources") {
  dependsOn(":desktopApp:buildJni")
  val binDir = rootProject.layout.projectDirectory.dir("cxx/build/bin")
  from(binDir) {
    include("*.dll", "*.so", "*.dylib")
  }
}

// 临时调试：把 webview.cpp 的日志打开
tasks.named<Test>("jvmTest") {
  // 进程级崩溃（native 段的访问违例之类）时，HotSpot 的现场报告默认落在**进程工作目录**，
  // 也就是本模块目录 —— 每崩一次就多一个待提交的未跟踪文件。指到 build/ 下，
  // 跟其他产物一样被 `**/build/` 忽略掉。排查崩溃时去 shared/build/ 找 hs_err_pid*.log。
  jvmArgs("-XX:ErrorFile=${layout.buildDirectory.get().asFile.resolve("hs_err_pid%p.log")}")
  // JDK 24 起（JEP 472）`System.load` 会打印 restricted-method 警告；测试里
  // `jniLoadLibrary` 正是用它加载 cxx 的三个 dll，所以这里也要显式声明一次。
  // 详细理由与取值见 desktopApp/build.gradle.kts 的同名注释。
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // 每个用例各自 ctx.close()：native 段的断言 abort 会连整个测试进程一起带走，
  // 而 Gradle 默认只汇总结果，崩了就只能从上一轮残留的 XML 去猜死在哪个用例。
  // 打印逐用例事件才能一眼定位，代价是每轮几十行输出 —— 留着当偶发崩的第一现场。
  testLogging { events("started", "passed", "skipped", "failed") }
  environment("ACG_WEBVIEW_DEBUG", "1")
  environment("ACG_WEBVIEW_LOG", "${rootProject.layout.projectDirectory.asFile}/build/acg-webview.log")
  // 让 WebView2 把自己那份 Chromium 日志写到 user data folder 下的
  // EBWebView/chrome_debug.log —— 引擎出的问题只有这份日志说得清。
  // 想试别的浏览器参数（比如 --disable-gpu）就设 ACG_WEBVIEW2_ARGS，会追加在后面。
  val extraArgs = providers.environmentVariable("ACG_WEBVIEW2_ARGS").orNull.orEmpty()
  environment(
    "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
    "--enable-logging --v=1" + if (extraArgs.isBlank()) "" else " $extraArgs",
  )
}

dependencies {
  androidRuntimeClasspath(libs.compose.uiTooling)
}

// —— dev 媒体服务器：Gradle 只负责「把 agent 挂上 hotRun 的应用进程」，其余都在 agent 里 —————
// 服务器的 spawn / 端口自愈 / 随应用退出收场，全在 DevToolsAgent.premain：
// Gradle 只传 -javaagent 与 -D，生命周期归 agent。服务器代码（DevServerKt /
// TestMediaServer / DevStrip）就住在本模块的 jvmTest，与 HTTP 用例共用 TestMediaServer：
// 同模块内部直接取 jvmTest 的运行时类路径即可，不需要对外暴露可消费配置。
// 生产打包走 main 运行时类路径，不经过它。

val jvmTestRuntimeClasspath =
  kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")
    .runtimeDependencyFiles
    ?: files()

val devMediaServerPid = layout.buildDirectory.file("dev-media-server.pid")
val devMediaServerLog = layout.buildDirectory.file("dev-media-server.log")

// 控制条的 premain agent jar：从测试类路径里只挑 agent 本体，打上 Premain-Class 清单。
// 面板本体（DevStrip）不打进来 —— 它经 `-Dacg.dev.classpath` 的子 ClassLoader 在应用进程里装载。
val devToolsAgentJar =
  tasks.register<Jar>("devToolsAgentJar") {
    archiveFileName.set("dev-tools-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dev-tools"))
    dependsOn("compileTestKotlinJvm")
    // 配置期解析成固定文件（CC 安全：可解析配置直接挂任务，CC 落盘时会对
    // `jvmTestRuntimeClasspath` 触发无锁解析而被拒 —— 实测）。
    from(jvmTestRuntimeClasspath) { include("soko/ekibun/DevToolsAgent*") }
    manifest { attributes("Premain-Class" to "soko.ekibun.DevToolsAgent") }
  }

// 手动常驻跑法（curl 调试用）：hotRun 不经过它 —— 那条路由 agent 托管（spawn / 随应用退出收场）。
tasks.register<JavaExec>("devMediaServer") {
  classpath(jvmTestRuntimeClasspath)
  mainClass.set("soko.ekibun.DevServerKt")
  // 命令在仓库根敲，--media 的相对路径也按仓库根解析（JavaExec 默认的工作目录是模块目录）。
  workingDir = rootProject.projectDir
  // 日志里全是中文：Windows 重定向 stdout 默认走系统码页（GBK），落文件再读就是乱码，钉成 UTF-8。
  jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
  // 文件依赖不带 built-by：测试类要现编，改完 DevServer.kt 立即生效。
  dependsOn("compileTestKotlinJvm")
}

// hotRun（:desktopApp 的任务）：把 agent 挂上应用进程，dev 服务器的 spawn 与收场都在 agent 里
// —— 服务器与应用进程同生共死（关窗 / Ctrl-C 走 shutdown hook；硬杀的残留由下一次 spawn 前的
// 端口自愈清掉）。
//
// 跨项目配置 `:desktopApp` 的 JavaExec 任务：hotRun 由 Compose Hot Reload 插件在**每个**
// Kotlin JVM 模块注册，只有桌面端那一个才能真正起应用，所以注入点在 desktopApp。
// 用 `evaluationDependsOn` 保证 `:desktopApp` 已求值（插件任务这时才存在），否则匹配为空集。
evaluationDependsOn(":desktopApp")

project(":desktopApp")
  .tasks
  .withType<JavaExec>()
  .matching { it.name == "hotRun" || it.name == "hotRunAsync" }
  .configureEach {
    dependsOn(devToolsAgentJar)
    jvmArgs(
      "-javaagent:" +
        devToolsAgentJar
          .get()
          .archiveFile
          .get()
          .asFile
          .absolutePath,
      "-Dacg.dev.server.port=" +
        providers.gradleProperty("devPort").orElse("8099").get(),
      "-Dacg.dev.classpath=" +
        jvmTestRuntimeClasspath.joinToString(File.pathSeparator, transform = { it.absolutePath }),
      "-Dacg.dev.pidfile=" + devMediaServerPid.get().asFile.absolutePath,
      "-Dacg.dev.logfile=" + devMediaServerLog.get().asFile.absolutePath,
      "-Dacg.dev.workdir=" + rootProject.projectDir.absolutePath,
    )
    providers.gradleProperty("devMedia").orNull?.let { media ->
      jvmArgs("-Dacg.dev.media=$media")
    }
  }
