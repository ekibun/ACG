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
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.ktor.client.core)
      // 跨端 WebView 不再用预编译的 composewebview：Android 端直接用
      // android.webkit.WebView，桌面端走自研的 cxx/webview（见 soko.ekibun.acg.web）。
    }
    jvmMain.dependencies {
      implementation(libs.ktor.client.java)
      implementation(libs.logback.classic)
      // 桌面端可见 WebView 的宿主：整个 AWT 组件（SwingPanel 里的 Canvas）+ JAWT
      // 取 HWND + SetParent，全在自己这边，不需要任何第三方窗口库。
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

// jniLoadLibrary() 从 classpath 资源里取 native 库，测试时需要能拿到 cxx 的构建产物
tasks.named<ProcessResources>("jvmTestProcessResources") {
  val binDir = rootProject.layout.projectDirectory.dir("cxx/build/bin")
  from(binDir) {
    include("*.dll", "*.so", "*.dylib")
  }
}

// 临时调试：把 webview.cpp 的日志打开
tasks.named<Test>("jvmTest") {
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
