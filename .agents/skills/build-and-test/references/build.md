# 构建

## 验收：编译门禁（六条，只在提交时跑一次）

```bash
./gradlew :shared:compileAndroidMain :shared:compileKotlinJvm \
          :desktopApp:compileKotlin :androidApp:compileDebugKotlin \
          :desktopApp:buildJni :androidApp:externalNativeBuildDebug
```

> **DO：** `expect` / `actual` 两端都成立，只有 jvm + android 那两条**一起跑**才查得出来。

> **DO：** 改了 `cxx/` 两条 native 都要编 —— 桌面 `buildJni` 与 Android `externalNativeBuildDebug` 互不相干，前四条一条都不碰 `cxx/`（机制见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的「原生构建」）。

> **DO：** 改过 JNI 面（改名 / 挪包 / 改签名）按 `:platform:jvmTest` 绿判据走，不看“编过了” —— 编译拦不住符号名，旧 dll 照样加载，只在调用时 `UnsatisfiedLinkError`。

> **DON'T：** 用 `:desktopApp:build` 代替门禁：它连带 `check`，会把 `:desktopApp` 自己的 `ktlintCheck` 与 `test` 再跑一遍。

耗时两档（实测）：`cxx/` 与构建配置都没动 ⇒ **2 秒**（任务仍真跑，`ninja: no work to do`）；四个 ABI 要重编 ⇒ **约 7 分钟**（实测 6m43s）。

## 出包（发布用）

```bash
./gradlew :desktopApp:build                     # 桌面 jar；经 processResources 连带 buildJni，并跑 check
./gradlew :androidApp:assembleDebug             # Android APK；经 mergeDebugNativeLibs 连带四个 ABI 的 CMake 编
```

exe/msi 不在 `build` 里，由 `packageReleaseDistributionForCurrentOS` 单独产出。
