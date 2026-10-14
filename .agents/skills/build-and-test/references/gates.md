# 编译闸门：四条（提交前跑，验收不跑）

> 四条闸门的**何时跑**以根 [`AGENTS.md`](../../../../AGENTS.md) §5 为正本：验收阶段不跑，只在**用户确认提交时**跑一次。本文件只给任务名与覆盖面。

```bash
./gradlew :shared:compileAndroidMain :shared:compileKotlinJvm \
          :desktopApp:compileKotlin :androidApp:compileDebugKotlin
# :shared 那条注意不是 compileDebugKotlinAndroid；:androidApp 这条恰恰叫 compileDebugKotlin
```

| 任务 | 编到哪 |
|---|---|
| `:shared:compileAndroidMain` | `shared/src/androidMain` + `commonMain` |
| `:shared:compileKotlinJvm` | `shared/src/jvmMain` + `commonMain` |
| `:desktopApp:compileKotlin` | `desktopApp/src` 自己（不含 shared） |
| `:androidApp:compileDebugKotlin` | `androidApp/src` 自己（不含 shared 与 native） |

> **DO：** 前两条会连带编 `:platform`（`shared` 以 `api` 挂接它）—— 平台层（jni / quickjs / ffmpeg / web）的编译错误由这两条顺带拦到，不需要单独的闸门任务。

> **DO：** `commonMain` 的 `expect` / `actual` 是否两端都成立，只有**前两条一起跑**才查得出来；只跑一条时，跨端签名错要等提交前才暴露。

> **DO：** 上面四条编译闸门都不碰 native：桌面侧 `.so`/`.dll` 由 `buildJni` 管，Android 侧由 `:androidApp:externalNativeBuildDebug` 管。native 重编与落位已全自动挂在消费链任务图上（改了 `cxx/` 跑测试/构建会先重编），无需手动触发（见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的「原生构建」）。
