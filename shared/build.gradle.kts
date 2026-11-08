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
  }

  sourceSets {
    commonMain.dependencies {
      // 以 api 暴露 :platform，业务层与各应用模块可直接使用其类型。
      api(project(":platform"))
      implementation(libs.androidx.collection)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      // 种子色 → M3 配色：官方算法（HCT/CAM16）的 KMP 实现，MIT。
      implementation(libs.materialkolor)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      // 引入 JUnit4，用于按 assumption 记为 skipped。
      implementation("junit:junit:4.13.2")
      // 提供 DevStrip 的编译期 Compose API（ComposePanel / material3）。
      implementation(compose.desktop.currentOs)
      // 提供 Dispatchers.Main（Swing EDT）：验证窗口与将来的 ViewModel 都要它。
      implementation(libs.kotlinx.coroutinesSwing)
      // 原生库与测试媒体都是目录型 classpath 条目：不复制、不进 build 产物。
      runtimeOnly(files(rootProject.extra["acgNativeBinDir"] as File))
      runtimeOnly(files(rootProject.file("test-media")))
    }
  }
}

tasks.named<Test>("jvmTest") {
  // 原生库与测试媒体都是目录型 classpath 条目（`loadLibrary` / `getResource` 直接读文件），
  // 所以只要保证 cxx/build/bin 已经产出即可，不用把它们拷进 build。
  dependsOn(":desktopApp:stageNativeLibs")
  // 把 JVM 崩溃报告写到 build/hs_err_pid%p.log。
  jvmArgs("-XX:ErrorFile=${layout.buildDirectory.get().asFile.resolve("hs_err_pid%p.log")}")
  // 关闭 JDK 24+ 的 restricted-method 警告。
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // 数据目录由各测试类的 `TestPaths` 注入，此处不注 `-Dacg.dataDir`。
  // 逐用例打印测试事件。
  testLogging { events("started", "passed", "skipped", "failed") }
}

dependencies {
  androidRuntimeClasspath(libs.compose.uiTooling)
}

// —— dev 媒体服务器：服务器与控制条由 debugPlay 的 main 自己拉起（见 DevServer.startDevServer），
// Gradle 只负责起窗口进程；服务器代码（DevServerKt / TestServer / DevStrip）位于本模块 jvmTest。

val jvmTestRuntimeClasspath =
  kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")
    .runtimeDependencyFiles
    ?: files()

// —— 手动验证窗口：每个页面一个入口，只供人启动，不进 jvmTest 任务 ——
// 必须是真 AWT 窗口：SwingPanel / JAWT 要挂 Win32 子窗口，离屏场景挂不上。
listOf(
  Triple("debugPlay", "soko.ekibun.acg.ui.screen.DebugPlayScreenKt", "播放"),
  Triple("debugWeb", "soko.ekibun.acg.ui.screen.DebugWebScreenKt", "浏览器"),
  Triple("debugCode", "soko.ekibun.acg.ui.screen.DebugCodeScreenKt", "脚本"),
).forEach { (taskName, rootClass, label) ->
  tasks.register<JavaExec>(taskName) {
    group = "debug"
    description = "启动「$label」手动验证窗口"
    classpath(jvmTestRuntimeClasspath)
    mainClass.set(rootClass)
    workingDir = rootProject.projectDir
    dependsOn("jvmTestClasses")
    // 原生库是目录型 classpath 条目，得先让 cxx/build/bin 产出。
    dependsOn(":desktopApp:stageNativeLibs")
    jvmArgs(
      "-Dstdout.encoding=UTF-8",
      "-Dstderr.encoding=UTF-8",
      "--enable-native-access=ALL-UNNAMED",
      "-Dacg.debug.dir=" +
        layout.buildDirectory
          .get()
          .asFile
          .resolve("debug")
          .absolutePath,
    )
  }
}
