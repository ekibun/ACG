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
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
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
            // 跨端 WebView：Android 走 android.webkit.WebView，
            // 桌面(jvm)走 Nucleus Tao NativeView 里的 WebView2/WKWebView/WebKit2GTK。
            implementation(libs.compose.webview)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            implementation(libs.logback.classic)
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

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}