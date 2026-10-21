import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
  // 声明插件而不在此处应用。
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidMultiplatformLibrary) apply false
  alias(libs.plugins.composeMultiplatform) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.ktlint) apply false
}

// 取出版本目录里的 compose-rules-ktlint 坐标。
val composeRulesKtlint = libs.compose.rules.ktlint

// 对根项目与全部子项目应用 ktlint，覆盖检查与格式化。
allprojects {
  apply(plugin = "org.jlleitschuh.gradle.ktlint")

  // 挂载 compose-rules 规则集。
  dependencies {
    add("ktlintRuleset", composeRulesKtlint)
  }

  // 排除生成代码目录。
  extensions.configure<KtlintExtension> {
    filter {
      exclude("**/generated/**")
    }
  }
}

// 把 cxx/ffmpeg/ffmpeg、cxx/quickjs/quickjs、cxx/build 加进各模块的 excludeDirs，使这些目录不进 IDE 索引。
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
