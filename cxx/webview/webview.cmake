cmake_minimum_required(VERSION 3.13.0)

# webview —— Windows 专用（WebView2）。其它平台没有源码，直接跳过，
# 免得拖累 macOS/Linux 上的 configure。
#
# **别用 `WIN32` 判断**：MSYS2 里跑的 `cmake` 是 msys 子系统的构建，
# `CMAKE_SYSTEM_NAME` 是 "MSYS"、`WIN32` 为假 —— 但它调用的编译器是
# `/mingw64/bin/g++`，产出的就是原生 Windows DLL。所以这里认「系统名属于
# Windows 系」而不是 `WIN32`。
if (NOT CMAKE_SYSTEM_NAME MATCHES "^(Windows|MSYS|CYGWIN)")
  return()
endif ()

set(WEBVIEW_SDK_DIR ${CMAKE_CURRENT_LIST_DIR}/sdk)

# 自研的 WebView2 JNI 宿主：**environment 进程内共享** —— 一条消息泵线程 +
# 一个 `ICoreWebView2Environment`（一个浏览器进程），每个 `WebviewContext` 只
# 在其中开自己的窗口 + controller，引用计数归零才收摊。
#
# 为什么不能一个 context 一个 environment：WebView2 **不允许同一 user data
# folder 上并存多个 environment**，而我们恰恰要求后台任务与可见视图共享
# cookie。实测症状还不稳定 —— `CreateCoreWebView2Controller` 报
# `0x80070578`（`ERROR_INVALID_WINDOW_HANDLE`），或者 controller 建起来了但
# 页面加载卡到超时。共享之后两种形态就是同一个 environment 下的两个窗口：
#   1. 后台任务：1×1 隐藏窗口 + WebResourceRequested 全量拦截 + 脚本注入
#   2. 嵌入视图：hwnd 经 JAWT SetParent 到 AWT 组件（SwingPanel 里的 Canvas）
add_library(webview SHARED
  ${CMAKE_CURRENT_LIST_DIR}/webview.cpp
)

project(webview LANGUAGES CXX)

# WebView2.h 在 MinGW 下能直接编（实测 g++ 16.1 约 1.6s 编完），
# 但 SDK 的 C++ 辅助类（WebView2EnvironmentOptions.h）依赖 MSVC 专有的
# Microsoft::WRL::RuntimeClass，MinGW 的 WRL 只移植了 ComPtr —— 所以 COM 回调
# 自己手写（见 webview.cpp 的 `ComCallback` 基类）。options 也是同一个路子：
# `webview.cpp` 里（类 `WebviewEnviorment`，原文拼写如此）手写了一个最小的
# `ICoreWebView2EnvironmentOptions` COM 对象（零 WRL 依赖），浏览器参数由 JVM 侧
# 以**构造参数**给进来（线程在构造函数末尾就起来了，事后 setter 是一场竞态），
# 不再传 nullptr。
target_include_directories(webview PRIVATE
  ${WEBVIEW_SDK_DIR}/include
)
target_compile_definitions(webview PRIVATE
  NOMINMAX
  WIN32_LEAN_AND_MEAN
  UNICODE
  _UNICODE
  # WebView2 只在 Windows 10+ 上跑，所以直接把 SDK 目标定到 0x0A00。
  # 不定的话 MinGW 默认头文件里 Win10 那批 API（DPI awareness、`WM_DPICHANGED`
  # 相关的辅助函数等）根本不出现，用的时候编译期直接报 not declared。
  WINVER=0x0A00
  _WIN32_WINNT=0x0A00
)
target_compile_features(webview PUBLIC cxx_std_17)

target_link_libraries(webview PRIVATE
  ${common-lib}
  user32
  ole32
  oleaut32
  # IID_IUnknown 在 MinGW 的 libuuid 里，缺了会在链接期报 undefined reference。
  uuid
  # 直接把 dll 交给链接器 —— GNU ld 会读它的导出表就地合成导入表，不必另备
  # import lib（官方 SDK 只随包发 dll，`.lib` 在 NuGet 包里）。
  "${WEBVIEW_SDK_DIR}/x64/WebView2Loader.dll"
  # 同上处理 jawt.dll：JDK 那份 `lib/jawt.lib` 是 MSVC 的 COFF 归档，GNU ld 读不了。
  "$ENV{JAVA_HOME}/bin/jawt.dll"
)

# WebView2Loader.dll 必须与 webview.dll 同级：隐式导入是 WebView2 自己按导入表
# 解析的，而 JNI 库由 System.load 从资源目录拉起，那段目录不在默认搜索路径里。
# jawt.dll 不必同级 —— `WebviewContext.kt` 会先用 `System.loadLibrary` 加载它
# （`java.library.path` 默认就是 `java.home\bin`），「已加载模块」优先于搜索路径。
add_custom_command(TARGET webview POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different
          "${WEBVIEW_SDK_DIR}/x64/WebView2Loader.dll"
          "$<TARGET_FILE_DIR:webview>/WebView2Loader.dll"
)
