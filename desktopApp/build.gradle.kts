import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.internal.os.OperatingSystem

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