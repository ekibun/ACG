cmake_minimum_required(VERSION 3.7 FATAL_ERROR)

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

# 自研的 WebView2 JNI 宿主：两种形态共用同一个 environment
# （同一份 user data folder → cookie 天然统一）
#   1. 后台任务：隐藏窗口 + WebResourceRequested 全量拦截 + 脚本注入
#   2. 嵌入视图：hwnd 经 JAWT SetParent 到 AWT 组件（SwingPanel 里的 Canvas）
add_library(webview SHARED
  ${CMAKE_CURRENT_LIST_DIR}/webview.cpp
)

project(webview LANGUAGES CXX)

# WebView2.h 在 MinGW 下能直接编（实测 g++ 16.1 约 1.6s 编完），
# 但 SDK 的 C++ 辅助类（WebView2EnvironmentOptions.h）依赖 MSVC 专有的
# Microsoft::WRL::RuntimeClass，MinGW 的 WRL 只移植了 ComPtr —— 所以
# webview.cpp 里 options 传 nullptr、COM 回调自己手写。见文件头注释。
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

# WebView2Loader.dll 不做 import lib：webview.cpp 运行时会按
# 「已加载 → 本 DLL 同目录 → 裸名字」的顺序动态加载它。
target_link_libraries(webview PRIVATE
  ${common-lib}
  user32
  ole32
  oleaut32
  # IID_IUnknown 在 MinGW 的 libuuid 里，缺了会在链接期报 undefined reference。
  uuid
)

# 把 WebView2Loader.dll 拷到产物目录，让上面那一步的「同目录」那档能命中。
add_custom_command(TARGET webview POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different
          "${WEBVIEW_SDK_DIR}/x64/WebView2Loader.dll"
          "$<TARGET_FILE_DIR:webview>/WebView2Loader.dll"
)
