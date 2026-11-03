import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 平台层（:platform）：native 绑定的 JNI 面、跨端通用结构与平台原语（webview 契约与两端实现、播放器的
// SurfaceContext / VideoSurface、File/Http IO 原语与 OkHttp 传输层）。业务与界面在 :shared。
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

kotlin {
  jvm()

  android {
    namespace = "soko.ekibun.platform"
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
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    // 「JVM 族」中间层：Android 与桌面 JVM 共用的平台实现，只收实现，不含 external fun 声明。
    val jvmShared =
      create("jvmShared") {
        dependsOn(commonMain.get())
      }
    jvmMain.get().dependsOn(jvmShared)
    androidMain.get().dependsOn(jvmShared)

    commonMain.dependencies {
      // coroutines 用 api 暴露，消费方可直接使用协程类型。
      api(libs.kotlinx.coroutines.core)
      // 提供 @Keep 注解。
      implementation(libs.androidx.annotation)
      // webview 壳与 VideoSurface 的 Compose 面。
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.ui)
      // 提供 DataStore 基于 okio Path 的 commonMain 实现。
      implementation(libs.okio)
      implementation(libs.androidx.datastore.preferences.core)
    }
    androidMain.dependencies {
      // 提供 SurfaceContext.android 的 createBitmap 扩展。
      implementation(libs.androidx.core.ktx)
      // 提供 Android 侧外部浏览器登录的 Custom Tabs。
      implementation(libs.androidx.browser)
    }
    // HTTP 传输层：OkHttp 一份实现覆盖桌面与 Android。
    jvmShared.dependencies {
      implementation(libs.okhttp)
      // 传输层日志（HEADERS 级）。
      implementation(libs.httpLogging)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      // 引入 JUnit4，用于按 assumption 记为 skipped。
      implementation("junit:junit:4.13.2")
    }
  }
}

// 把原生库打进测试资源，并让 jvmTestProcessResources 依赖 stageNativeLibs。
// 目录由根脚本给出：从属 worktree 直接读主仓的 cxx/build/bin，不复制。
tasks.named<ProcessResources>("jvmTestProcessResources") {
  dependsOn(":desktopApp:stageNativeLibs")
  from(rootProject.extra["acgNativeBinDir"] as File) {
    include("*.dll", "*.so", "*.dylib")
  }
}

tasks.named<Test>("jvmTest") {
  // 把 JVM 崩溃报告写到 build/hs_err_pid%p.log。
  jvmArgs("-XX:ErrorFile=${layout.buildDirectory.get().asFile.resolve("hs_err_pid%p.log")}")
  // 关闭 JDK 24+ 的 restricted-method 警告。
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // 数据目录由各测试类的 `TestPaths` 注入，此处不注 `-Dacg.dataDir`。
  // 逐用例打印测试事件。
  testLogging { events("started", "passed", "skipped", "failed") }
}
