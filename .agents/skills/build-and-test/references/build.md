# 构建与打包

```bash
./gradlew :desktopApp:build                 # 桌面构建（触发 :desktopApp:buildJni）
./gradlew :desktopApp:build -x :desktopApp:buildJni   # 跳过 native 重编、只编 Java 侧
./gradlew :desktopApp:buildJni              # 只编 native、不跑
./gradlew :androidApp:assembleDebug         # Android debug 包
```

- Android 包产物：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。
  内含 `lib/<abi>/libffmpeg.so` 与 `libquickjs.so`，四个 ABI（arm64-v8a / armeabi-v7a / x86 /
  x86_64）齐全 —— 也是「Android 侧 native 有没有跟上」的快捷核对点。
  首次跑要编 native（5 分钟量级），之后增量是几十秒。
- 桌面产物：`desktopApp/build/libs/desktopApp.jar`（`:desktopApp:build` 默认只出 jar，不打包 exe/msi；exe/msi 由 `packageReleaseDistributionForCurrentOS` 单独产出）。native 库落在
  `cxx/build/bin/`（`webview.dll` / `libquickjs.*` / `libffmpeg.*`）—— 这是「桌面 native 有没有跟上」的
  核对点，也是测试资源拷贝源（`jvmTestProcessResources` 从那里拷进测试）。
- 本机前提：**MSYS2**（只有编 native 用得到，见
  [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的「原生构建」）、**Android SDK**（`local.properties` 里的 `sdk.dir`，
  本机私有、已忽略）。
