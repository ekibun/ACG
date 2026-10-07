import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// JNI 绑定层（soko.ekibun.{jni,quickjs,ffmpeg}）：jvm() + android 两个 JVM 族 target，
// 不引 compose —— 业务与 UI 都在 :shared，这里只暴露纯 Kotlin 的绑定面。
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
  jvm()

  android {
    namespace = "soko.ekibun.bindings"
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
  // 跟其他产物一样被 `**/build/` 忽略掉。排查崩溃时去 bindings/build/ 找 hs_err_pid*.log。
  jvmArgs("-XX:ErrorFile=${layout.buildDirectory.get().asFile.resolve("hs_err_pid%p.log")}")
  // JDK 24 起（JEP 472）`System.load` 会打印 restricted-method 警告；测试里
  // `jniLoadLibrary` 正是用它加载 cxx 的三个 dll，所以这里也要显式声明一次。
  // 详细理由与取值见 desktopApp/build.gradle.kts 的同名注释。
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // 每个用例各自 ctx.close()：native 段的断言 abort 会连整个测试进程一起带走，
  // 而 Gradle 默认只汇总结果，崩了就只能从上一轮残留的 XML 去猜死在哪个用例。
  // 打印逐用例事件才能一眼定位，代价是每轮几十行输出 —— 留着当偶发崩的第一现场。
  testLogging { events("started", "passed", "skipped", "failed") }
}
