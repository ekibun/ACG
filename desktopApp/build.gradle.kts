import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.nucleus)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    // Nucleus Tao 后端：composewebview 的桌面实现依赖它拿宿主窗口句柄
    // (`LocalTaoWindow`)，Tao 后端不加载 AWT。
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.decorated.window.tao)
    implementation(libs.nucleus.core.runtime)
}

val buildJni by tasks.registering(Exec::class) {
    description = "Build native libs (ffmpeg/quickjs) for the desktop JVM"
    group = "build"
    workingDir = rootDir.resolve("cxx")

    val javaHome = javaToolchains.compilerFor {}.get().metadata.installationPath.asFile.absolutePath.replace("\\", "/")
    if(OperatingSystem.current().isWindows) {
        commandLine("cmd", "/c", "exec ./build.jni.sh \"$javaHome\"")
    } else {
        commandLine("./build.jni.sh", javaHome)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildJni)
    from(rootDir.resolve("cxx/build/bin"))
}

// 打包 DSL 由 Nucleus 插件提供（替代原来的 compose.desktop.application）。
nucleus.application {
    mainClass = "soko.ekibun.acg.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        appName = "ACG"
        packageName = "soko.ekibun.acg"
        packageVersion = "1.0.0"
    }
}
