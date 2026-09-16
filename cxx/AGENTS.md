# cxx/AGENTS.md

只讲 `cxx/` 下的原生构建、JNI 桥与各库的硬约束。项目整体约定见 [../AGENTS.md](../AGENTS.md)（含
dll 落位三处、构建命令、完成标准）；WebView2 与 QuickJS 的深水手册在
[`../.agents/skills/`](../.agents/skills/) 下对应的技能里。

产物是三个共享库，各自被同名 `.cmake` 引入，源码在 `cxx/{webview,quickjs,ffmpeg}/`：

| 目标 | 作用 |
|---|---|
| `webview` | 桌面端 WebView2 宿主（后台页也复用它） |
| `quickjs` | JS 引擎桥（对应 `soko.ekibun.quickjs`） |
| `ffmpeg` | 自定义 AVIO + 解码 + 播放（对应 `soko.ekibun.ffmpeg`） |

- 产物落在 `cxx/build/bin/`，**不带 `lib` 前缀**：根 `CMakeLists.txt` 设了
  `CMAKE_SHARED_LIBRARY_PREFIX ""`；输出目录 `bin` 由 `build.jni.sh` 的
  `-DLIBRARY_OUTPUT_PATH=bin` 给。文件名就是 `webview.dll` / `quickjs.dll` / `ffmpeg.dll`。
- `cxx/ffmpeg/ffmpeg/`、`cxx/quickjs/quickjs/` 是 **git submodule**（完整上游源码树）。
  找参考实现（如 `fftools/ffplay.c`）**直接读本地文件，不要联网下载**；也不要修改它们。

## 原生构建

入口只有一个：`./gradlew :desktopApp:buildJni`（或在 Android Studio 里点桌面端运行，它会触发它）。
`buildJni` 自己进 MSYS2 的 bash，并把 toolchain 的 JDK 传给 `cxx/build.jni.sh` 去 configure / cmake ——
**不用你准备任何 JDK**。

- cmake 在 **configure 期**读这份 JDK 的 `$JAVA_HOME/include` 与 `include/win32`（jni.h）。
  Gradle 给的那份是**带 `include/` 的完整 JDK**（`gradle/gradle-daemon-jvm.properties` 钉的那份，
  本地没有 Gradle 会自己下），所以 jni.h 自动可得。**注意**：只有 `bin/` 没有 `include/` 的 JDK
  （比如 Android Studio 自带的 jbr）拿去当 `JAVA_HOME` 会直接 `jni.h: No such file`。
- **走 MSYS2 自己的 login shell 时工具链是自足的**：`MSYSTEM` / `/mingw64/bin` / `/usr/bin`
  （`make` / `cmake` 在这里）/ 临时目录已就绪，无需补丁。坑在**非 login shell**
  （Git Bash、`bash -c`）：`MSYSTEM` 缺失、`c++.exe` 找不到自己的 DLL 以 `0xC0000135` **静默**死、
  `TMPDIR` 空则编译期炸 `cc1plus: Cannot create temporary file`。
  **别用 Git Bash 编 native** —— 走 `buildJni` 就绕开了。
- **Android 侧目前没有构建入口**：`androidApp/build.gradle.kts` 里没有 `externalNativeBuild` /
  ndk 配置，根 `CMakeLists.txt` 的 `if (ANDROID)` 分支（链 `log` 库、不加 `JAVA_HOME` include）
  暂时没人调用。要把 native 带进 Android 得自己补 CMake/AGP 配置。

两条容易白跑一轮的流程坑：

- `cmake --build ... | grep -E 'error'` **无输出不代表成功** —— 漏掉 `error:` / 本地化的 `错误 1`
  会把编译失败看成成功。看 `tail`，或匹配 `错误|error`。
- CMake 增量对 `.cpp` 时间戳敏感；改完立即构建若只报 `Built target` 而没有编译行，`touch` 一下。

## dll 同步

改完 native **必须**把 dll 同步到位（落位三处见 [../AGENTS.md](../AGENTS.md) §5），
源是 `cxx/build/bin/<name>.dll`：

1. `shared/build/processedResources/jvm/test/` —— 跑 `:shared:jvmTest` 时由 `jvmTestProcessResources` 自动刷
2. `desktopApp/build/resources/main/` —— 由 `:desktopApp:processResources` 刷
3. `desktopApp/build/run/main/classpath/classes/` —— **没有任何任务写这里，必须手动拷**

漏掉第 3 处的症状是"改动没生效、连日志都没有"，最容易被误判成代码问题。

## JNI

- 符号是**不带签名的名称导出**：`Java_soko_ekibun_acg_web_NativeWebView_<name>`。
  改了 Kotlin 侧的方法签名却用着旧 dll，**不会报错**，只会参数错位 ——
  改签名后第一件事是确认 dll 真的刷新到了运行位置。
- 绑定分别在 `shared/src/{commonMain,jvmMain,androidMain}/kotlin/soko/ekibun/jni.kt`
  与 `soko.ekibun.{quickjs, ffmpeg}`。
- **加载方式不统一，别想当然**：`jniLoadLibrary()` 把每个 dll 各解到一个独立临时文件；
  而 `NativeWebView` 自己做内容哈希分桶目录加载，因为 `webview.dll` 必须与
  `WebView2Loader.dll` **同级**才能启动。
- native 调用都要回 `dispatcher` 线程（`QuickJS.kt` 里的做法照抄）。

## webview

- **不要换第三方 KMP WebView 库。** 我们的宿主需要 `WebResourceRequested` 子资源拦截、
  自定义 `AdditionalBrowserArguments` 和 CDP 访问；现成的 KMP WebView 封装把钩子挂在导航事件上，
  换过去这些能力全部丢失。自研宿主没有这个限制。
- 子资源拦截：`add_WebResourceRequested` + `AddWebResourceRequestedFilter(L"*", ALL)`，
  端到端用例 `NativeWebViewHostTest.subResourcesAreVisibleToInterceptor`。
- `WebView2.h` 在 MinGW 下能编，但 SDK 的 C++ 辅助类（如 `WebView2EnvironmentOptions.h`）
  依赖 MSVC 专有的 `Microsoft::WRL::RuntimeClass`，MinGW 的 WRL 只移植了 `ComPtr` —— 别去引那些头。
- **页面脚本注入为零**：`initScript` 已整体删除，`put_IsWebMessageEnabled` 恒 `FALSE`。
- 崩溃 / 白屏 / 焦点问题，**先读** [`../.agents/skills/webview2-windows/references/webview2-windows.md`](../.agents/skills/webview2-windows/references/webview2-windows.md)（或触发 skill `webview2-windows`），
  不要重新发明排查路径。

## quickjs

- 所有权模型是显式引用计数，完整规则见
  [`../.agents/skills/quickjs-ownership/references/quickjs-reference-ownership.md`](../.agents/skills/quickjs-ownership/references/quickjs-reference-ownership.md)
  （或触发 skill `quickjs-ownership`）。动 `JS_FreeValue` / `JS_DupValue` 之前先读它。
- 三条最容易踩死的（漏了会改错）：
  - `JS_DefinePropertyValue` 内部**无条件**消费 `val` 的引用，调用方不要再减一次；
  - `jsToJava` 是**唯一**的对象/标量分发点，递归点必须走它（历史上把对象分支移出去过一次，
    症状是"数组元素全是 null"、"promise 没有 then"）；
  - `tag = -1` 是**正常的对象**（`JS_TAG_OBJECT = -1`），不是异常。
- **`import()` / `require()` 一律不许用**：本桥的模块加载路径会把进程 `abort()`。
  能力全部内联进 `init.js`（位置见 [../AGENTS.md](../AGENTS.md) §2）。

## ffmpeg

- `AvPlayback.audioFormat` / `videoFormat` 是**平台要求 native 输出什么格式**，不是
  native 实际输出什么；两端可以不同（Android 的 `ENCODING_PCM_8BIT` 是有意为之）。
- `videoFormat` 必须是 `AV_PIX_FMT_RGBA = 26`。**不要写 25** —— 那是 `AV_PIX_FMT_ARGB`，通道序不同。
- 自定义 AVIO 下的 seek 能力由 `HttpIO.seek` 的模拟质量决定（`aviobuf.c` 会因 seek 回调非空
  判为 `AVIO_SEEKABLE_NORMAL`）；语义有专属用例 `HttpSeekSemanticsTest` /
  `SeekWindowSemanticsTest`，动这块必须让它们保持绿。
- 解码路径已经处理 `EAGAIN` 并带 drain（native `ffmpeg.cpp` + Kotlin `AvCodec.drain()`，
  `FFPlayer` 在 EOF 主动 drain）。**不要**再把它当成"未实现"去重写。
