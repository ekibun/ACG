import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 平台层（:platform）：native 绑定（soko.ekibun.{jni,quickjs,ffmpeg,web} 的 JNI 面）+
// 跨端通用结构与平台原语（webview 契约与两端实现、播放器的 SurfaceContext / VideoSurface、
// File/Http IO 原语与 ktor 引擎）。包名是 JNI 符号的一部分，搬动文件不改包名。业务与界面在 :shared。
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

  sourceSets {
    commonMain.dependencies {
      // coroutines 用 api：Pointer.closeDeferred() 的返回类型 Job 与 ThreadDispatcher
      // 本身都是协程类型，消费方（:shared）要看得见。
      api(libs.kotlinx.coroutines.core)
      // QuickJS.kt 的 @Keep（native 线程直接调进来的入口不被动过）。
      implementation(libs.androidx.annotation)
      // webview 壳与 VideoSurface 的 Compose 面。不引 material3 —— 进度条这类
      // 视觉折算在 :shared 的屏幕层做。
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.ui)
      // Http 的公共面直接暴露 ktor 类型（HttpClient / HttpResponse / ByteReadChannel），
      // 消费方（:shared 的 JsEngine / FileIO / HttpIO）要看得见 —— 与 coroutines 同理用 api。
      api(libs.ktor.client.core)
    }
    jvmMain.dependencies {
      // 桌面端 ktor 引擎；logback 是 ktor 经 slf4j 打日志的后端（代码零引用，纯运行时）。
      implementation(libs.ktor.client.java)
      implementation(libs.logback.classic)
    }
    androidMain.dependencies {
      // Android 端 ktor 引擎。
      implementation(libs.ktor.client.okhttp)
      // SurfaceContext.android 的 createBitmap（androidx.core.graphics 扩展）——
      // 在 :shared 时经传递依赖可见，:platform 的依赖图没有它，须显式声明。
      implementation(libs.androidx.core.ktx)
    }
    jvmTest.dependencies {
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

tasks.named<Test>("jvmTest") {
  // 进程级崩溃（native 段的访问违例之类）时，HotSpot 的现场报告默认落在**进程工作目录**，
  // 也就是本模块目录 —— 每崩一次就多一个待提交的未跟踪文件。指到 build/ 下，
  // 跟其他产物一样被 `**/build/` 忽略掉。排查崩溃时去 platform/build/ 找 hs_err_pid*.log。
  jvmArgs("-XX:ErrorFile=${layout.buildDirectory.get().asFile.resolve("hs_err_pid%p.log")}")
  // JDK 24 起（JEP 472）`System.load` 会打印 restricted-method 警告；测试里
  // `jniLoadLibrary` 正是用它加载 cxx 的四个 dll，所以这里也要显式声明一次。
  // 详细理由与取值见 desktopApp/build.gradle.kts 的同名注释。
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // 每个用例各自 ctx.close()：native 段的断言 abort 会连整个测试进程一起带走，
  // 而 Gradle 默认只汇总结果，崩了就只能从上一轮残留的 XML 去猜死在哪个用例。
  // 打印逐用例事件才能一眼定位，代价是每轮几十行输出 —— 留着当偶发崩的第一现场。
  testLogging { events("started", "passed", "skipped", "failed") }
  // webview.cpp 的原生日志开关与 Chromium 日志重定向（随 NativeWebViewHostTest 迁入本模块）。
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
