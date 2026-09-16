plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktlint) apply false
}

// 版本目录的访问器在 subprojects {} 里不可用，先取出来。
val composeRulesKtlint = libs.compose.rules.ktlint

// 格式化 / lint 覆盖全部自有模块（submodule 与 cxx/webview/sdk 不在 Gradle 工程里，天然排除）。
// 规则取值来自根 `.editorconfig`：块缩进 2、续行 4。
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // Compose 专用规则集（compose-rules），补标准 ktlint 覆盖不到的 Compose 用法问题。
    dependencies {
        add("ktlintRuleset", composeRulesKtlint)
    }

    // 生成代码不是我们的代码 —— 排除掉，否则 compose 资源生成器产出的 .kt 也会被检查。
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            exclude("**/generated/**")
        }
    }
}