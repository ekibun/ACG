# cxx/AGENTS.md

只讲 `cxx/` 下的原生构建、JNI 桥与各库的硬约束。项目整体约定见 [../AGENTS.md](../AGENTS.md)；构建 / 测试见 skill `build-and-test`；
WebView2 的排查手册见 [../docs/development/webview2-windows.md](../docs/development/webview2-windows.md)；QuickJS 能力桥的设计正本见
[../docs/design/quickjs-ability-bridge.md](../docs/design/quickjs-ability-bridge.md)；通用做法（最小实现、外科手术式改动）见
根 [../AGENTS.md](../AGENTS.md) §2。

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
  ffmpeg 的 submodule 指向上游 `FFmpeg/FFmpeg`，本地改动只以 `cxx/ffmpeg/patches/hls.sed` 形式存在，
  由构建在构建目录里应用（见 [ffmpeg 一节](#ffmpeg) 的「源码改动走 sed」）。
- **重编与 dll 落位全自动，没有「手动同步」这一步**：所有消费链的任务图都挂着 `buildJni`
  （无 outputs 的 Exec，每次必跑，`cxx/` 一变就重编）—— `:desktopApp:run`（经 `prepareAppResources`）、
  `hotRun`（`hotSnapshotMain`）、IDE 直跑 MainKt（`processResources`）、打包（`syncNativeResources`）、
  `:shared:jvmTest` 与 `:platform:jvmTest`（各自 `jvmTestProcessResources` 显式 `dependsOn`）。落位五处：
  `shared/build/processedResources/jvm/test/`、`platform/build/processedResources/jvm/test/`、
  `desktopApp/build/resources/main/`、
  `desktopApp/build/compose/tmp/prepareAppResources/`（run 与打包共用 `syncNativeResources` 喂料）、
  打包态 `<app-image>/app/resources/`。打包侧 `createDistributable` 已把 `nativeResources` 登记成显式
  输入（插件的 `appResourcesDir` 是 @Internal、不进 up-to-date 判据），打出旧包时用 `--rerun` 复查。
  加载侧按什么顺序找库，正本在 `platform/src/jvmMain/kotlin/soko/ekibun/Binding.jvm.kt`
  的 `loadLibrary` KDoc；运行态反复走到兜底解包，
  就是资源布局回归。
- **Hot Reload 换不了 native 库**：`System.load` 是进程启动时做的，热重载只换 Kotlin 类 ——
  改完 `cxx/` 必须整进程重启，否则你以为在测新代码，其实跑的是旧 dll（症状：修复「没生效」）。

## 原生构建

入口只有一个：`./gradlew :desktopApp:buildJni`（或在 Android Studio 里点桌面端运行，它会触发它）。
`buildJni` 自己进 MSYS2 的 bash，并把 toolchain 的 JDK 传给 `cxx/build.jni.sh` 去 configure / cmake ——
**不用你准备任何 JDK**。唯一还要的本机私有值是环境变量 **`MSYS2_BIN`**
（`cxx/exec.cmd` 靠它进 MSYS2 的 bash；没设的话 exec.cmd 直接退出 1）。

- cmake 在 **configure 期**读 `$ENV{JAVA_HOME}/include` 与 `include/win32`（jni.h）。
  `:desktopApp:buildJni` 调 `cxx/build.jni.sh` 时，**Gradle 会把 `JAVA_HOME` 覆盖成它自己备好的
  toolchain JDK**（`gradle/gradle-daemon-jvm.properties` 钉的那份 Azul 21，本地没有自动下，
  **带 `include/`**）—— 所以 jni.h 自动可得，你**不用、也不该**自己准备 / 指定任何 JDK。
  全局 `JAVA_HOME`（如 Android Studio 的 jbr）在这里不参与，native 不会去读它。
- **走 MSYS2 自己的 login shell 时工具链是自足的**：`MSYSTEM` / `/mingw64/bin` / `/usr/bin`
  （`make` / `cmake` 在这里）/ 临时目录已就绪，无需补丁。坑在**非 login shell**
  （Git Bash、`bash -c`）：`MSYSTEM` 缺失、`c++.exe` 找不到自己的 DLL 以 `0xC0000135` **静默**死、
  `TMPDIR` 空则编译期炸 `cc1plus: Cannot create temporary file`。
  **别用 Git Bash 编 native** —— 走 `buildJni` 就绕开了。
- **Android 侧的 native 走 AGP 的 `externalNativeBuild`**：`androidApp/build.gradle.kts` 挂根
  `CMakeLists.txt`（`if (ANDROID)` 分支链 `log` 库、不加 `JAVA_HOME` include 就是给它用的），
  入口是 `:androidApp:externalNativeBuildDebug`，编 arm64-v8a / armeabi-v7a / x86 / x86_64 四个
  ABI，产物 `libffmpeg.so` 与 `libquickjs.so` 随 `assembleDebug` 打进 APK。
  它与桌面侧的 `buildJni` 是**两条独立的路** ⇒ 改了 `cxx/` **两边都要验**，
  桌面绿不代表 Android 绿（反之亦然）。

两条容易白跑一轮的流程坑：

- `cmake --build ... | grep -E 'error'` **无输出不代表成功** —— 漏掉 `error:` / 本地化的 `错误 1`
  会把编译失败看成成功。看 `tail`，或匹配 `错误|error`。
- CMake 增量对 `.cpp` 时间戳敏感；改完立即构建若只报 `Built target` 而没有编译行，`touch` 一下。

## JNI

- 符号是**不带签名的名称导出**：`Java_soko_ekibun_web_Webview2Context_<name>`。
  改了 Kotlin 侧的方法签名却用着旧 dll，**不会报错**，只会参数错位 ——
  改签名后第一件事是确认 dll 真的刷新到了运行位置。
- 绑定收在 `:platform` 模块：`platform/src/{commonMain,jvmMain,androidMain}/kotlin/soko/ekibun/jni.kt`
  与 `soko.ekibun.{quickjs, ffmpeg, web}`。webview 整层（JNI 面
  `platform/src/jvmMain/kotlin/soko/ekibun/web/Webview2Context.kt` 与控制器
  `WebView.jvm.kt`）都在这个模块。
  （2026-10-02 拍板把整个 web 文件夹移出 `acg`，JNI 符号随之从 `_acg_web_` 变 `_web_`）——
  包名别再动：那会又改 JNI 导出名，连带重编 dll。
- **加载方式三个库统一**：都走 `loadLibrary()`，它优先加载**应用资源目录 / classpath 资源目录
  里的真文件**（打包态与开发态都不解包），两条都不成立才退到 `System.loadLibrary`。
  ⇒ 「`WebView2Loader.dll` 必须与 `webview.dll` 同级」这条要求由资源布局直接满足 —— 两者在
  那些目录里本来就挨着，依赖解析优先按被加载 dll 自己所在目录找（实测如此）。
- **`webview.dll` 的两个依赖都是隐式导入**：`webview.cmake` 把 `WebView2Loader.dll` 与
  `$JAVA_HOME/bin/jawt.dll` **直接当链接输入**（GNU ld 读导出表就地合成导入表；官方 SDK 与
  JDK 都只给 dll，`.lib` 要么在 NuGet 里、要么是 MSVC 的 COFF 归档 GNU ld 读不了）。
  ⚠️ 后果是解析失败发生在**映射那一刻**，表现为 `System.load` 抛
  `UnsatisfiedLinkError`、**整个 JVM 起不来**，native 侧没有机会报错。
  `Webview2Context.kt` 伴生对象的 `init` 因此**先加载两个依赖再加载 webview**
  （已加载模块排在搜索路径之前，`jawt.dll` 靠 `java.library.path` = `java.home\bin` 那一档
  命中）—— **顺序反了就不成立**，别把这三行调换。
- native 调用都要回 `dispatcher` 线程（`QuickJS.kt` 里的做法照抄）。
- **Kotlin 侧 `external fun` 声明在哪，决定 JNI 名**（可见性不影响）：
  - 声明在**类体**里 → 编译成**实例**原生方法，JNI 名就是外层类名
    （`Java_soko_ekibun_ffmpeg_AvFrame_closeNative`）；
  - 声明在 **`companion object`** 里 → 原生方法挂在 `Xxx$Companion` 上，JNI 名多一段
    `_00024Companion`，**必须加 `@JvmStatic`** 才让名字落回外层类。漏了注解不会编译错，
    只在**真的调**的时候抛 `UnsatisfiedLinkError`（历史上 `AvFrame.closeNative` 就是这样，
    `av_frame_free` 因此从来没跑成过）。
  - ⚠️ **但 `@JvmStatic` 还有第二个后果**：原生方法变成静态，于是**第二个 JNI 实参从「实例」
    变成「类对象」（`jclass`）**。native 侧凡把这个实参当对象用的（`GetObjectClass(thiz)`、
    存进 `->opaque` 之类）都会坏。本仓库只有 `AvFormat_initNative` 是这样 —— 它把 `thiz`
    存进 `AVFormatContext::opaque`，再在 `io_open` 回调里读它的 `io` 字段；静态化后
    `GetObjectClass` 拿到 `java.lang.Class`、`GetFieldID` 抛 `NoSuchFieldError`，
    接着 JVM 直接 `EXCEPTION_ACCESS_VIOLATION`（2026-09-21 实测）。所以它**留在类体里**
    当实例成员 —— 名字同样是外层类名，两全。
  - ⇒ 往这个包里加原生绑定：默认「companion + `@JvmStatic`」；**native 用到 `thiz` 就改放类体**。
- **类的全限定名（含包名）也进 JNI 符号**：符号是 `Java_<包名下划线>_<类名>_<方法名>` ——
  把声明 `external fun` 的类挪到别的包 / 改类名，符号名就变，dll 不重编就 `UnsatisfiedLinkError`
  （「不报错、只失效」那一类）。模块搬迁（如绑定层已进 `:platform`）不必重编的前提是
  **包名一字不动**；真要改包名，就走「原生构建」重编 + dll 落位。

## webview

- **不要换第三方 KMP WebView 库。** 我们的宿主需要 `WebResourceRequested` 子资源拦截、
  自定义 `AdditionalBrowserArguments` 和 CDP 访问；现成的 KMP WebView 封装把钩子挂在导航事件上，
  换过去这些能力全部丢失。自研宿主没有这个限制。
- 子资源拦截：`add_WebResourceRequested` + `AddWebResourceRequestedFilter(L"*", ALL)`，
  端到端用例 `WebViewHostTest.subResourcesAreVisibleToInterceptor`。
- JNI 面的冒烟用例 `Webview2Test`（`:platform:jvmTest`）：建上下文 → 导航 → 归还，
  只验「宿主起得来」，不锁行为细节 —— 行为细节归 `WebViewHostTest`。
- `WebView2.h` 在 MinGW 下能编，但 SDK 的 C++ 辅助类（如 `WebView2EnvironmentOptions.h`）
  依赖 MSVC 专有的 `Microsoft::WRL::RuntimeClass`，MinGW 的 WRL 只移植了 `ComPtr` —— 别去引那些头。
- **页面脚本注入为零**：`initScript` 已整体删除，`put_IsWebMessageEnabled` 恒 `FALSE`。
- **成功才报 `DocumentCompleted` / 历史栈变化**：失败那轮没有新文档，历史栈也没动。
- **先接父链、再改窗口样式**：反过来，窗口会在父链还没接上时就已经是控件形状。
- **子窗口坐标相对父窗口客户区**：位置恒为 `(0, 0)`，尺寸由 Kotlin 侧给。
- 崩溃 / 白屏 / 焦点问题，**先读** [`../docs/development/webview2-windows.md`](../docs/development/webview2-windows.md)，
  不要重新发明排查路径。

## quickjs

- 所有权模型是**显式引用计数**：每造一个跨 JNI 的句柄就是一票，`dup` 加票、归还减票，
  销毁 runtime 时必须**没有残留**（`JS_FreeRuntime` 会 `assert(list_empty(&rt->gc_obj_list))`，
  漏任何一票就是整个进程 `abort()`，Gradle 只报一句结果、`failures=0`，极具欺骗性）。
  规则已由代码结构保证 —— **`Pointer` 基类 + `QuickJS.releaseImpl` 的清算就是正本**，照着改：
  - `JS_DefinePropertyValue` 内部**无条件**消费 `val` 的引用，调用方**不要**再减一次
    （`quickjs.cpp` 把 `jsReleaseValue` / `jsDestroyHandle` 拆成两个粒度就是为它，注释里有说）。
  - `jsToJava` 是**唯一**的对象/标量分发点，递归点必须走它（历史上把对象分支移出去过一次，
    症状是「数组元素全是 null」「promise 没有 then」）。
  - **引用必须在 runtime 销毁之前归还，且归还路径不能经过 `isClosed` 门**：`releaseRef`
    别包进 `withPtrSync`（包了 `collectLeaks()` 那轮清算就被挡掉 → 残留撑到 `JS_FreeRuntime` → abort），
    也别把归还 `submit` 到 `releaseImpl`（销毁）之后（那就是对着已销毁 runtime 释放 → use-after-free）。
  - **native 访问只能在归属线程**：`Pointer.withPtr` / `withPtrSync` 会自动投递到 dispatcher
    （已在归属线程则就地执行）—— **不要绕过它裸读 `ptr`**，新建 `Pointer` 子类也别漏传 dispatcher。
  - **tag 别写死数值**：Kotlin 侧的 `JS_TAG_OBJECT` 是 **5**（C 侧反向映射后的返回值，
    QuickJS 原始 tag 不出 native）。`-1` 是 C 头文件里的值，**Kotlin 侧根本看不到** ——
    照 `-1` 判断会漏判对象。要判类型走 `jsGetTag` 的分派器。
- **`import()` / `require()` 一律不许用**：本桥的模块加载路径会把进程 `abort()`（见
  [`../docs/development/playback-invariants.md`](../docs/development/playback-invariants.md) 的 quickjs 桥一节）。
  能力全部内联进 `init.js`（脚本位置见 [../AGENTS.md](../AGENTS.md) §2.5）。
- **类析构回调的顺序是「先 `close()` 再放全局引用」**：`autoCloseableClass` 必须在
  `JS_NewClass` **之前**取好（回调随时可能跑），`destroyContext` 里要等
  `JS_FreeContext` / `JS_FreeRuntime` 都跑完才删它 —— 析构回调期间还要用它做
  `IsInstanceOf`。先放引用就再也拿不到 `jobject`，`close()` 无从调起。
- **栈预算默认 256KB**（`JS_SetMaxStackSize`）：`stack_limit = stack_top - stack_size`，
  而 `stack_top` 已落在 JNI 帧里，预算必须远低于宿主线程栈。
- **模块 loader 返回 NULL 时必须先挂一个待处理异常**（`JS_ThrowReferenceError`），
  否则 `JS_LoadModuleInternal` 会去报运行期里残留的旧异常。
- **析构回调绝不能往外抛**：它跑在 GC 时机上，没有调用者能接，异常只能就地清。
- **`JS_NewArrayBufferCopy` 会复制字节**：返回前必须把 Java 数组那一票放掉。
- **`TypeError` 一类异常必须就地清掉**：留着会让后面每个「返回 NULL 却不抛」的 API 都带着旧异常。
- **`JS_NewPromiseCapability` 往数组里写两个新持有**：resolve / reject 各造一个 `JSFunction`，别共用一票。

## ffmpeg

- **源码改动走 sed，不碰 submodule**：submodule 指向上游原版，本地唯一改动是
  `cxx/ffmpeg/patches/hls.sed`（把 `hls.c` 的 `open_url` 协议检查连同只在该段用到的
  `proto_name` 声明包进 `#if CONFIG_NETWORK`）。`patches/configure.sh` 就是 ExternalProject 的
  `CONFIGURE_COMMAND`：先跑 configure，再把 `hls.c` 拷成构建目录的 `libavformat/hls.patched.c`、
  用 sed 改它，并把构建目录的 `Makefile` 改写成把 `hls.o` 指向副本（该 Makefile 由 configure
  写出、已存在时不覆盖，所以扛得住 reconfigure）。submodule 一个字节都不动。
  改这条链路时的硬约束：
  - **sed 匹配不到不报错**，改动会静默消失。脚本末尾用 `grep -c '^#if CONFIG_NETWORK$'`
    必须为 1 兜底 —— 上游改了锚点就在这里失败，别把这道校验删掉。
  - **锚点必须唯一**：`int is_http = 0;` 在文件里出现两次、`HLSContext *c = s->priv_data;`
    九次，裸匹配会多插。`hls.sed` 因此把插入限定在 `open_url` 签名行到下一个顶格 `}` 之间；
    删声明那条靠整行精确匹配（全文件唯一）。加新改动先数一下锚点出现次数。
  - 副本不在源码树里，`hls.c` 的 `#include "avformat.h"` 要靠
    `CCFLAGS += -I$(SRC_PATH)/libavformat -I$(SRC_PATH)`。
  - **不要手工删 `hls.o` / `hls.d`**：副本每次 configure 都重写，mtime 更新就会触发重编，
    显式规则也认新的 `hls.patched.c`（实测从旧 `hls.d` 指向 `hls.c` 的状态切过来照样重编）。
    删了反而让每次 prepare 都白编一遍。
  - sed 保持行尾不变（实测 CR count 0），不要引入会把整份文件改写的工具。
- **视频转码目标固定为 `AV_PIX_FMT_RGBA`**，写死在 `ffmpeg.cpp` 的 `postFrameVideo`
  （`av_image_get_buffer_size` / `av_image_fill_arrays` / `sws_getContext` 三处必须用同一个
  格式常量，否则 `sws_scale` 写进来的布局与缓冲区对不上）。Kotlin 侧已无像素格式常量，
  也没有 `AvSurfaceContext.videoFormat` —— 平台实现只负责把 RGBA 字节贴到自己的渲染面。
- **两端都开 asm，只有 32 位 `x86` 关**（桌面 2026-09-22 / Android 2026-10-20）：
  `ffmpeg.cmake` 里 `--disable-asm` 只在 `ANDROID` + `CMAKE_ANDROID_ARCH=x86` 时追加。
  x86/x86_64 的手写汇编要 `nasm`（构建机用 MSYS2 的 `mingw-w64-x86_64-nasm`；PATH 里没有时
  configure 直接 `die`（`configure:6875`），不是静默降级），armv7/arm64 走 clang 的 `.S`。
  32 位 x86 关着是硬限制：它的汇编**和** C 内联汇编都只出 32 位绝对重定位，链接出带 textrel 的
  .so，而 Android 从 API 23 起拒绝加载 —— 没有干净的开关，别试着用 `-z notext` 掩盖。
- **静态库进我们的 `.so` 必须 `-Wl,-Bsymbolic`**（Android 分支，`ffmpeg.cmake`）：汇编用
  PC 相对 / ADRP 直接引用 C 侧常量符号（`ff_pw_32` 之流），这些符号默认可抢占，链接器不肯就地
  解析（`relocation R_*_PC32 / ADR_PREL_PG_HI21 cannot be used …; recompile with -fPIC`）。
  上游链接自己的 libav*.so 同样靠它（configure 把它放进 `SHFLAGS`，`configure:8033`）。
  删掉它 x86_64 / arm64 立刻链接失败，与「像素有没有差异」无关。
- 开启后输出像素与纯 C 路径**不是逐比特一致**（RGB 有 1~3 LSB 舍入差，alpha 恒等）⇒ 像素比对
  别用哈希：用「帧数 / 时戳 / 尺寸 / alpha 相等 + RGB ≤ 3 LSB」。
- **`SwsContext` / `SwrContext` 都按「源格式 + 尺寸」缓存，缓存键必须回写**：`postFrameVideo`
  比的是 `_srcVideoFormat`（对应 `postFrameAudio` 的 `_srcAudioFormat` + 声道布局 + 采样率）。
  漏了回写**编译和调用都不会失败**，只会让那个条件**每一帧都成立** → 逐帧
  `sws_getContext` / `swr_init`，白烧 CPU 并把日志刷满：`No accelerated colorspace conversion found from yuv420p to rgba.`
  是 `sws_init_context` 路径里的 `ff_yuv2rgb_get_func_ptr` 打出来的，**它的条数就等于 sws
  上下文被重建的次数** —— 日志里刷出几千条时先查上下文缓存，别去查颜色空间
  （2026-09-22：这条线索把 `_srcVideoFormat` 漏回写钉了出来）。
- 音频不一样：`AvSurfaceContext.audioFormat` 仍是平台**要求** native 输出什么采样格式，
  语义见 [../AGENTS.md](../AGENTS.md) §2.5。
- 自定义 AVIO 下的 seek 能力由 `HttpIO.seek` 的模拟质量决定（`aviobuf.c` 会因 seek 回调非空
  判为 `AVIO_SEEKABLE_NORMAL`）；语义有专属用例 `HttpSeekSemanticsTest` /
  `SeekWindowSemanticsTest`，动这块必须让它们保持绿。
- **avio 初始缓冲的 32768 是 `ffmpeg.cpp` 的 `io_open` 里直接写死的**，与 `IO_BUFFER_SIZE`
  （`cxx/ffmpeg/ffmpeg/libavformat/aviobuf.c:36`）对齐，不是随手取的整数。它**只管初始值**：
  MP4 源在 `mov_read_header` 末尾会被按索引换掉（`ff_configure_buffers_for_index`），实际大小
  由文件决定。Kotlin 侧没有这个数 —— 别再往 `AvIO` 上加回反射方法。
- **`AvIO.read(buf, len)` 必须按 `len` 截断，不许按 `buf.size` 填满**：native 侧每个 pb 只持有
  **一个复用数组**（`IoOpaque::arr`，按需扩容、只增不减），它的容量是历史最大请求量，通常远大于
  这次请求（`fill_buffer` 要的是「缓冲剩余容量」，MP4 源能被放大到 MB 级）。按 `buf.size` 读会
  报出比请求量还多的字节 —— `fill_buffer` 拿返回值定 `buf_end`，等同谎报；native 侧只能把
  `ret > buf_size` clamp 回去并打一条 `AvIO read over-ran`（每轮读一条）。同款「谎报读满」在
  `HttpIO` 的作废分支里，那里返回的是 `len`，别改回 `buf.size`。
- **`pb->opaque` 是 `IoOpaque*`，不是裸 `jobject`**（里面装着 AvIO 的 GlobalRef、复用数组、
  缓存的三组 methodID）。读/seek/关三条路径都从它取，`closeIoContext` 负责整体释放 ——
  加字段时记得在那一处收干净，HLS 一次 open 会开/关多个 pb。
- **前向「白读 vs 重开」不归 Kotlin 管**：`aviobuf.c:281-289` 先用 `short_seek_threshold`
  （`:43`，默认 32768，mov 只会照索引往上抬）把近跳用 `fill_buffer` 吸收成顺序读，交到
  `HttpIO.seek` 的只能是长跳 ⇒ IO 层不设阈值（`HttpIO` 那个 `> 0` 判的是「还没取到的部分
  非零」，不是阈值），只负责「重开一条落在落点上的会话，服务端给不出就保留旧会话退回白读」。
- **`AvIO.Handler.open` 不许抛异常**：它跑在 native 的 `io_open` 回调里，Kotlin 异常会
  pending 在 JNI 上没人接，下一个 JNI 调用直接 abort 整个 JVM（hs_err 的 Problematic frame
  在 `initNative`）。「打不开」要学 `FileIO`：返回句柄为 null 的 IO（read/seek 恒 -1），
  让 `avformat_open_input` 自己以 I/O 错误收场 —— 失败清理路径照常走完。
- **`io_open` 建的 pb 要记进账本、`io_close2` 必须先把 pb 销账再关**：账本
  `FormatOpaque::pbs` 是**活集合、不是单槽**，因为 HLS 一次 open 里会连续开
  master / variant / segment 多个 pb，而 CUSTOM_IO 下 ffmpeg 不替调用方关 pb。
  单槽记账、或 `io_close2` 不销账，都会让 `avformat_open_input` 的失败路径**二次关闭**
  一个已被 `io_close2` 释放的 pb —— `pb->opaque` 读出垃圾当 jobject 交给
  `GetObjectClass`，`EXCEPTION_ACCESS_VIOLATION` 崩在 `initNative`（症状只在
  「read_header 中途开嵌套源又失败」时出现，mp4 与正常 HLS 都不崩）。
  同理 `destroyNative` 要**先 `avformat_close_input` 再收账**：`read_close`（如
  `hls_close`）会把 pb 交回 `io_close2`，先收账就是拿悬垂指针再关一遍。
- 解码路径已经处理 `EAGAIN` 并带 drain（native `ffmpeg.cpp` + Kotlin `AvCodec.drain()`，
  `FFPlayer` 在 EOF 主动 drain）。**不要**再把它当成"未实现"去重写。
- **自定义 pb 不能走 `avio_close`**：它把 `s->opaque` 当 `URLContext*` 交给 `ffurl_close`，
  而我们的 opaque 是 `IoOpaque*` —— 类型混淆直接访问违例。buffer 由调用方 `av_free`，
  上下文本体交 `avio_context_free`（它只还 struct，不管 buffer）。
- **`avformat_open_input` 失败路径要自己收尾**：CUSTOM_IO 下 ctx 已被 ffmpeg free 掉，
  `destroyNative` 不会再被调，账本剩余的 pb、`thiz` 的 GlobalRef 与 `FormatOpaque` 都要在
  `initNative` 里释放，且只释放账本上剩下的（`io_close2` 已销账的不得再关）。
- **读回调的错误语义**：`AVERROR_EOF` 表示 EOF，其余负返回值必须打日志（`fill_buffer`
  对任何负值都置 `eof_reached`，不打日志时 IO 故障在外部看来就是「播完了」）；返回值超过
  请求长度时截断到请求长度。
- **`getBufferNative` 返回的内存只在本轮有效**：下一帧 `sws_scale` / `swr_convert` 会就地
  覆写，调用方必须同步消费、不能留存引用。跨 JNI 走 `byte[]` 而不是 `DirectByteBuffer`
  （根 [../AGENTS.md](../AGENTS.md) §2.5：commonMain 不许出现 `java.nio.ByteBuffer`）。
- **音频输出有两块缓冲**（`audioBuffer` 与 `_audioBuffer1` 在 `postFrameAudio` 末尾交换），
  `closeNative` 两块都要 `av_free`，视频缓冲同理。
- **`send_packet` 的 EAGAIN 重试上限是 16 次**：重试耗尽仍失败时打日志并交给调用方收场，
  不能静默丢包（丢一包就断参考链，之后整个 GOP 画面成块）。
- **`getStreamsNative` 把 `stream->time_base` 一并传给 Kotlin**：容器级 seek 把微秒折回刻度时
  会吃掉半格以内的偏移，上层算步长需要这个最小刻度。
