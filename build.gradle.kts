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

// —— 原生库复用：只判断「库从哪来」，桌面端按需取用 ——
// 本目录若是 git 从属工作树（`--git-common-dir` 指向主仓的 `.git`），且主仓编过桌面端，就直接用
// 主仓的 dll，不需要任何配置；主仓自己照旧本地编译。正本见 docs/development/build.md「原生库复用」。
//
// 只管桌面：worktree 约定是「只改 Kotlin、UI 哪都能测」，Android 的 native 与打包留在能编它的地方。
val repositoryRoot: File = rootDir

fun gitLines(
  root: File,
  vararg arguments: String,
): List<String> =
  providers
    .exec {
      commandLine("git", "-C", root.absolutePath, *arguments)
      isIgnoreExitValue = true
    }.standardOutput
    .asText
    .get()
    .lines()
    .filter { it.isNotBlank() }

// 本目录若是从属工作树，返回主工作树（主仓）根；主仓自己、或不是 git 仓库时返回 null。
fun mainWorktreeRoot(): File? {
  val topLevel =
    gitLines(repositoryRoot, "rev-parse", "--path-format=absolute", "--show-toplevel")
      .singleOrNull()
      ?.let(::File)
      ?: return null
  // 从属工作树的 .git 是文件、common dir 落在主仓 `.git` 下；主仓自己的 common dir 就是它的 `.git`。
  val mainRoot =
    gitLines(repositoryRoot, "rev-parse", "--path-format=absolute", "--git-common-dir")
      .singleOrNull()
      ?.let(::File)
      ?.parentFile
      ?: return null
  return mainRoot.takeIf { root -> root.canonicalFile != topLevel.canonicalFile }
}

// 主仓编过桌面端就复用它的产物；没编过就照旧本地编（那种情况本来也编不动）。
val nativeReuseRoot: File? =
  mainWorktreeRoot()?.let { main ->
    if (main.resolve("cxx/build/bin").isDirectory) {
      main
    } else {
      logger.warn(
        "No reusable native libs in {} (cxx/build/bin is missing): building locally, " +
          "which needs the cxx submodules and MSYS2.",
        main,
      )
      null
    }
  }

// 原生库目录：从属 worktree 指主仓的 `cxx/build/bin`（各模块直接读，不复制），主仓指自己的。
extra["acgNativeBinDir"] =
  nativeReuseRoot?.resolve("cxx/build/bin") ?: repositoryRoot.resolve("cxx/build/bin")
