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

dependencies {
  androidRuntimeClasspath(libs.compose.uiTooling)
}

// —— dev 媒体服务器：Gradle 通过 -javaagent 与 -D 把 agent 挂到 hotRun 的应用进程，其余由 DevToolsAgent 负责 ——
// 服务器代码（DevServerKt / TestMediaServer / DevStrip）位于本模块 jvmTest。

val jvmTestRuntimeClasspath =
  kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")
    .runtimeDependencyFiles
    ?: files()

val devMediaServerPid = layout.buildDirectory.file("dev-media-server.pid")
val devMediaServerLog = layout.buildDirectory.file("dev-media-server.log")

// 打 premain agent jar：只收 DevToolsAgent 类，清单写入 Premain-Class。
val devToolsAgentJar =
  tasks.register<Jar>("devToolsAgentJar") {
    archiveFileName.set("dev-tools-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dev-tools"))
    dependsOn("compileTestKotlinJvm")
    // 从测试运行时类路径中取 DevToolsAgent* 打进 jar。
    from(jvmTestRuntimeClasspath) { include("soko/ekibun/DevToolsAgent*") }
    manifest { attributes("Premain-Class" to "soko.ekibun.DevToolsAgent") }
  }

// 手动常驻启动 dev 媒体服务器（curl 调试用）。
tasks.register<JavaExec>("devMediaServer") {
  classpath(jvmTestRuntimeClasspath)
  mainClass.set("soko.ekibun.DevServerKt")
  // 工作目录设为仓库根。
  workingDir = rootProject.projectDir
  // stdout / stderr 编码固定为 UTF-8。
  jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
  // 依赖测试类编译产物。
  dependsOn("compileTestKotlinJvm")
}

// 给 :desktopApp 的 hotRun / hotRunAsync 注入 -javaagent 与 -D 参数。
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
  }
