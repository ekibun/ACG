import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jlleitschuh.gradle.ktlint.KtlintExtension

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
  extensions.configure<KtlintExtension> {
    filter {
      exclude("**/generated/**")
    }
  }
}

// 让 cxx 下两个 vendored submodule（ffmpeg / quickjs）与 native 构建产物不进 Studio 索引，
// 压低 IDE 内存（实测 IDE 工作集 ~3.6GB，主因是 cxx 1.3 万文件被全量索引）。
// excludeDirs 在每次 Gradle sync 时写回生成的 .iml，跨 sync 不丢失。
allprojects {
  apply(plugin = "idea")
  val ideaModule = extensions.getByType<IdeaModel>().module
  ideaModule.excludeDirs =
    ideaModule.excludeDirs +
    setOf(
      rootDir.resolve("cxx/ffmpeg/ffmpeg"),
      rootDir.resolve("cxx/quickjs/quickjs"),
      rootDir.resolve("cxx/build"),
    )
}
