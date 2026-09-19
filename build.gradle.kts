plugins {
  // 这里必须 `apply false`，否则插件会在每个子项目各自的 classloader 里
  // 再加载一遍
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidMultiplatformLibrary) apply false
  alias(libs.plugins.composeMultiplatform) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.ktlint) apply false
}

// 版本目录的访问器在 allprojects {} 里不可用，先取出来。
val composeRulesKtlint = libs.compose.rules.ktlint

// 格式化 / lint 覆盖**含根项目在内**的全部自有项目 —— 用 `allprojects` 而不是 `subprojects`：
// 根 `build.gradle.kts` 与 `settings.gradle.kts` 也是我们的代码，写成 `subprojects` 会把它们
// 漏掉，于是这两个文件既不参与 `ktlintCheck` 也不会被 `ktlintFormat` 修（2026-09-17 实测，
// 曾因此留了 4 空格缩进没人管）。
// submodule 与 `cxx/webview/sdk` 不在 Gradle 工程里，天然排除。
allprojects {
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
