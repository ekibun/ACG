/*
 * webview.cpp —— 自研的 Windows WebView2 原生宿主
 *
 * 请求钩子直接接在引擎的 `add_WebResourceRequested` +
 * `AddWebResourceRequestedFilter(L"*", ALL)` 上：含全部子资源，也拿得到
 * `Range` 这类请求头。
 *
 * 两种用法共用**同一个 environment**（因此同一份 user data folder、
 * 同一个浏览器进程、**同一份 cookie**）：
 *
 *   后台任务（background task）
 *     隐藏顶层窗口 + 拦截 + 注入脚本 + 一次性取结果。对齐 Android 侧
 *     「用完即弃」的语义。
 *
 *   嵌入视图（embedded view / 可见 WebView）
 *     建一个独立 HWND（先是隐藏的顶层 `WS_POPUP`），由 `nativeViewAttach`
 *     经 JAWT 从 AWT 组件（`SwingPanel { Canvas() }` 里的 Canvas）取到父 HWND，
 *     `SetParent` + 改 `WS_CHILD` 挂进去，尺寸由 `fitViewToParent`
 * 按父窗口客户区对齐 —— 完全不依赖任何 UI 框架的原生互操作层。
 *
 * 结构（照 cxx/quickjs/quickjs.cpp 的路子，直接编成 SHARED 库、导出 JNI）：
 *
 *   1. 一条**专用的 WebView 线程**，`CoInitializeEx(APARTMENTTHREADED)`
 * 后建一个永不显示的顶层窗口当消息泵宿主，再在同一线程上建
 *      `ICoreWebView2Environment`。
 *
 *   2. WebView2 的 COM 对象是 STA、线程亲和的，只能在创建它的线程上调用；而窗口
 *      的消息也只会在创建它的线程上派发。JNI 是从随便哪条 JVM 线程进来的，所以
 *      一切「建窗口 / 调 COM」都经由 `postTask()` 投到 WebView 线程执行。
 *
 *   3. WebView2 的事件回调**一律不直接干活**，而是 `postTask` 出去再干 —— 在
 * COM 回调里 `Close()` controller 是文档明确不支持的（可能死锁）。唯一的例外是
 *      请求拦截回调，它必须**同步**给出结论（见 RequestHandler::Invoke）。
 *
 * WebView2Loader.dll 不引入 import lib，运行时按「已加载 → 本 DLL 同目录 →
 * 裸名字」 的顺序 `LoadLibrary`。JNI 库是被 `System.load`
 * 到临时目录的，那个目录不在任何默认搜索路径里，所以「本 DLL
 * 同目录」这一档是必需的。
 */

#include <jni.h>
#include <objbase.h>
#include <windows.h>
#include <wrl/client.h>
// JAWT：从 AWT 组件上取原生 HWND，用来把宿主窗口 SetParent 进去。
// 头文件在 $JAVA_HOME/include{,/win32}（cxx/CMakeLists.txt 已经加进 include
// 路径）， 但**不链接** jawt.lib —— 和 WebView2Loader 一样运行时 LoadLibrary。
// jawt_md.h 依赖前面已 include 的 windows.h。
#include <jawt.h>
#include <jawt_md.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cwchar>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "WebView2.h"

using Microsoft::WRL::ComPtr;

// ---------------------------------------------------------------------------
// 调试日志
//
//   ACG_WEBVIEW_DEBUG=1        → 日志走 stderr（控制台能直接看到）
//   ACG_WEBVIEW_LOG=<路径>     → 日志**追加**到那个文件
//
// 只写其中一份：给了 ACG_WEBVIEW_LOG 就写文件，否则写 stderr（见下面
// `WV_LOG`）。为什么要文件：这是个 native DLL，在 GUI 程序里 stderr
// 没人接；而且 JVM 侧 `System.load` 进来的库和宿主各有各的 CRT，stderr
// 不一定是同一个句柄，日志很容易「写了但看不到」。
// ---------------------------------------------------------------------------
static FILE* logFile() {
  static FILE* f = []() -> FILE* {
    char path[MAX_PATH]{};
    if (GetEnvironmentVariableA("ACG_WEBVIEW_LOG", path, sizeof(path)) == 0)
      return nullptr;
    return std::fopen(path, "a");
  }();
  return f;
}

static bool debugEnabled() {
  static const bool on = [] {
    char buf[16]{};
    return GetEnvironmentVariableA("ACG_WEBVIEW_DEBUG", buf, sizeof(buf)) > 0;
  }();
  return on || logFile() != nullptr;
}

/** 日志用的相对时间戳（从模块加载算起）。看时序问题全靠它。 */
static long long logNowMs() {
  static const auto start = std::chrono::steady_clock::now();
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now() - start)
      .count();
}

#define WV_LOG(...)                                                 \
  do {                                                              \
    if (debugEnabled()) {                                           \
      if (FILE* lf_ = logFile()) {                                  \
        std::fprintf(lf_, "[acg-webview +%lldms] ", logNowMs());    \
        std::fprintf(lf_, __VA_ARGS__);                             \
        std::fprintf(lf_, "\n");                                    \
        std::fflush(lf_);                                           \
      } else {                                                      \
        std::fprintf(stderr, "[acg-webview +%lldms] ", logNowMs()); \
        std::fprintf(stderr, __VA_ARGS__);                          \
        std::fprintf(stderr, "\n");                                 \
        std::fflush(stderr);                                        \
      }                                                             \
    }                                                               \
  } while (0)

// ---------------------------------------------------------------------------
// WebView2Loader 的动态加载
// ---------------------------------------------------------------------------
typedef HRESULT(STDAPICALLTYPE* PFN_CreateEnvWithOptions)(
    PCWSTR, PCWSTR, ICoreWebView2EnvironmentOptions*,
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler*);
typedef HRESULT(STDAPICALLTYPE* PFN_GetBrowserVersion)(PCWSTR, LPWSTR*);

static HMODULE g_loader = nullptr;
static PFN_CreateEnvWithOptions g_pfnCreateEnv = nullptr;
static PFN_GetBrowserVersion g_pfnGetVersion = nullptr;

/** 本 DLL 自己的模块句柄 —— 用 VirtualQuery 反查，避免函数指针转 void* 的强转。
 */
static HMODULE selfModule() {
  MEMORY_BASIC_INFORMATION mbi{};
  if (VirtualQuery(reinterpret_cast<LPCVOID>(&selfModule), &mbi, sizeof(mbi)) ==
      0)
    return nullptr;
  return static_cast<HMODULE>(mbi.AllocationBase);
}

static bool ensureLoader() {
  if (g_pfnCreateEnv) return true;

  if (!g_loader) g_loader = GetModuleHandleW(L"WebView2Loader.dll");

  if (!g_loader) {
    if (HMODULE self = selfModule()) {
      wchar_t path[MAX_PATH * 2]{};
      const DWORD n = GetModuleFileNameW(self, path, MAX_PATH * 2);
      if (n > 0 && n < MAX_PATH * 2) {
        const std::wstring p(path, n);
        const size_t slash = p.find_last_of(L"\\/");
        if (slash != std::wstring::npos) {
          const std::wstring cand =
              p.substr(0, slash + 1) + L"WebView2Loader.dll";
          g_loader = LoadLibraryExW(cand.c_str(), nullptr,
                                    LOAD_WITH_ALTERED_SEARCH_PATH);
          if (g_loader) WV_LOG("loader <- %ls", cand.c_str());
        }
      }
    }
  }
  if (!g_loader) {
    g_loader = LoadLibraryW(L"WebView2Loader.dll");
    if (g_loader) WV_LOG("loader <- bare name");
  }
  if (!g_loader) {
    WV_LOG("WebView2Loader.dll not found (err=%lu)", GetLastError());
    return false;
  }

  g_pfnCreateEnv = reinterpret_cast<PFN_CreateEnvWithOptions>(
      reinterpret_cast<void*>(GetProcAddress(
          g_loader, "CreateCoreWebView2EnvironmentWithOptions")));
  g_pfnGetVersion = reinterpret_cast<PFN_GetBrowserVersion>(
      reinterpret_cast<void*>(GetProcAddress(
          g_loader, "GetAvailableCoreWebView2BrowserVersionString")));
  if (!g_pfnCreateEnv) {
    WV_LOG("CreateCoreWebView2EnvironmentWithOptions missing in loader");
    return false;
  }
  return true;
}

/** 装了 WebView2 运行时吗？返回版本号，未装则空。 */
static std::wstring runtimeVersion() {
  if (!ensureLoader() || !g_pfnGetVersion) return {};
  LPWSTR v = nullptr;
  const HRESULT hr = g_pfnGetVersion(nullptr, &v);
  std::wstring out;
  if (SUCCEEDED(hr) && v) {
    out.assign(v);
    CoTaskMemFree(v);
  }
  return out;
}

// ---------------------------------------------------------------------------
// 初始化失败的诊断信息
//
// 思路来自 wvbridge 的 `libs_helpers.cpp::build_init_error`。光给一个 HRESULT
// 数字在排查时几乎没用 —— 我们之前就吃过亏：环境创建失败只打出一句 「创建
// WebView2 环境失败（hr=0x80070005）」，看不出到底是「没装运行时」 「user data
// folder 没权限」还是「运行时自己启动崩了」。
//
// 这里把 HRESULT 的名字、系统消息、user data folder、当前运行时版本、以及一句
// 人话提示一起拼进去 —— 出问题时看一眼就知道下一步该查什么。
// ---------------------------------------------------------------------------
static std::wstring hresultName(HRESULT hr) {
  switch (hr) {
    case S_OK:
      return L"S_OK";
    case S_FALSE:
      return L"S_FALSE";
    case E_ABORT:
      return L"E_ABORT";
    case E_ACCESSDENIED:
      return L"E_ACCESSDENIED";
    case E_FAIL:
      return L"E_FAIL";
    case E_HANDLE:
      return L"E_HANDLE";
    case E_INVALIDARG:
      return L"E_INVALIDARG";
    case E_NOINTERFACE:
      return L"E_NOINTERFACE";
    case E_NOTIMPL:
      return L"E_NOTIMPL";
    case E_OUTOFMEMORY:
      return L"E_OUTOFMEMORY";
    case E_POINTER:
      return L"E_POINTER";
    case E_UNEXPECTED:
      return L"E_UNEXPECTED";
    case HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND):
      return L"ERROR_FILE_NOT_FOUND";
    case HRESULT_FROM_WIN32(ERROR_PATH_NOT_FOUND):
      return L"ERROR_PATH_NOT_FOUND";
    case HRESULT_FROM_WIN32(ERROR_MOD_NOT_FOUND):
      return L"ERROR_MOD_NOT_FOUND";
    case HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND):
      return L"ERROR_PROC_NOT_FOUND";
    case HRESULT_FROM_WIN32(ERROR_SHARING_VIOLATION):
      return L"ERROR_SHARING_VIOLATION";
    case HRESULT_FROM_WIN32(ERROR_BUSY):
      return L"ERROR_BUSY";
    case HRESULT_FROM_WIN32(ERROR_TIMEOUT):
      return L"ERROR_TIMEOUT";
    default:
      return {};
  }
}

/** HRESULT → 系统里注册的那句人话（英文/中文取决于系统语言）。 */
static std::wstring systemMessageOf(HRESULT hr) {
  // FACILITY_WIN32 的 HRESULT 要还原成 Win32 错误码才查得到消息。
  DWORD code = static_cast<DWORD>(hr);
  if (HRESULT_FACILITY(hr) == FACILITY_WIN32) code = HRESULT_CODE(hr);

  LPWSTR raw = nullptr;
  const DWORD n = FormatMessageW(
      FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
          FORMAT_MESSAGE_IGNORE_INSERTS,
      nullptr, code, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
      reinterpret_cast<LPWSTR>(&raw), 0, nullptr);
  if (n == 0 || !raw) return {};
  std::wstring msg(raw, n);
  LocalFree(raw);
  while (!msg.empty() &&
         (msg.back() == L'\r' || msg.back() == L'\n' || msg.back() == L' '))
    msg.pop_back();
  return msg;
}

/** 这个 HRESULT 通常意味着什么、下一步查什么。 */
static const wchar_t* hintOf(HRESULT hr) {
  if (hr == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)) {
    return L"找不到可用的 WebView2 运行时 —— 装 Evergreen Runtime，或确认 "
           L"WebView2Loader.dll 在位";
  }
  if (hr == E_ACCESSDENIED || hr == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED)) {
    return L"WebView2 读不到 user data folder —— 换个可写目录，并别去动它的 "
           L"ACL";
  }
  if (hr == HRESULT_FROM_WIN32(ERROR_SHARING_VIOLATION) ||
      hr == HRESULT_FROM_WIN32(ERROR_BUSY)) {
    return L"user data folder 被别的进程占着 —— 同一个目录只能有一个进程的 "
           L"WebView2 用它";
  }
  if (hr == E_FAIL) {
    return L"运行时起来又失败了 —— 看 user data folder 下的 "
           L"EBWebView/chrome_debug.log 里浏览器进程为什么 CHECK";
  }
  return nullptr;
}

/** 判断目录存不存在（WebView2 会自己建，但建不出来时得能说清是哪一层的问题）。
 */
static bool directoryExists(const std::wstring& path) {
  if (path.empty()) return false;
  const DWORD attr = GetFileAttributesW(path.c_str());
  return attr != INVALID_FILE_ATTRIBUTES &&
         (attr & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

/**
 * 拼一条能直接拿去排查的失败消息。
 *
 * 加 `stage` 是因为同一条链路上有三个不同的失败点（建环境 / 建控制器 / 取
 * ICoreWebView2），HRESULT 完全可能一样，不区分就分不出来卡在哪一步。
 */
static std::wstring describeEnvFailure(const wchar_t* stage, HRESULT hr,
                                       const std::wstring& userDataDir) {
  wchar_t buf[64]{};
  std::swprintf(buf, 64, L"0x%08lX", static_cast<unsigned long>(hr));

  std::wstring out = stage ? stage : L"WebView2 初始化失败";
  out += L"（hr=";
  out += buf;
  const std::wstring name = hresultName(hr);
  if (!name.empty()) {
    out += L" ";
    out += name;
  }
  const std::wstring sys = systemMessageOf(hr);
  if (!sys.empty()) {
    out += L"，";
    out += sys;
  }
  out += L"）";

  out += L"\n  user data folder: ";
  if (userDataDir.empty()) {
    out += L"<默认>";
  } else {
    out += userDataDir;
    if (!directoryExists(userDataDir)) out += L"（不存在，而且没建出来）";
  }
  const std::wstring version = runtimeVersion();
  out += L"\n  WebView2 运行时: ";
  out += version.empty() ? L"<没检测到>" : version;

  if (const wchar_t* hint = hintOf(hr)) {
    out += L"\n  提示: ";
    out += hint;
  }
  return out;
}

// ---------------------------------------------------------------------------
// WebView2 的浏览器参数
//
// **为什么必须带 `--disable-gpu-process-crash-limit`**（实测，2026-09-15）：
//
//   这台机器上 WebView2 的 GPU 进程一起来就 `exit_code=1` 崩掉，默认策略下
//   Chromium 会重试 6 次、然后走
//     FATAL:content\browser\gpu\gpu_data_manager_impl_private.cc:436]
//       GPU process isn't usable. Goodbye.
//   那是**浏览器进程里的 CHECK 失败**，整个浏览器进程带着 `0x80000003` 一起走。
//   宿主这边只看到「WebView2 进程异常退出（kind=0 reason=0）」—— 除了
//   `about:blank` （不需要合成器）之外的任何页面都打不开，连
//   `NavigationStarting` 都不触发。
//
//   加上这个开关之后 GPU 进程照样崩，但**不再拖垮浏览器进程**：三个端到端用例
//   从 0/3 变成 2/3（见 `shared/src/jvmTest/.../NativeWebViewHostTest.kt`）。
//
//   注意区别：`--disable-gpu` **没用**（试过）—— 它只是关掉硬件加速，GPU
//   进程照样
//   会启动、照样会崩、照样会把浏览器进程带走。真正要改的是「崩了别自杀」这条策略。
//
// 为什么走进程环境变量而不是
// `ICoreWebView2EnvironmentOptions`：选项接口要手写一套 COM 类（MinGW 的 WRL
// 没有 `RuntimeClass`，见下文 ComCallback 那段的说明），而
// `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` 是 WebView2Loader
// 在创建环境时直接读的， 浏览器子进程又从我们这儿继承环境块 ——
// 效果一样，代码只有几行。
// 已经设过的值会被**追加**而不是覆盖，方便临时替换参数做对照。
//
// **这台机器上「子进程崩溃」这件事的实测结论（2026-09-15，别再来回试）**：
//
//   症状：GPU(6)/Utility(4) 进程以 `reason=2`(`TERMINATED`) `exit=1` 无限重启；
//         一场 run 里几百到几千条。页面 `NavigationCompleted success=1`
//         但白屏。
//   对照（同一份代码、同一份 user data folder，只换启动方式/参数）：
//     | 参数 / 启动方式                    | 进程失败条数 | 页面 | 按键进页面 |
//     | 仅 `--disable-gpu-process-crash-limit`（本函数加的）| 623~4870 | 白屏 |
//     否 | | 再加 `--disable-gpu-sandbox`       | 177~186（只剩 kind=4）| 正常
//     | 否 | | 再加 `--no-sandbox`                | **0** | 正常 | **是** | |
//     不加任何参数、改用 explorer.exe 启动 | **0** | 正常 | **是** |
//
//   关键一行：`--disable-gpu-sandbox` 只治 GPU(6)，**Utility(4) 照样崩**，
//   所以「输入窗口树」起不全（没有 `Chrome_RenderWidgetHostHWND`），焦点到了
//   `Chrome_WidgetWin_1` 按键也进不了渲染器 —— 必须 `--no-sandbox` 或干净启动。
//
//   **而「干净启动」这一行说明这不是本工程的缺陷**：从 `explorer.exe`（脱离
//   agent 那条进程树）启动、一个沙箱参数都不给，进程失败就是 0，App 完全正常。
//   排除过的常见元凶：Exploit Protection 缓解项（没有）、IFEO `Debugger`
//   劫持（没有）、 AppInit_DLLs（0）、user data folder
//   位置（`%LOCALAPPDATA%\ACG\webview2`，NTFS 正常 ACL）、第三方杀软（只有
//   Windows Defender）。
//
//   ⚠️ 所以**不要把 `--no-sandbox` 写进 `kExtraBrowserArgs`** —— 那是给
//   「被 agent 进程树包着跑」这种开发场景用的临时开关，代价是关掉 Chromium 沙箱
//   （这个视图要加载任意外部网页，别默认降级）。
//   正常双击/快捷方式启动不需要任何参数；本机 WebView2 运行时本身还不稳定
//   （同一份代码，14:57 那次 0 条失败，之后几百条；最近两轮又卡在
//   `CreateCoreWebView2Controller`
//   不动），所以**别把偶发现象写成代码里的结论**。
// ---------------------------------------------------------------------------
static const wchar_t* kExtraBrowserArgs = L"--disable-gpu-process-crash-limit";

static void applyDefaultBrowserArguments() {
  const wchar_t* key = L"WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS";
  wchar_t buf[2048]{};
  const DWORD n =
      GetEnvironmentVariableW(key, buf, sizeof(buf) / sizeof(buf[0]));
  const bool haveValue = n > 0 && n < sizeof(buf) / sizeof(buf[0]);
  if (haveValue && std::wcsstr(buf, kExtraBrowserArgs)) {
    WV_LOG("浏览器参数已含 %ls，不动", kExtraBrowserArgs);
    return;
  }

  std::wstring value = haveValue ? buf : L"";
  if (!value.empty()) value += L' ';
  value += kExtraBrowserArgs;
  if (SetEnvironmentVariableW(key, value.c_str())) {
    WV_LOG("浏览器参数 = %ls", value.c_str());
  } else {
    WV_LOG("SetEnvironmentVariableW 失败 (%lu)，浏览器参数没生效",
           GetLastError());
  }
}

// ---------------------------------------------------------------------------
// 手写的 COM 回调基类
//
// MinGW 的 WRL 只移植了 ComPtr（wrl.h 里 implements.h / event.h
// 都是注释掉的）， 既没有 `Microsoft::WRL::RuntimeClass` / `Callback<>`，也没有
// `__uuidof`。 所以自己实现引用计数 + QueryInterface，IID 由子类显式传入。
// ---------------------------------------------------------------------------
template <class I>
class ComCallback : public I {
 public:
  explicit ComCallback(REFIID iid) : iid_(iid) {}

  STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (riid == IID_IUnknown || IsEqualIID(riid, iid_)) {
      *ppv = static_cast<I*>(this);
      AddRef();
      return S_OK;
    }
    return E_NOINTERFACE;
  }
  STDMETHODIMP_(ULONG) AddRef() override { return ++refs_; }
  STDMETHODIMP_(ULONG) Release() override {
    const ULONG n = --refs_;
    if (n == 0) delete this;
    return n;
  }

 protected:
  virtual ~ComCallback() = default;

 private:
  std::atomic<ULONG> refs_{1};
  const IID iid_;
};

// ---------------------------------------------------------------------------
// JVM 侧
// ---------------------------------------------------------------------------
static JavaVM* g_vm = nullptr;

static JNIEnv* envForThread() {
  if (!g_vm) return nullptr;
  JNIEnv* env = nullptr;
  const jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (rc == JNI_EDETACHED) {
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env),
                                          nullptr) != JNI_OK)
      return nullptr;
  } else if (rc != JNI_OK) {
    return nullptr;
  }
  return env;
}

static std::mutex g_cbMutex;
static jobject g_cb = nullptr;  // nativeStart 传进来的回调对象（global ref）
static jmethodID g_midIntercept = nullptr;
static jmethodID g_midFinished = nullptr;
static jmethodID g_midFailed = nullptr;
static jmethodID g_midViewEvent = nullptr;
static jmethodID g_midScriptResult = nullptr;

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------
static std::wstring toWide(JNIEnv* env, jstring s) {
  if (!s) return {};
  const jsize len = env->GetStringLength(s);
  if (len == 0) return {};
  const jchar* chars = env->GetStringChars(s, nullptr);
  if (!chars) return {};
  std::wstring out(reinterpret_cast<const wchar_t*>(chars),
                   static_cast<size_t>(len));
  env->ReleaseStringChars(s, chars);
  return out;
}

static jstring fromWide(JNIEnv* env, const std::wstring& s) {
  return env->NewString(reinterpret_cast<const jchar*>(s.data()),
                        static_cast<jsize>(s.size()));
}

/** 读一个 COM 的 LPWSTR 出参并立刻释放。 */
template <class Fn>
static std::wstring takeString(Fn&& getter) {
  LPWSTR raw = nullptr;
  std::wstring out;
  if (SUCCEEDED(getter(&raw)) && raw) {
    out.assign(raw);
    CoTaskMemFree(raw);
  }
  return out;
}

// ---------------------------------------------------------------------------
// JAWT：从 AWT 组件取原生 HWND
//
// 可见 WebView 要「长在」一个 AWT 组件里（`SwingPanel { Canvas() }`），做法就是
// 把宿主窗口 `SetParent` 到那个组件的 HWND。JAWT 是官方给的路子，wvbridge
// 也用它。
//
// 不链接 `jawt.lib`：MinGW 编 MSVC 的 import lib 很麻烦，而 `jawt.dll` 在
// `$JAVA_HOME/bin`、JVM 起来之后本来就已经映射进本进程了 —— 直接
// `GetModuleHandleW` / `LoadLibraryW` 取 `JAWT_GetAWT` 就行，和 WebView2Loader
// 一个套路。
// ---------------------------------------------------------------------------
typedef jboolean(JNICALL* PFN_JAWT_GetAWT)(JNIEnv*, JAWT*);

static HMODULE g_jawt = nullptr;
static PFN_JAWT_GetAWT g_pfnJawt = nullptr;

static bool ensureJawt() {
  if (g_pfnJawt) return true;
  if (!g_jawt) g_jawt = GetModuleHandleW(L"jawt.dll");
  if (!g_jawt) g_jawt = LoadLibraryW(L"jawt.dll");
  if (!g_jawt) {
    WV_LOG("jawt.dll 没加载起来 (err=%lu)", GetLastError());
    return false;
  }
  g_pfnJawt = reinterpret_cast<PFN_JAWT_GetAWT>(
      reinterpret_cast<void*>(GetProcAddress(g_jawt, "JAWT_GetAWT")));
  if (!g_pfnJawt) {
    WV_LOG("jawt.dll 里没有 JAWT_GetAWT");
    return false;
  }
  return true;
}

/**
 * 取 AWT 组件的原生窗口句柄；非 Windows 组件 / 还没 peer / JAWT 不可用都返回
 * nullptr。
 *
 * `Lock`/`Unlock` 必须成对，`GetDrawingSurfaceInfo` 拿到的东西必须
 * `FreeDrawingSurfaceInfo` 还回去 —— 漏了会泄漏，而且这里是在别的线程上被调的，
 * 所以**只做取句柄这一件事**，不碰任何绘制状态。
 */
static HWND awtHwndOf(JNIEnv* env, jobject component) {
  if (!env || !component || !ensureJawt()) return nullptr;

  JAWT awt{};
  awt.version = JAWT_VERSION_1_4;
  if (!g_pfnJawt(env, &awt)) {
    WV_LOG("JAWT_GetAWT 失败");
    return nullptr;
  }

  JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, component);
  if (!ds) {
    WV_LOG("GetDrawingSurface 返回 null（组件还没 peer？）");
    return nullptr;
  }

  HWND hwnd = nullptr;
  const jint lock = ds->Lock(ds);
  if ((lock & JAWT_LOCK_ERROR) == 0) {
    if (JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds)) {
      if (auto* info =
              static_cast<JAWT_Win32DrawingSurfaceInfo*>(dsi->platformInfo)) {
        hwnd = info->hwnd;
      }
      ds->FreeDrawingSurfaceInfo(dsi);
    }
    ds->Unlock(ds);
  } else {
    WV_LOG("DrawingSurface lock 失败 (0x%08X)", static_cast<unsigned>(lock));
  }
  awt.FreeDrawingSurface(ds);
  return hwnd;
}

// ---------------------------------------------------------------------------
// 一次「视图」。handle 就是窗口句柄本身。
// ---------------------------------------------------------------------------
struct View {
  HWND hwnd = nullptr;
  /** true = 后台任务（隐藏顶层窗口），false = 嵌进 AWT 组件里的可见视图。 */
  bool background = false;
  /**
   * 可见视图的**父窗口** —— 由 JAWT 从 AWT 组件上取来的那个 HWND。
   *
   * 拿到了就说明窗口已经从顶层 `WS_POPUP` 改成了它的 `WS_CHILD`，之后尺寸一律
   * 以 `GetClientRect(parent)` 为准（见 [fitViewToParent]）。后台视图恒为
   * null。
   */
  HWND parent = nullptr;

  ComPtr<ICoreWebView2Controller> controller;
  ComPtr<ICoreWebView2> webview;
  ComPtr<ICoreWebView2Environment2> env2;

  ComPtr<ICoreWebView2WebResourceRequestedEventHandler> requestHandler;
  ComPtr<ICoreWebView2NavigationStartingEventHandler> navStartHandler;
  ComPtr<ICoreWebView2NavigationCompletedEventHandler> navDoneHandler;
  ComPtr<ICoreWebView2SourceChangedEventHandler> sourceHandler;
  ComPtr<ICoreWebView2HistoryChangedEventHandler> historyHandler;
  ComPtr<ICoreWebView2DocumentTitleChangedEventHandler> titleHandler;
  ComPtr<ICoreWebView2NewWindowRequestedEventHandler> newWindowHandler;
  ComPtr<ICoreWebView2ProcessFailedEventHandler> processFailedHandler;

  EventRegistrationToken tRequest{}, tNavStart{}, tNavDone{}, tSource{},
      tHistory{}, tTitle{}, tNewWindow{}, tProcessFailed{};

  // ---- 后台任务用 ----
  /** 调用方传进来的标识 —— 后台任务的结果回调按它找收件人，避免
   *  「句柄还没返回、事件先到」的竞态。 */
  jlong token = 0;
  std::wstring url;
  std::vector<std::wstring> headers;  // 摊平的 [name, value, ...]
  std::wstring script;
  bool hasScript = false;
  bool settled = false;
  bool notified = false;

  // ---- 可见视图用 ----
  bool ready = false;
  bool destroyed = false;
  /** 控制器就绪前收到的 bounds / 导航先存着。 */
  RECT pendingBounds{-1, -1, -1, -1};
  bool hasPendingBounds = false;
  std::wstring pendingUrl;
  std::vector<std::wstring> pendingHeaders;
  /**
   * 还剩几次焦点重试（见 [requestWebViewFocus]）。只在 WebView 线程上读写。
   */
  int focusRetries = 0;
  /**
   * 本线程已经**长久**接到哪条输入队列上了（0 = 还没接）。
   * 见 [ensureThreadAttached] ——
   * 这是「能点不能打字」的最终解，别再改回临时接法。
   */
  DWORD attachedTid = 0;
};

static std::mutex g_viewsMutex;
static std::map<HWND, std::shared_ptr<View>> g_views;

static std::shared_ptr<View> findView(HWND hwnd) {
  if (!hwnd) return nullptr;
  std::lock_guard<std::mutex> lock(g_viewsMutex);
  const auto it = g_views.find(hwnd);
  return it == g_views.end() ? nullptr : it->second;
}

/** 后台任务的结果回调只拿到 token，需要反查视图。一个 token 只对应一个任务。 */
static std::shared_ptr<View> findBackgroundByToken(jlong token) {
  std::lock_guard<std::mutex> lock(g_viewsMutex);
  for (const auto& entry : g_views) {
    if (entry.second->background && entry.second->token == token)
      return entry.second;
  }
  return nullptr;
}

/** 焦点重试用的定时器 id（见 [requestWebViewFocus]）。 */
#define WV_TIMER_FOCUS 1

/**
 * 把 WebView 线程**长久**接到 AWT 线程（根窗口 `SunAwtFrame`
 * 所在线程）的输入队列上。
 *
 * ## 为什么必须「长久」，临时接一下不够
 *
 * 键盘是**按前台窗口所在线程的 `hwndFocus`** 路由的。前台窗口是 AWT 的
 * `SunAwtFrame`，宿主 `AcgNativeWebViewHost` 建在 WebView 线程上 ——
 * 两条输入队列各自记一份焦点：
 *
 * ```
 * 前景窗口 = SunAwtFrame
 * tid=<AWT>     hwndFocus = SunAwtFrame         ← 键盘落这，被 AWT 吃掉
 * tid=<WebView> hwndFocus = Chrome_WidgetWin_1  ← 我们自己认为「有焦点」
 * ```
 *
 * 只往 WebView 线程里 `SetFocus`（旧的 `ScopedThreadInput` 做法）能让我们自己的
 * `GetFocus()` 变对，但**前台那条队列的 `hwndFocus` 还是 `SunAwtFrame`** ——
 * 于是「焦点进 WebView 了」的日志刷得欢，键盘却一个字都到不了页面。
 * 而且 `ScopedThreadInput` 用完就 `AttachThreadInput(FALSE)`，队列随即分裂。
 *
 * 长久接上之后两条线程共享同一份输入状态，`MoveFocus` / `SetFocus`
 * 的结果**两条队列都认**，前台那条也就跟着指向
 * `Chrome_WidgetWin_1`，键盘才真的进渲染器。
 *
 * ## 为什么接「根窗口的线程」，不是「前台窗口的线程」
 *
 * 前台可能是**别人家 App** 的窗口；把我们的队列长久接到一个外部进程的线程上，
 * 后果不可控。根窗口（`GetAncestor(host, GA_ROOT)` =
 * `SunAwtFrame`）永远是我们自己的，同进程、同生命周期，安全。
 *
 * ## 副作用要有数
 *
 * `AttachThreadInput` 会让两边共享焦点 / 激活 / 键盘布局 / 鼠标捕获，
 * 所以 AWT 自己的输入框要抢焦点时也会正常抢到（这是**期望**行为，不是 bug）。
 * 代价是两条消息循环在 `SendMessage` 层面互相牵连 —— WebView 线程别做长阻塞。
 * 视图销毁时（[wndProc] 的 `WM_DESTROY`）会摘掉。
 */
/**
 * 长久接队列的开关。设 `ACG_WEBVIEW_NO_THREAD_ATTACH=1`
 * 关掉（退回「只在自己线程里设焦点」的老行为 ——
 * 焦点日志看着正常，但键盘进不了页面）。
 *
 * 留这个口子是因为 `AttachThreadInput` 会让两条消息循环在 `SendMessage`
 * 层面互相牵连， 万一在别的机器上出现界面卡死，可以**不重编**就直接关掉对比。
 * 环境变量只读一次（进程内缓存）。
 */
static bool threadInputAttachEnabled() {
  static int enabled = -1;
  if (enabled < 0) {
    char buf[16]{};
    const DWORD n = GetEnvironmentVariableA("ACG_WEBVIEW_NO_THREAD_ATTACH", buf,
                                            sizeof(buf));
    enabled = (n > 0 && buf[0] != '0' && buf[0] != '\0') ? 0 : 1;
  }
  return enabled == 1;
}

static void ensureThreadAttached(const std::shared_ptr<View>& view, HWND host,
                                 DWORD hostTid) {
  if (!threadInputAttachEnabled()) return;
  const HWND root = GetAncestor(host, GA_ROOT);
  const DWORD rootTid = root ? GetWindowThreadProcessId(root, nullptr) : 0;
  if (!rootTid || rootTid == hostTid) return;  // 同线程（比如纯探针场景）不用接
  if (view->attachedTid == rootTid) return;    // 已经接在这条上了

  if (view->attachedTid) AttachThreadInput(hostTid, view->attachedTid, FALSE);
  if (AttachThreadInput(hostTid, rootTid, TRUE)) {
    view->attachedTid = rootTid;
    WV_LOG("view %p: 输入队列已接到 AWT 线程 (本线程 %lu -> %lu)", host,
           hostTid, rootTid);
  } else {
    WV_LOG("view %p: AttachThreadInput 失败 (本线程 %lu -> %lu) err=%lu", host,
           hostTid, rootTid, GetLastError());
  }
}

/** 摘掉 [ensureThreadAttached] 接上的输入队列。销毁视图时调用。 */
static void detachThreadInput(const std::shared_ptr<View>& view, HWND host) {
  if (!view || !view->attachedTid) return;
  const DWORD hostTid = GetWindowThreadProcessId(host, nullptr);
  AttachThreadInput(hostTid, view->attachedTid, FALSE);
  WV_LOG("view %p: 输入队列已摘开 (本线程 %lu <- %lu)", host, hostTid,
         view->attachedTid);
  view->attachedTid = 0;
}

/** `f` 是 `ancestor` 自己或它的后代吗。 */
static bool isSelfOrDescendant(HWND f, HWND ancestor) {
  for (HWND w = f; w; w = GetParent(w)) {
    if (w == ancestor) return true;
  }
  return false;
}

// 'ScopedThreadInput'（临时接一下、析构就摘）已于 2026-09-15
// 删除：它只能让我们自己的 `GetFocus()` 变对，改不动**前台队列**的
// `hwndFocus`，键盘照样被 `SunAwtFrame` 吃掉。取而代之的是
// [ensureThreadAttached] 的**长久**接法。
// 保留这段说明，免得以后有人图省事又改回去。
/**
 * 键盘焦点**是不是已经落在 WebView2 的输入窗口里**了。
 *
 * 判据的三个细节：
 *
 * - **要问「宿主所在线程」，不是「前台窗口所在线程」。** 宿主窗口建在 WebView
 * 线程上， 焦点真送进去之后 `GetGUIThreadInfo(宿主tid).hwndFocus` 就是
 *   `Chrome_WidgetWin_1`（探针实测）。反过来问前台线程是**错的**：前台恒为 AWT
 * 的 `SunAwtFrame`，而那条队列的 `hwndFocus` 在焦点进 WebView2
 * 之后竟然是**空的** （探针实测 `tid=<awt> hwndFocus=(空)`）—— 拿它判会永远
 * false，闸门等于不存在， 于是每次页面喊一声就重新
 * `MoveFocus`，把输入法组合状态反复打断。
 * - **`GetFocus()` 在我们自己线程上就够用**：本函数一直在 WebView 线程上跑，
 *   `GetFocus()` 读的正是这条队列，跟 `GetGUIThreadInfo(宿主tid).hwndFocus`
 * 等价。 万一将来从别的线程调进来，退化成 `GetGUIThreadInfo(宿主tid)` 也行。
 * - **宿主的直接子窗口不算**：`Chrome_WidgetWin_0` 只是 WebView2
 * 的中间层，焦点停在那儿键盘进不了渲染器，仍然需要
 * `MoveFocus`。真正算数的是它下面的 `Chrome_WidgetWin_1` /
 * `Chrome_RenderWidgetHostHWND`。
 * - 宿主自己（`AcgNativeWebViewHost`）拿到焦点同理不算 ——
 * 那正是「需要转交」的场景。
 *
 * 另外还要**前台确实是我们这棵窗口树**：切到别的 App
 * 之后本线程队列可能残留着旧的
 * `hwndFocus`，不加这一条会把「切回来之后第一次点击」误判成「已经有焦点」而跳过。
 */
static bool webViewAlreadyHasKeyboardFocus(HWND host) {
  // 前台不是我们的根窗口 → 焦点根本不在我们这边，别信 `GetFocus()` 的残留值。
  if (GetForegroundWindow() != GetAncestor(host, GA_ROOT)) return false;

  HWND f = nullptr;
  if (GetWindowThreadProcessId(host, nullptr) == GetCurrentThreadId()) {
    f = GetFocus();
  } else {
    GUITHREADINFO gti{};
    gti.cbSize = sizeof(gti);
    if (GetGUIThreadInfo(GetWindowThreadProcessId(host, nullptr), &gti))
      f = gti.hwndFocus;
  }
  if (!f || f == host) return false;
  if (!isSelfOrDescendant(f, host)) return false;
  return GetParent(f) != host;  // 直接子窗口（Chrome_WidgetWin_0）不算数
}

/**
 * 把键盘焦点送进
 WebView2。**这是「能点不能打字」的唯一解**，细节都是探针实测出来的。
 *
 * ## 为什么必须显式做
 *
 * 宿主的 HWND 拿到焦点**不会自动传给** WebView2 的子窗口，而 WebView2
 自己也不抢焦点：
 * 它只处理鼠标消息，鼠标消息按**坐标**投递、根本不需要焦点。所以焦点不送进去，
 * 表现就是「能点、能滚动、能选中，但敲键盘毫无反应」。
 *
 * ## 为什么不能靠 Win32 的鼠标消息
 *
 * WebView2 嵌进来的窗口是**跨进程两层**的（探针 `dumpTree` 实测）：
 *
 *     host(本线程) → Chrome_WidgetWin_0(本线程) →
 Chrome_WidgetWin_1(浏览器进程)
 *                                                     →
 Chrome_RenderWidgetHostHWND
 *
 * 点击落在最里层（`WindowFromPoint` 就是 `Chrome_RenderWidgetHostHWND`）。而
 * `WM_MOUSEACTIVATE` / `WM_PARENTNOTIFY` **只到直接父窗口** —— 也就是
 * `Chrome_WidgetWin_0`。它们**永远到不了我们的宿主**（探针里真实点击之后宿主一条都没收到，
 * 只有建控制器时的 `WM_PARENTNOTIFY code=1`(WM_CREATE)）。
 * 因此「点击 → 父窗口收消息 → SetFocus」这条经典路子对 WebView2
 是**结构性失效**的，
 * 别再往这个方向使劲。
 *
 * ## 点击之后靠什么把焦点送进来
 *
 * 1. **宿主 `WM_SETFOCUS`**（主路径，见 [wndProc]）：输入队列在
 [nativeViewAttach] 里
 *    就接到了 AWT 线程，所以点击激活 `SunAwtFrame` 时 AWT
 会把焦点派下去，宿主收到
 *    `WM_SETFOCUS` → 走到这里 → `MoveFocus`。2026-09-15 实测：把原来的 DOM
 焦点桥
 *   整个删掉之后键盘依然正常，说明这条链能独立走通。
 * 2. AWT 组件 `focusGained` → `nativeViewFocus`（见
 `AcgWebView.jvm.kt`），兜底。
 *
 * （曾经还有一条「页面侧 DOM 桥」：页面按下/聚焦就 post 一条 `acg:focus` 回来。
 * 它在输入队列**没有**提前接上的年代是唯一能用的路 ——
 那会儿点击消息压根到不了宿主。
 * 2026-09-15 删除，源码在 git `f113a56`。）
 *
 * ## 关键实现细节（都是踩出来的）
 *
 * - **`MoveFocus(PROGRAMMATIC)` 是主路，但单独用它不够。**
 探针（宿主就是顶层窗口、
 *   进程里只有它一个窗口）实测：调完 `GetFocus()` 变成
 `Chrome_WidgetWin_1`，页面
 *   `document.hasFocus()` 从 0 变 1，敲键页面里的 `keydown` 计数器真的在涨。
 *   **但那只是因为探针的宿主窗口自己就是前台窗口**；真正嵌进 AWT 之后不成立 ——
 *   前台是 AWT 线程的 `SunAwtFrame`，而宿主窗口建在 WebView
 线程上，两个输入队列
 *   互不相干，`SetFocus` 会被系统**静默拒绝**（`GetFocus()` 恒为 NULL，实测
 *   `focus=0000000000000000` 刷了 33 条）。所以要先用 `AttachThreadInput`
 把本线程
 *   接到前台线程的队列上，`SetFocus` 才会落到那份共享焦点上。
 * - **AWT 到底把焦点给了谁，别凭印象下结论。** 早期（队列还没提前接上时）观察到
 *   AWT 把焦点留在 `SunAwtFrame` 自己身上、`WebViewCanvas.focusGained`
 很少触发，
 *   于是判定「兜底那条路是摆设」。2026-09-15 删掉 DOM
 桥后键盘仍然正常，说明队列
 *   一提前接上，画面就变了 —— 但**具体是哪条入口触发的（宿主 `WM_SETFOCUS` 还是
 *   `focusGained`）日志里没分开打**，要分辨就在两处各加一条日志。

 * - **但不能在 `MoveFocus` 之后再去 `SetFocus`/`SetForegroundWindow` 宿主** ——
 那会把
 *   焦点从 WebView
 手里拽回来，键盘又回到宿主窗口上。（探针第一版就是这么自坑的：
 *   `MoveFocus` 后 `HASFOCUS=1`，紧接着自己抢前台，然后 `KEYS` 一直是 0。）
 *   顺序必须是「先把宿主顶成前台/激活，再 MoveFocus」。
 * - **`GetFocus()` 是「按线程消息队列」算的**：本函数跑在 WebView
 线程上，若焦点在
 *   AWT 线程的队列里，这里读到的就是 NULL —— 正好可以当作「不在我们这棵树上」。
 * - **`SetFocus` 只能点名本线程队列里的窗口**，而 `Chrome_WidgetWin_0`
 恰好就是建在
 *   我们宿主（WebView 线程）下面的，所以下面那个兜底可以放心点。
 * - **要重试**：AWT / Compose 处理激活流程时可能**在我们之后**才 `SetFocus`
 到自己的
 *   组件上，把刚送进 WebView 的焦点又拽走。表现是「点击之后第一下打字没反应、
 *   再点一下才行」—— 典型的时序竞态。所以成功之后也再确认两次。
 *
 * @param retries 还剩几次重试（调用方给初值，见 [wndProc] 里的用法）。
 */
static void requestWebViewFocus(HWND host, int retries) {
  std::shared_ptr<View> view = findView(host);
  // 后台任务是隐藏窗口，永远不该抢焦点。
  if (!view || view->destroyed || view->background || !view->controller) return;

  // **幂等**：焦点已经在 WebView
  // 的输入窗口上就什么都不做，连重试定时器都不再挂。
  //
  // 这一条不是省事，是**输入法的硬需求**：`MoveFocus` / `SetFocus`
  // 每次调用都相当于
  // **重新聚焦一次输入窗口**，Chromium 会把输入法的组合状态清掉（候选窗收起、
  // 已敲的拼音作废）。历史上 DOM 桥在 `focus` / `mousedown` / `pointerdown`
  // 上都会来喊一声，打字过程中被喊到就会反复重置 ——
  // 表现就是「焦点一直被强制切换，
  // 没法用输入法连续输入」。桥已删，但**这道闸必须留着**：现在电话更多了
  // （AWT 激活、窗口缩放、重试定时器都可能打进来），没有它输入法一样会废。
  //
  // 所以**所有**调用方（`WM_SETFOCUS`、AWT `focusGained`）都过这道闸。
  // 别再往这里加「页面说了算」的口子：页面侧的 `document.hasFocus()` 在这个 AWT
  // 嵌入式场景里不可信（实测焦点还在 `SunAwtFrame` 上时它就是 true）。
  if (webViewAlreadyHasKeyboardFocus(host)) {
    view->focusRetries = 0;
    return;
  }

  const DWORD myTid = GetCurrentThreadId();

  // 第一步：确保前台是**我们自己的窗口**。不是的话先把它顶上去 ——
  // 后台进程抢焦点系统本来就会拒，后面 SetFocus
  // 更没有意义。顺序依旧是「先顶前台，再 MoveFocus」， 反过来会把刚送进 WebView
  // 的焦点又拽回来。
  HWND fg = GetForegroundWindow();
  if (!fg || GetWindowThreadProcessId(fg, nullptr) != GetCurrentProcessId()) {
    if (HWND root = GetAncestor(host, GA_ROOT)) {
      SetForegroundWindow(root);
      fg = GetForegroundWindow();
    }
  }

  const DWORD fgTid = fg ? GetWindowThreadProcessId(fg, nullptr) : 0;
  const DWORD hostTid = GetWindowThreadProcessId(host, nullptr);

  // 第二步：输入队列的接续 —— **这一步不在这里做**。
  //
  // `AttachThreadInput` 只吃两个线程 ID，跟窗口父子关系无关；目标线程是 AWT 的
  // EDT （`SunAwtFrame`
  // 的拥有者），进程内恒定。所以接上之后就永久有效，没必要每次
  // 重新校验。真正会变的是**父窗口 HWND**，但那件事本来就要重新 `SetParent`，
  // 也就是重新走一遍 [nativeViewAttach] —— 在那里接一次就够了。
  //
  // （下面的日志会把 `已接=` 打出来，万一哪天真的没接上，一眼能看出来。）
  //
  // 官方路子：让 WebView2 把焦点搬进它自己的输入窗口。
  view->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
  bool ok = isSelfOrDescendant(GetFocus(), host);

  HWND target = nullptr;
  if (!ok) {
    // `MoveFocus` 单独用有时不生效（wails / wry 都有实测记录）。直接点名：
    // 把焦点给 WebView2 的子窗口（优先可见的那个），再不行退到宿主自己。
    HWND best = nullptr;
    for (HWND c = GetWindow(host, GW_CHILD); c; c = GetWindow(c, GW_HWNDNEXT)) {
      if (!best) best = c;
      if (IsWindowVisible(c)) {
        best = c;
        break;
      }
    }
    target = best ? best : host;
    SetFocus(target);
    ok = isSelfOrDescendant(GetFocus(), host);
  }

  // 一次就到位了也再确认两下（AWT 可能稍后把焦点拽回去）；一次没进就多试几次
  // —— 多半是激活流程还没走完，系统这会儿还不让 SetFocus。
  const int budget = ok ? 2 : 6;
  if (ok) {
    WV_LOG("view %p: 焦点进入 WebView (focus=%p)", host,
           static_cast<void*>(GetFocus()));
  } else {
    // `前台队列焦点` 是**决定键盘归属**的那个值 —— 它和 `focus=` 不一致就说明
    // 队列还没接上（见 [ensureThreadAttached]）。打出来方便一眼定位。
    HWND fgFocus = nullptr;
    if (fg) {
      GUITHREADINFO gti{};
      gti.cbSize = sizeof(gti);
      if (GetGUIThreadInfo(fgTid, &gti)) fgFocus = gti.hwndFocus;
    }
    WV_LOG(
        "view %p: 焦点未进入 WebView (focus=%p 前台队列焦点=%p target=%p "
        "err=%lu "
        "前台=%p/%lu 宿主tid=%lu 本tid=%lu 已接=%lu) 剩余重试=%d",
        host, static_cast<void*>(GetFocus()), static_cast<void*>(fgFocus),
        static_cast<void*>(target), GetLastError(), static_cast<void*>(fg),
        fgTid, hostTid, myTid, static_cast<unsigned long>(view->attachedTid),
        retries);
  }
  if (retries > 0) {
    view->focusRetries = std::min(retries, budget) - 1;
    SetTimer(host, WV_TIMER_FOCUS, ok ? 120 : 80, nullptr);
  }
}

/**
 * 让可见视图的窗口 + WebView2 控制器都贴合**父窗口客户区**。
 *
 * 父窗口是 `awtHwndOf` 从 AWT 组件（`SwingPanel { Canvas() }` 里的那个 Canvas）
 * 上取来的 HWND，客户区尺寸 = 组件尺寸。这是 attach 之后唯一的尺寸来源：
 * 子窗口如果比父窗口大，会被父窗口裁掉；如果还残留顶层窗口语义，会盖到屏幕别处。
 *
 * 必须在 WebView 线程上调（要碰 `view->controller`）。
 */
static void fitViewToParent(const std::shared_ptr<View>& view) {
  if (!view || view->destroyed || !view->hwnd) return;

  int width = 0;
  int height = 0;
  if (view->parent) {
    RECT rc{};
    if (GetClientRect(view->parent, &rc)) {
      width = rc.right;
      height = rc.bottom;
    }
  }
  // 父窗口拿不到尺寸（刚 attach、还没布局完）就退回上一次的 bounds。
  if ((width <= 0 || height <= 0) && view->hasPendingBounds) {
    width = view->pendingBounds.right;
    height = view->pendingBounds.bottom;
  }
  if (width < 1) width = 1;
  if (height < 1) height = 1;

  if (view->parent) {
    // 子窗口坐标相对父窗口客户区，所以位置恒为 (0, 0)。
    SetWindowPos(view->hwnd, nullptr, 0, 0, width, height,
                 SWP_NOZORDER | SWP_NOACTIVATE);
  }

  if (view->ready && view->controller) {
    RECT bounds{0, 0, width, height};
    view->controller->put_Bounds(bounds);
  } else {
    // 控制器还没就绪（CreateCoreWebView2Controller 是异步的）：先记着，
    // configureView 里会重放。
    view->pendingBounds = RECT{0, 0, width, height};
    view->hasPendingBounds = true;
  }
}

// ---------------------------------------------------------------------------
// WebView 线程 + 消息泵
// ---------------------------------------------------------------------------
#define WV_WM_TASK (WM_APP + 1)
/** 延后一点把键盘焦点送进 WebView（激活流程还没走完时 SetFocus
 * 可能被系统拒绝）。 */
#define WV_WM_FOCUS (WM_APP + 2)

/** 后台任务窗口 / 消息泵宿主共用。嵌入视图的窗口也用同一个类。 */
static const wchar_t* kWindowClass = L"AcgNativeWebViewHost";

/**
 * 常驻的隐藏顶层窗口，收 postTask + 当父窗口。
 *
 * 必须是原子的：JVM 线程随时在读它投任务，而 WebView
 * 线程在退出路径上会把它清掉。 裸 HWND 在这里就是数据竞争（而且清完之后 JVM
 * 线程可能拿着一个已经销毁的句柄 `PostMessageW`）。
 */
static std::atomic<HWND> g_dispatcher{nullptr};
static std::thread g_thread;
static std::atomic<bool> g_threadRunning{false};
/**
 * WebView 线程的 tid。
 *
 * 有它才能用 `PostThreadMessageW` 投**线程消息** —— 那是唯一一条不依赖任何窗口
 * 存活的通知通道，用来给消息泵兜底退出（见 [nativeStop]）。
 */
static std::atomic<DWORD> g_threadId{0};
/** WebView 线程退出的信号 —— 让 [nativeStop] 能做**有界**等待。 */
static std::mutex g_threadMutex;
static std::condition_variable g_threadCv;
static bool g_threadExited = false;

static ComPtr<ICoreWebView2Environment> g_env;
static std::mutex g_envMutex;
static std::condition_variable g_envCv;
static bool g_envDone = false;
static std::wstring g_envError;
static std::wstring g_userDataDir;

/**
 * 把闭包投到 WebView 线程执行。
 *
 * 所有触碰 `ICoreWebView2*` 或创建窗口的逻辑都必须经过这里。
 */
static bool postTask(std::function<void()> fn) {
  HWND target = g_dispatcher.load();
  if (!target) return false;
  auto* p = new (std::nothrow) std::function<void()>(std::move(fn));
  if (!p) return false;
  if (!PostMessageW(target, WV_WM_TASK, 0, reinterpret_cast<LPARAM>(p))) {
    delete p;
    return false;
  }
  return true;
}

/** 同步地在 WebView
 * 线程上跑一个闭包并等它回来（只用于「建窗口」这种极短操作）。 */
template <class Fn>
static bool runSync(const Fn& fn, int timeoutMs) {
  struct Box {
    std::mutex m;
    std::condition_variable cv;
    bool done = false;
  };
  auto box = std::make_shared<Box>();
  if (!postTask([box, fn] {
        try {
          fn();
        } catch (...) {
          WV_LOG("sync task threw");
        }
        {
          std::lock_guard<std::mutex> lock(box->m);
          box->done = true;
        }
        box->cv.notify_all();
      })) {
    return false;
  }
  std::unique_lock<std::mutex> lock(box->m);
  return box->cv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                          [&] { return box->done; });
}

// ---------------------------------------------------------------------------
// 通知 JVM
// ---------------------------------------------------------------------------
/** 事件类型，必须和 Kotlin 侧
 * `NativeWebView.EVENT_*`（`NativeWebView.jvm.kt`）一一对应。 */
enum ViewEvent {
  kViewReady = 1,    // 控制器就绪
  kViewLoading = 2,  // a = "1"/"0"
  kViewUrl = 3,      // a = url
  kViewTitle = 4,    // a = title
  kViewHistory = 5,  // a = "1"/"0" canGoBack, b = canGoForward
  kViewFailed = 6,   // a = 失败原因
  kViewDestroyed = 7,
};

static void notifyBackgroundFinished(jlong token, const std::wstring& json,
                                     bool hasJson) {
  // 结果只能落一次：拦截命中与导航完成可能同时到。
  std::shared_ptr<View> view = findBackgroundByToken(token);
  if (!view || view->notified || view->settled) return;
  view->notified = true;
  JNIEnv* env = envForThread();
  if (!env) return;
  jstring arg = hasJson ? fromWide(env, json) : nullptr;
  std::lock_guard<std::mutex> lock(g_cbMutex);
  if (g_cb && g_midFinished)
    env->CallVoidMethod(g_cb, g_midFinished, token, arg);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (arg) env->DeleteLocalRef(arg);
}

static void notifyBackgroundFailed(jlong token, const std::wstring& message) {
  std::shared_ptr<View> view = findBackgroundByToken(token);
  if (!view || view->notified || view->settled) return;
  view->notified = true;
  JNIEnv* env = envForThread();
  if (!env) return;
  jstring arg = fromWide(env, message);
  std::lock_guard<std::mutex> lock(g_cbMutex);
  if (g_cb && g_midFailed) env->CallVoidMethod(g_cb, g_midFailed, token, arg);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (arg) env->DeleteLocalRef(arg);
}

static void notifyViewEvent(HWND hwnd, ViewEvent what, const std::wstring& a,
                            const std::wstring& b) {
  JNIEnv* env = envForThread();
  if (!env) return;
  jstring ja = a.empty() ? nullptr : fromWide(env, a);
  jstring jb = b.empty() ? nullptr : fromWide(env, b);
  std::lock_guard<std::mutex> lock(g_cbMutex);
  if (g_cb && g_midViewEvent)
    env->CallVoidMethod(g_cb, g_midViewEvent,
                        static_cast<jlong>(reinterpret_cast<intptr_t>(hwnd)),
                        static_cast<jint>(what), ja, jb);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (ja) env->DeleteLocalRef(ja);
  if (jb) env->DeleteLocalRef(jb);
}

static void notifyScriptResult(HWND hwnd, jlong token, const std::wstring& json,
                               bool hasJson) {
  JNIEnv* env = envForThread();
  if (!env) return;
  jstring ja = hasJson ? fromWide(env, json) : nullptr;
  std::lock_guard<std::mutex> lock(g_cbMutex);
  if (g_cb && g_midScriptResult)
    env->CallVoidMethod(g_cb, g_midScriptResult,
                        static_cast<jlong>(reinterpret_cast<intptr_t>(hwnd)),
                        token, ja);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (ja) env->DeleteLocalRef(ja);
}

// ---------------------------------------------------------------------------
// 销毁
// ---------------------------------------------------------------------------
static void destroyView(HWND hwnd) {
  std::shared_ptr<View> view;
  {
    std::lock_guard<std::mutex> lock(g_viewsMutex);
    const auto it = g_views.find(hwnd);
    if (it == g_views.end()) return;
    view = it->second;
    g_views.erase(it);
  }
  view->destroyed = true;
  WV_LOG("view %p: destroy 开始 (background=%d)", hwnd,
         view->background ? 1 : 0);
  if (view->webview) {
    if (view->requestHandler)
      view->webview->remove_WebResourceRequested(view->tRequest);
    if (view->navStartHandler)
      view->webview->remove_NavigationStarting(view->tNavStart);
    if (view->navDoneHandler)
      view->webview->remove_NavigationCompleted(view->tNavDone);
    if (view->sourceHandler) view->webview->remove_SourceChanged(view->tSource);
    if (view->historyHandler)
      view->webview->remove_HistoryChanged(view->tHistory);
    if (view->titleHandler)
      view->webview->remove_DocumentTitleChanged(view->tTitle);
    if (view->newWindowHandler)
      view->webview->remove_NewWindowRequested(view->tNewWindow);
    if (view->processFailedHandler)
      view->webview->remove_ProcessFailed(view->tProcessFailed);
  }
  view->webview.Reset();
  view->requestHandler.Reset();
  view->navStartHandler.Reset();
  view->navDoneHandler.Reset();
  view->sourceHandler.Reset();
  view->historyHandler.Reset();
  view->titleHandler.Reset();
  view->newWindowHandler.Reset();
  view->processFailedHandler.Reset();
  view->env2.Reset();
  // Close() 必须不在 COM 回调里调 —— 这里已经跑在 postTask 出来的上下文里了。
  if (view->controller) {
    view->controller->Close();
    view->controller.Reset();
  }
  if (view->hwnd && IsWindow(view->hwnd)) {
    // 注意：可见视图的窗口是 AWT 组件的子窗口。如果 AWT 那侧先没了，窗口会被
    // 系统一起销毁 —— 所以组合层必须在组件 dispose 之前调 `destroyView`
    // （`AcgWebView` 的 `onDispose` 就是这个顺序）。这里的 IsWindow 只是兜底，
    // 挡掉「句柄已经失效」的情况。
    DestroyWindow(view->hwnd);
  }
  view->hwnd = nullptr;
  WV_LOG("view %p destroyed (background=%d)", hwnd, view->background ? 1 : 0);
}

// ---------------------------------------------------------------------------
// 请求头工具
// ---------------------------------------------------------------------------
static std::wstring buildHeaderBlock(const std::vector<std::wstring>& flat) {
  std::wstring out;
  for (size_t i = 0; i + 1 < flat.size(); i += 2) {
    if (flat[i].empty()) continue;
    out += flat[i];
    out += L": ";
    out += flat[i + 1];
    out += L"\r\n";
  }
  return out;
}

// ---------------------------------------------------------------------------
// 事件处理器
// ---------------------------------------------------------------------------

/**
 * 请求拦截 —— 整个自研 shim 存在的理由之一。
 *
 * 这是**唯一**同步回调 JVM 的地方，而且必须是同步的：`WebResourceRequested`
 * 要求我们在返回前决定「放行」还是「给一个响应」，而 JVM 侧的拦截器是普通
 * （非 suspend）函数，能立刻给结论 —— 和 Android 的 `shouldInterceptRequest`
 * 一样在后台线程上同步返回。
 *
 * 用 `GetDeferral()` 异步化需要把 commonMain 的
 * `WebViewTask.onInterceptRequest` 改成 suspend，牵动面太大，不做。
 */
class RequestHandler final
    : public ComCallback<ICoreWebView2WebResourceRequestedEventHandler> {
 public:
  RequestHandler(HWND hwnd, jlong token, ICoreWebView2Environment* env)
      : ComCallback(IID_ICoreWebView2WebResourceRequestedEventHandler),
        hwnd_(hwnd),
        token_(token),
        env_(env) {
    if (env_) env_->AddRef();
  }
  ~RequestHandler() override {
    if (env_) env_->Release();
  }

  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2WebResourceRequestedEventArgs* args) override {
    (void)sender;
    std::shared_ptr<View> view = findView(hwnd_);
    if (!view || view->settled) return S_OK;  // 已经出结果了就纯放行

    ComPtr<ICoreWebView2WebResourceRequest> request;
    if (FAILED(args->get_Request(request.GetAddressOf())) || !request)
      return S_OK;

    LPWSTR uriRaw = nullptr;
    LPWSTR methodRaw = nullptr;
    request->get_Uri(&uriRaw);
    request->get_Method(&methodRaw);
    const std::wstring uri = uriRaw ? uriRaw : L"";
    const std::wstring method = methodRaw ? methodRaw : L"GET";
    if (uriRaw) CoTaskMemFree(uriRaw);
    if (methodRaw) CoTaskMemFree(methodRaw);

    COREWEBVIEW2_WEB_RESOURCE_CONTEXT ctx =
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL;
    args->get_ResourceContext(&ctx);
    WV_LOG("view %p: WebResourceRequested %ls ctx=%d token=%lld", hwnd_,
           uri.c_str(), static_cast<int>(ctx), static_cast<long long>(token_));
    // filter 用的是 ALL，正常情况下 ResourceContext 仍会报具体类型；万一它直接
    // 回 ALL，就用「和任务目标 URL 相同」兜底判断是不是主框架。
    bool isForMainFrame;
    if (ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT) {
      isForMainFrame = true;
    } else if (ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL) {
      isForMainFrame = (uri == view->url);
    } else {
      isForMainFrame = false;
    }

    // 摊平成 [name, value, ...] 交给 JVM —— 比在 native 里拼 HashMap 省事，
    // 也不用在两边维护同一份类型。
    std::vector<std::wstring> flat;
    {
      ComPtr<ICoreWebView2HttpRequestHeaders> headers;
      request->get_Headers(headers.GetAddressOf());
      if (headers) {
        ComPtr<ICoreWebView2HttpHeadersCollectionIterator> it;
        if (SUCCEEDED(headers->GetIterator(it.GetAddressOf())) && it) {
          BOOL more = FALSE;
          while (SUCCEEDED(it->get_HasCurrentHeader(&more)) && more) {
            LPWSTR name = nullptr;
            LPWSTR value = nullptr;
            if (SUCCEEDED(it->GetCurrentHeader(&name, &value))) {
              flat.emplace_back(name ? name : L"");
              flat.emplace_back(value ? value : L"");
            }
            if (name) CoTaskMemFree(name);
            if (value) CoTaskMemFree(value);
            if (FAILED(it->MoveNext(&more))) break;
          }
        }
      }
    }

    JNIEnv* env = envForThread();
    if (!env) return S_OK;

    jstring jUri = fromWide(env, uri);
    jstring jMethod = fromWide(env, method);
    jobjectArray jHeaders =
        env->NewObjectArray(static_cast<jsize>(flat.size()),
                            env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < flat.size(); ++i) {
      jstring item = fromWide(env, flat[i]);
      env->SetObjectArrayElement(jHeaders, static_cast<jsize>(i), item);
      env->DeleteLocalRef(item);
    }

    jboolean hit = JNI_FALSE;
    {
      std::lock_guard<std::mutex> lock(g_cbMutex);
      if (g_cb && g_midIntercept)
        hit = env->CallBooleanMethod(
            g_cb, g_midIntercept, token_, jUri, jMethod,
            isForMainFrame ? JNI_TRUE : JNI_FALSE, jHeaders);
    }
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
    env->DeleteLocalRef(jUri);
    env->DeleteLocalRef(jMethod);
    env->DeleteLocalRef(jHeaders);

    if (!hit) return S_OK;

    // 命中：给一个空的 204 并把整轮加载停掉。JVM 侧此刻已经拿到结果了，
    // 之后 nativeCancel 会来收摊。
    view->settled = true;
    WV_LOG("view %p intercepted: %ls", hwnd_, uri.c_str());
    ComPtr<ICoreWebView2WebResourceResponse> response;
    if (env_ &&
        SUCCEEDED(env_->CreateWebResourceResponse(
            nullptr, 204, L"No Content", L"", response.GetAddressOf())) &&
        response) {
      args->put_Response(response.Get());
    }
    if (view->webview) view->webview->Stop();
    return S_OK;
  }

 private:
  HWND hwnd_;
  jlong token_;
  ICoreWebView2Environment* env_;
};

/** 导航开始 → isLoading = true。 */
class NavigationStartingHandler final
    : public ComCallback<ICoreWebView2NavigationStartingEventHandler> {
 public:
  explicit NavigationStartingHandler(HWND hwnd)
      : ComCallback(IID_ICoreWebView2NavigationStartingEventHandler),
        hwnd_(hwnd) {}
  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2NavigationStartingEventArgs* args) override {
    (void)sender;
    (void)args;
    const HWND hwnd = hwnd_;
    WV_LOG("view %p: NavigationStarting", hwnd);
    postTask([hwnd] {
      if (findView(hwnd)) notifyViewEvent(hwnd, kViewLoading, L"1", L"");
    });
    return S_OK;
  }

 private:
  HWND hwnd_;
};

/** 脚本注入的完成回调。resultJson 已经是 JSON 文本，和 Android 的取值形状一致。
 */
class ScriptHandler final
    : public ComCallback<ICoreWebView2ExecuteScriptCompletedHandler> {
 public:
  ScriptHandler(HWND hwnd, jlong token)
      : ComCallback(IID_ICoreWebView2ExecuteScriptCompletedHandler),
        hwnd_(hwnd),
        token_(token) {}

  HRESULT STDMETHODCALLTYPE Invoke(HRESULT errorCode,
                                   LPCWSTR resultJson) override {
    const std::wstring json = resultJson ? resultJson : L"";
    const bool ok = SUCCEEDED(errorCode);
    const HWND hwnd = hwnd_;
    const jlong token = token_;
    postTask([hwnd, token, ok, json] {
      std::shared_ptr<View> view = findView(hwnd);
      if (!view) return;
      // 后台任务和可见视图的结果通道不同：前者一次性回 nativeFinished，
      // 后者按 token 回 nativeScriptResult 并同时推一个失败事件。
      if (view->background) {
        if (ok) {
          notifyBackgroundFinished(view->token, json, true);
        } else {
          notifyBackgroundFailed(view->token, L"脚本执行失败");
        }
        return;
      }
      if (ok) {
        notifyScriptResult(hwnd, token, json, true);
      } else {
        notifyViewEvent(hwnd, kViewFailed, L"脚本执行失败", L"");
        notifyScriptResult(hwnd, token, L"", false);
      }
    });
    return S_OK;
  }

 private:
  HWND hwnd_;
  jlong token_;
};

/** 导航结束 → 后台任务在这里收尾；可见视图推 loading=false。 */
class NavigationCompletedHandler final
    : public ComCallback<ICoreWebView2NavigationCompletedEventHandler> {
 public:
  explicit NavigationCompletedHandler(HWND hwnd)
      : ComCallback(IID_ICoreWebView2NavigationCompletedEventHandler),
        hwnd_(hwnd) {}

  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2NavigationCompletedEventArgs* args) override {
    (void)sender;
    BOOL ok = FALSE;
    args->get_IsSuccess(&ok);
    COREWEBVIEW2_WEB_ERROR_STATUS err = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
    args->get_WebErrorStatus(&err);
    const HWND hwnd = hwnd_;
    postTask([hwnd, ok = ok != FALSE, err] { onDone(hwnd, ok, err); });
    return S_OK;
  }

 private:
  static void onDone(HWND hwnd, bool success,
                     COREWEBVIEW2_WEB_ERROR_STATUS err) {
    std::shared_ptr<View> view = findView(hwnd);
    WV_LOG("view %p: NavigationCompleted success=%d err=%d background=%d", hwnd,
           success ? 1 : 0, static_cast<int>(err),
           view && view->background ? 1 : 0);
    if (!view) return;
    notifyViewEvent(hwnd, kViewLoading, L"0", L"");

    if (view->settled || view->notified) return;
    // 我们自己在拦截后调的 Stop() 会引出一个 OperationCanceled，静默吞掉。
    if (!success && err == COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED)
      return;

    if (!view->background) return;

    if (!success) {
      notifyBackgroundFailed(
          view->token, L"导航失败（WebView2 错误码 " +
                           std::to_wstring(static_cast<int>(err)) + L"）：" +
                           view->url);
      return;
    }
    if (!view->hasScript) {
      notifyBackgroundFinished(view->token, L"", false);
      return;
    }
    auto* handler = new (std::nothrow) ScriptHandler(hwnd, view->token);
    if (!handler) {
      notifyBackgroundFailed(view->token, L"内存不足，无法注入脚本");
      return;
    }
    const HRESULT hr =
        view->webview->ExecuteScript(view->script.c_str(), handler);
    handler->Release();
    if (FAILED(hr))
      notifyBackgroundFailed(view->token, L"ExecuteScript 调用失败");
  }

  HWND hwnd_;
};

/** 文档地址变了。 */
class SourceChangedHandler final
    : public ComCallback<ICoreWebView2SourceChangedEventHandler> {
 public:
  explicit SourceChangedHandler(HWND hwnd)
      : ComCallback(IID_ICoreWebView2SourceChangedEventHandler), hwnd_(hwnd) {}
  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2SourceChangedEventArgs* args) override {
    (void)args;
    const HWND hwnd = hwnd_;
    ComPtr<ICoreWebView2> wv(sender);
    const std::wstring url =
        takeString([&](LPWSTR* out) { return wv->get_Source(out); });
    postTask([hwnd, url] {
      if (findView(hwnd)) notifyViewEvent(hwnd, kViewUrl, url, L"");
    });
    return S_OK;
  }

 private:
  HWND hwnd_;
};

/** 前进/后退栈变化。 */
class HistoryChangedHandler final
    : public ComCallback<ICoreWebView2HistoryChangedEventHandler> {
 public:
  explicit HistoryChangedHandler(HWND hwnd)
      : ComCallback(IID_ICoreWebView2HistoryChangedEventHandler), hwnd_(hwnd) {}
  HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2* sender,
                                   IUnknown* args) override {
    (void)args;
    BOOL back = FALSE, forward = FALSE;
    sender->get_CanGoBack(&back);
    sender->get_CanGoForward(&forward);
    const HWND hwnd = hwnd_;
    postTask([hwnd, back = back != FALSE, forward = forward != FALSE] {
      if (findView(hwnd))
        notifyViewEvent(hwnd, kViewHistory, back ? L"1" : L"0",
                        forward ? L"1" : L"0");
    });
    return S_OK;
  }

 private:
  HWND hwnd_;
};

/** 标题变化。 */
class TitleChangedHandler final
    : public ComCallback<ICoreWebView2DocumentTitleChangedEventHandler> {
 public:
  explicit TitleChangedHandler(HWND hwnd)
      : ComCallback(IID_ICoreWebView2DocumentTitleChangedEventHandler),
        hwnd_(hwnd) {}
  HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2* sender,
                                   IUnknown* args) override {
    (void)args;
    const std::wstring title =
        takeString([&](LPWSTR* out) { return sender->get_DocumentTitle(out); });
    const HWND hwnd = hwnd_;
    WV_LOG("view %p: DocumentTitleChanged -> %ls", hwnd, title.c_str());
    postTask([hwnd, title] {
      if (findView(hwnd)) notifyViewEvent(hwnd, kViewTitle, title, L"");
    });
    return S_OK;
  }

 private:
  HWND hwnd_;
};

/**
 * 页面的「开新窗口」请求（`window.open()` / `target="_blank"`）怎么处理。
 *
 * -
 * **后台视图**：吞掉。隐藏窗口弹窗没有任何意义，还会在屏幕上冒出一个没人管的窗口。
 * - **可见视图**：**绝对不能只是吞掉**。这里以前对两种视图一视同仁地
 *   `put_Handled(TRUE)`，于是页面上所有走新窗口的按钮和链接点了**完全没反应**
 * —— 表现就是「有些按钮点不动、另一些好好的」（点得动的都是同页跳转的）。
 *   现在改成在当前视图里**就地导航**过去。
 *
 * 为什么不做成真正的新窗口：那要再建一套 controller + 宿主窗口，还得接管它的
 * 生命周期和 `IsVisible`；这个 App 的可见视图只有一个，就地导航行为最可预期。
 * 以后真需要多窗口，这里是唯一要改的地方。
 */
class NewWindowHandler final
    : public ComCallback<ICoreWebView2NewWindowRequestedEventHandler> {
 public:
  NewWindowHandler(HWND hwnd, bool swallow)
      : ComCallback(IID_ICoreWebView2NewWindowRequestedEventHandler),
        hwnd_(hwnd),
        swallow_(swallow) {}

  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2NewWindowRequestedEventArgs* args) override {
    if (swallow_ || !sender) {
      args->put_Handled(TRUE);
      return S_OK;
    }
    LPWSTR uri = nullptr;
    args->get_Uri(&uri);
    const std::wstring target = uri ? uri : L"";
    if (uri) CoTaskMemFree(uri);
    args->put_Handled(TRUE);
    WV_LOG("view %p: 新窗口请求 → 就地导航 %ls", hwnd_, target.c_str());
    if (!target.empty()) sender->Navigate(target.c_str());
    return S_OK;
  }

 private:
  HWND hwnd_;
  /** true = 后台视图，直接吞掉。 */
  bool swallow_;
};

/**
 * 哪种进程退出才算「这个视图完了」。
 *
 * 只认浏览器进程和**渲染**进程 —— 这是 WebView2 官方 sample 的口径。
 *
 * GPU / Utility / SandboxHelper 的退出是**常态**，不是异常：硬件 GPU 不可用、
 * 驱动重置、沙箱受限、或者在无桌面会话里跑，Chromium 都会主动 kill 掉它们再重启
 * （日志里 `reason=2` 就是 `TERMINATED`，`exit=1` 也是这么来的），WebView2
 * 自己会兜底到软件渲染，页面照常加载。
 *
 * 把它们当失败上报，等于把「一次正常的进程重启」变成「导航中途被销毁」——
 * 实测就是这样：文档请求刚发出去就被自己的失败上报打断，页面永远到不了
 * `NavigationCompleted`。
 */
static bool isFatalProcessFailure(COREWEBVIEW2_PROCESS_FAILED_KIND kind) {
  switch (kind) {
    case COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED:
    case COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED:
    case COREWEBVIEW2_PROCESS_FAILED_KIND_FRAME_RENDER_PROCESS_EXITED:
      return true;
    default:
      return false;
  }
}

/**
 * WebView2 的浏览器/渲染进程挂了。
 *
 * **必须处理**：不接这个事件的话，进程一死就再没有任何回调 —— 调用方只会看到
 * 「等满 30s 超时」，排查时完全看不出是崩了还是网慢。见
 * `COREWEBVIEW2_PROCESS_FAILED_KIND`。
 *
 * 但只有致命的那几种才上报失败，其余只记日志 —— 理由见
 * `isFatalProcessFailure()`。
 */
class ProcessFailedHandler final
    : public ComCallback<ICoreWebView2ProcessFailedEventHandler> {
 public:
  explicit ProcessFailedHandler(HWND hwnd)
      : ComCallback(IID_ICoreWebView2ProcessFailedEventHandler), hwnd_(hwnd) {}

  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2ProcessFailedEventArgs* args) override {
    (void)sender;
    COREWEBVIEW2_PROCESS_FAILED_KIND kind =
        COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED;
    args->get_ProcessFailedKind(&kind);
    // Reason / ExitCode 在 _2 上（基接口没有），拿不到就算了 —— 别为了日志把
    // 事件处理搞失败。
    COREWEBVIEW2_PROCESS_FAILED_REASON reason =
        COREWEBVIEW2_PROCESS_FAILED_REASON_UNEXPECTED;
    INT32 exitCode = 0;
    ComPtr<ICoreWebView2ProcessFailedEventArgs2> args2;
    if (SUCCEEDED(args->QueryInterface(
            IID_ICoreWebView2ProcessFailedEventArgs2,
            reinterpret_cast<void**>(args2.GetAddressOf()))) &&
        args2) {
      args2->get_Reason(&reason);
      args2->get_ExitCode(&exitCode);
    }

    const HWND hwnd = hwnd_;
    postTask([hwnd, kind, reason, exitCode] {
      std::shared_ptr<View> view = findView(hwnd);
      if (!view) return;
      // 注意：`%ls` 在 "C" locale 下过不去非 ASCII，中文经宽字符走 native 日志
      // 会被截断 —— 所以这里的数字用 `%d`，后面那句中文用 narrow 的 `%s` 原样
      // 写字节（不经过宽字符转换）；给 JVM 的那份消息才走宽字符。
      WV_LOG("view %p: process failed kind=%d reason=%d exit=%d%s", hwnd,
             static_cast<int>(kind), static_cast<int>(reason),
             static_cast<int>(exitCode),
             isFatalProcessFailure(kind) ? "" : "（非致命，忽略）");
      if (!isFatalProcessFailure(kind)) return;
      const std::wstring message =
          L"WebView2 进程异常退出（kind=" +
          std::to_wstring(static_cast<int>(kind)) + L" reason=" +
          std::to_wstring(static_cast<int>(reason)) + L" exit=" +
          std::to_wstring(exitCode) + L"）";
      if (view->background) {
        notifyBackgroundFailed(view->token, message);
      } else {
        notifyViewEvent(hwnd, kViewFailed, message, L"");
      }
    });
    return S_OK;
  }

 private:
  HWND hwnd_;
};

// ---------------------------------------------------------------------------
// 键盘焦点：全部走 Win32 原生链，不做任何脚本注入
// ---------------------------------------------------------------------------
// 这里曾经是 DOM 焦点桥（三个脚本常量和《FocusMessageHandler》回调，靠页面
// `postMessage('acg:focus')` 回喊原生来 MoveFocus）。2026-09-15 整套删除 ——
// 输入队列提前到 [nativeViewAttach] 接上之后纯原生链就够了，实测键盘正常。
// 现在的入口只有两个：宿主 `WM_SETFOCUS`（主）、AWT `focusGained`（兜），
// 都汇到 [requestWebViewFocus]。**本文件不再有任何脚本注入** ——
// `opt.initScript` 也在同一天删掉了（桌面与 Android
// 都不提供文档级前置脚本，保持两端一致）。 真要注入，WebView2 这边是
// `AddScriptToExecuteOnDocumentCreated`， Android 那边得引 `androidx.webkit` 的
// `addDocumentStartJavaScript`。

// ---------------------------------------------------------------------------
// 视图配置（控制器就绪后调用，跑在 WebView 线程上）
// ---------------------------------------------------------------------------
struct ViewOptions {
  jlong token = 0;
  std::wstring userAgent;
  std::wstring script;  // 死字段：既没有赋值点也没有读取点（`opt.script`
                        // 全文件只此一行）
  // 初始 URL；空 → 不导航（停在 about:blank）
  std::wstring url;
  std::vector<std::wstring> headers;
  bool enableDevtools = false;
  bool allowNewWindow = false;
  double zoom = 1.0;
};

static void configureView(const std::shared_ptr<View>& view,
                          const ViewOptions& opt,
                          ICoreWebView2Controller* controller,
                          ICoreWebView2* webview);

/** 控制器就绪后继续：设置项、事件、注册拦截、导航。 */
class ControllerHandler final
    : public ComCallback<
          ICoreWebView2CreateCoreWebView2ControllerCompletedHandler> {
 public:
  ControllerHandler(HWND hwnd, ViewOptions opt)
      : ComCallback(
            IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler),
        hwnd_(hwnd),
        opt_(std::move(opt)) {}

  HRESULT STDMETHODCALLTYPE
  Invoke(HRESULT errorCode, ICoreWebView2Controller* controller) override {
    const HWND hwnd = hwnd_;
    WV_LOG(
        "view %p: CreateCoreWebView2Controller 回调 hr=0x%08lX controller=%p",
        hwnd, static_cast<unsigned long>(errorCode),
        static_cast<void*>(controller));
    if (FAILED(errorCode) || !controller) {
      const jlong token = opt_.token;
      postTask([hwnd, token] {
        notifyViewEvent(hwnd, kViewFailed, L"创建 WebView2 控制器失败", L"");
        notifyBackgroundFailed(token, L"创建 WebView2 控制器失败");
        destroyView(hwnd);
      });
      return S_OK;
    }
    ComPtr<ICoreWebView2Controller> ctrl(controller);
    ComPtr<ICoreWebView2> webview;
    ctrl->get_CoreWebView2(webview.GetAddressOf());
    if (!webview) {
      const jlong token = opt_.token;
      postTask([hwnd, token] {
        notifyViewEvent(hwnd, kViewFailed, L"控制器没有给出 ICoreWebView2",
                        L"");
        notifyBackgroundFailed(token, L"控制器没有给出 ICoreWebView2");
        destroyView(hwnd);
      });
      return S_OK;
    }
    ViewOptions opt = opt_;
    postTask([hwnd, ctrl, webview, opt] {
      configureView(findView(hwnd), opt, ctrl.Get(), webview.Get());
    });
    return S_OK;
  }

 private:
  HWND hwnd_;
  ViewOptions opt_;
};

static void configureView(const std::shared_ptr<View>& view,
                          const ViewOptions& opt,
                          ICoreWebView2Controller* controller,
                          ICoreWebView2* webview) {
  if (!view) {
    WV_LOG("configureView: 视图已经没了，跳过");
    return;
  }
  view->controller = controller;
  view->webview = webview;
  // 早点置位：下面 fitViewToParent / 后续 JNI 都靠它判断「控制器可用」。
  // configureView 整段都跑在 WebView
  // 线程上的单个任务里，提前不会和别的线程打架。
  view->ready = true;
  g_env->QueryInterface(IID_ICoreWebView2Environment2,
                        reinterpret_cast<void**>(view->env2.GetAddressOf()));
  WV_LOG("view %p: configureView background=%d env2=%d", view->hwnd,
         view->background ? 1 : 0, view->env2 ? 1 : 0);

  // ---- 设置项：后台页不要界面 ----
  ComPtr<ICoreWebView2Settings> settings;
  if (SUCCEEDED(webview->get_Settings(settings.GetAddressOf())) && settings) {
    settings->put_IsScriptEnabled(TRUE);
    // 一直 FALSE：不给页面 `window.chrome.webview`，也就没有「页面 postMessage
    // 喊原生」这条通道。DOM 焦点桥已删（2026-09-15），页面 →
    // 原生目前不需要任何消息。 这个值曾经被写死成 FALSE 时被误判成
    // bug（2026-09-15 前），其实那是对的；
    // 当时误判的原因是桥自身失效，跟这里无关。要加消息通道先想清楚跨进程窗口那套。
    const HRESULT hrMsg = settings->put_IsWebMessageEnabled(FALSE);
    WV_LOG("view %p: put_IsWebMessageEnabled(0) hr=0x%08lX", view->hwnd,
           static_cast<unsigned long>(hrMsg));
    settings->put_AreDefaultScriptDialogsEnabled(FALSE);
    settings->put_AreDevToolsEnabled(opt.enableDevtools ? TRUE : FALSE);
    settings->put_IsStatusBarEnabled(FALSE);
    settings->put_IsZoomControlEnabled(FALSE);
    settings->put_IsBuiltInErrorPageEnabled(FALSE);
    settings->put_AreDefaultContextMenusEnabled(opt.enableDevtools ? TRUE
                                                                   : FALSE);
  }
  if (!opt.userAgent.empty()) {
    ComPtr<ICoreWebView2Settings2> settings2;
    if (SUCCEEDED(webview->QueryInterface(
            IID_ICoreWebView2Settings2,
            reinterpret_cast<void**>(settings2.GetAddressOf()))) &&
        settings2) {
      settings2->put_UserAgent(opt.userAgent.c_str());
    }
  }
  if (opt.zoom > 0 && opt.zoom != 1.0) controller->put_ZoomFactor(opt.zoom);

  // 后台视图是隐藏顶层窗口，1x1 就够；可见视图尺寸以 AWT
  // 组件（父窗口）客户区为准。
  if (view->background) {
    RECT bounds{0, 0, 1, 1};
    controller->put_Bounds(bounds);
  } else {
    // attach 可能比控制器先到、也可能后到 —— 两边都收口到 fitViewToParent。
    fitViewToParent(view);
    if (view->parent) ShowWindow(view->hwnd, SW_SHOWNA);
    controller->put_IsVisible(TRUE);
  }

  // ---- 事件 ----
  auto* reqHandler =
      new (std::nothrow) RequestHandler(view->hwnd, view->token, g_env.Get());
  if (reqHandler) {
    view->requestHandler = reqHandler;
    reqHandler->Release();
  }
  if (view->requestHandler) {
    const HRESULT hr = webview->add_WebResourceRequested(
        view->requestHandler.Get(), &view->tRequest);
    WV_LOG("view %p: add_WebResourceRequested hr=0x%08lX token=%lld",
           view->hwnd, static_cast<unsigned long>(hr),
           static_cast<long long>(view->token));
    if (SUCCEEDED(hr)) {
      // ==== 关键：filter 用 "*" + ALL，子资源（含 Range 分片）才看得见 ====
      const HRESULT hrFilter = webview->AddWebResourceRequestedFilter(
          L"*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
      WV_LOG("view %p: AddWebResourceRequestedFilter hr=0x%08lX", view->hwnd,
             static_cast<unsigned long>(hrFilter));
    }
  } else {
    WV_LOG("view %p: RequestHandler 分配失败", view->hwnd);
  }

#define ADD_HANDLER(field, token, method, type)         \
  do {                                                  \
    auto* h = new (std::nothrow) type(view->hwnd);      \
    if (h) {                                            \
      view->field = h;                                  \
      h->Release();                                     \
      webview->method(view->field.Get(), &view->token); \
    }                                                   \
  } while (0)

  ADD_HANDLER(navStartHandler, tNavStart, add_NavigationStarting,
              NavigationStartingHandler);
  ADD_HANDLER(navDoneHandler, tNavDone, add_NavigationCompleted,
              NavigationCompletedHandler);
  ADD_HANDLER(sourceHandler, tSource, add_SourceChanged, SourceChangedHandler);
  ADD_HANDLER(historyHandler, tHistory, add_HistoryChanged,
              HistoryChangedHandler);
  ADD_HANDLER(titleHandler, tTitle, add_DocumentTitleChanged,
              TitleChangedHandler);
  ADD_HANDLER(processFailedHandler, tProcessFailed, add_ProcessFailed,
              ProcessFailedHandler);
#undef ADD_HANDLER

  if (!opt.allowNewWindow) {
    // swallow=false（可见视图）→ 就地导航；swallow=true（后台视图）→ 直接吞。
    auto* h = new (std::nothrow) NewWindowHandler(view->hwnd, view->background);
    if (h) {
      view->newWindowHandler = h;
      h->Release();
      webview->add_NewWindowRequested(view->newWindowHandler.Get(),
                                      &view->tNewWindow);
    }
  }

  // ---- 键盘焦点：这里**不注入任何脚本** ----
  //
  // 曾经在这一段注入一个 DOM 桥（页面按下 / 拿到焦点就
  // `postMessage('acg:focus')` 回来）， 因为点击落在 WebView2
  // 最里层那个跨进程窗口上，宿主一条鼠标消息都收不到， 「点击 → 把焦点送进
  // WebView」只能从页面里喊回来。
  //
  // 2026-09-15 **整套删掉**，实测键盘正常。走的是纯原生链：
  // 输入队列在 [nativeViewAttach] 里就接到了 AWT 线程 → 点击时系统激活
  // SunAwtFrame → AWT 把焦点派回 Canvas → 宿主收到 `WM_SETFOCUS`（见
  // [wndProc]） → [requestWebViewFocus] → `MoveFocus`。
  //
  // 哪天又打不了字，先查 [nativeViewAttach] 里那次 attach 成不成
  // （日志找「输入队列已接到 AWT 线程」），再考虑回 git 历史 `f113a56` 取回桥。

  notifyViewEvent(view->hwnd, kViewReady, L"", L"");

  // ---- 导航 ----
  if (view->background) {
    // 带请求头导航：`Navigate` 不支持自定义头，得走
    // `NavigateWithWebResourceRequest`（要 `ICoreWebView2_2`）。
    ComPtr<ICoreWebView2_2> webview2;
    bool navigated = false;
    if (view->env2 && view->headers.size() >= 2 &&
        SUCCEEDED(webview->QueryInterface(
            IID_ICoreWebView2_2,
            reinterpret_cast<void**>(webview2.GetAddressOf())))) {
      ComPtr<ICoreWebView2WebResourceRequest> request;
      const std::wstring headerBlock = buildHeaderBlock(view->headers);
      const HRESULT hrReq = view->env2->CreateWebResourceRequest(
          view->url.c_str(), L"GET", nullptr, headerBlock.c_str(),
          request.GetAddressOf());
      if (SUCCEEDED(hrReq) && request) {
        const HRESULT hrNav =
            webview2->NavigateWithWebResourceRequest(request.Get());
        navigated = SUCCEEDED(hrNav);
        WV_LOG("view %p: NavigateWithWebResourceRequest hr=0x%08lX url=%ls",
               view->hwnd, static_cast<unsigned long>(hrNav),
               view->url.c_str());
      } else {
        WV_LOG("view %p: CreateWebResourceRequest hr=0x%08lX（退回 Navigate）",
               view->hwnd, static_cast<unsigned long>(hrReq));
      }
    }
    if (!navigated) {
      const HRESULT hrNav = webview->Navigate(view->url.c_str());
      navigated = SUCCEEDED(hrNav);
      WV_LOG("view %p: Navigate hr=0x%08lX url=%ls", view->hwnd,
             static_cast<unsigned long>(hrNav), view->url.c_str());
    }
    if (!navigated) notifyBackgroundFailed(view->token, L"导航调用失败");
  } else if (!view->pendingUrl.empty()) {
    // 建视图时还没就绪就已经有 loadUrl 调过来了：用它覆盖初始 URL。
    view->webview->Navigate(view->pendingUrl.c_str());
  } else if (!opt.url.empty()) {
    ComPtr<ICoreWebView2_2> webview2;
    bool navigated = false;
    if (view->env2 && !opt.headers.empty() &&
        SUCCEEDED(webview->QueryInterface(
            IID_ICoreWebView2_2,
            reinterpret_cast<void**>(webview2.GetAddressOf())))) {
      ComPtr<ICoreWebView2WebResourceRequest> request;
      const std::wstring headerBlock = buildHeaderBlock(opt.headers);
      if (SUCCEEDED(view->env2->CreateWebResourceRequest(
              opt.url.c_str(), L"GET", nullptr, headerBlock.c_str(),
              request.GetAddressOf())) &&
          request) {
        navigated =
            SUCCEEDED(webview2->NavigateWithWebResourceRequest(request.Get()));
      }
    }
    // 没有 ICoreWebView2_2（或那个带头的请求没建出来）就退回普通 Navigate ——
    // 这条路带不了自定义 header。
    if (!navigated) navigated = SUCCEEDED(webview->Navigate(opt.url.c_str()));
    if (!navigated)
      notifyViewEvent(view->hwnd, kViewFailed, L"导航调用失败", L"");
  }
}

/** 建环境（异步）→ 回调里唤醒 nativeStart 的等待。 */
class EnvironmentHandler final
    : public ComCallback<
          ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler> {
 public:
  EnvironmentHandler()
      : ComCallback(
            IID_ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler) {}

  HRESULT STDMETHODCALLTYPE Invoke(HRESULT errorCode,
                                   ICoreWebView2Environment* env) override {
    WV_LOG("EnvironmentHandler: hr=0x%08lX env=%p",
           static_cast<unsigned long>(errorCode), static_cast<void*>(env));
    // 消息在锁外拼：它要去问 loader 当前运行时是哪个版本。
    const std::wstring failure =
        (SUCCEEDED(errorCode) && env)
            ? std::wstring()
            : describeEnvFailure(L"创建 WebView2 环境失败", errorCode,
                                 g_userDataDir);
    {
      std::lock_guard<std::mutex> lock(g_envMutex);
      if (failure.empty()) {
        g_env = env;
      } else {
        g_envError = failure;
        WV_LOG("环境创建失败：%ls", failure.c_str());
      }
      g_envDone = true;
    }
    g_envCv.notify_all();
    return S_OK;
  }
};

// ---------------------------------------------------------------------------
// 窗口过程 / WebView 线程
// ---------------------------------------------------------------------------
static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
  switch (msg) {
    case WV_WM_TASK: {
      std::unique_ptr<std::function<void()>> fn(
          reinterpret_cast<std::function<void()>*>(lp));
      if (fn && *fn) {
        try {
          (*fn)();
        } catch (...) {
          WV_LOG("posted task threw");
        }
      }
      return 0;
    }
    case WM_CLOSE:
      DestroyWindow(hwnd);
      return 0;
    // ---- 键盘焦点 ----
    //
    // 落**在页面里**的点击到不了这里：WebView2 的窗口是跨进程两层的（host →
    // Chrome_WidgetWin_0 → Chrome_WidgetWin_1 →
    // Chrome_RenderWidgetHostHWND），点击落在最里层，而那些消息**只到直接父窗口**
    // （Chrome_WidgetWin_0）。探针里真实点击页面之后宿主一条都没收到。
    // 落在宿主**自己**身上的点击是收得到的（页面没铺满、露出来的那点边），
    // 见下面 `WM_MOUSEACTIVATE` 与 `WM_LBUTTONDOWN` 那一组 case。
    // 那页面里的点击是怎么走到这儿的？靠激活流程：输入队列在 [nativeViewAttach]
    // 里就接到了 AWT 线程 → 点击激活 `SunAwtFrame` → AWT 把焦点派回
    // Canvas（宿主）→ 下面这条 `WM_SETFOCUS`。2026-09-15 把 DOM
    // 桥整个删掉之后实测键盘仍然正常，就是这条链
    // 能独立走通的证据（此前队列没提前接上，这条链走不通，才需要页面回喊）。
    case WM_SETFOCUS:
      // 焦点到了宿主窗口 —— 送到 WebView2 去。WebView2 官方示例的标准做法，
      // 也是现在**唯一的主路径**。
      requestWebViewFocus(hwnd, 2);
      return 0;
    case WV_WM_FOCUS:
      // 延后一拍的焦点请求（激活流程还没走完时 SetFocus 可能被系统拒绝）。
      requestWebViewFocus(hwnd, 2);
      return 0;
    case WM_MOUSEACTIVATE:
      // 点在宿主**自己**身上（WebView2
      // 还没铺满、或者页面比宿主小时露出来的那点边） 的情况。**不能**在这里直接
      // SetFocus —— 激活流程还没走完，系统可能拒绝；
      // 延后一条消息再做。只认客户区（HTCLIENT），别去抢非客户区的点击。
      if (LOWORD(lp) == HTCLIENT) PostMessageW(hwnd, WV_WM_FOCUS, 0, 0);
      return MA_ACTIVATE;
    case WM_LBUTTONDOWN:
    case WM_RBUTTONDOWN:
    case WM_MBUTTONDOWN:
      // 同理：只有直接落在宿主窗口上的点击才收得到（页面里的点击落在最里层，
      // 永远到不了这儿）。这一路只覆盖「WebView2 没铺满、露出来的那点边」。
      PostMessageW(hwnd, WV_WM_FOCUS, 0, 0);
      return 0;
    case WM_TIMER:
      if (wp == WV_TIMER_FOCUS) {
        KillTimer(hwnd, WV_TIMER_FOCUS);
        const std::shared_ptr<View> timerView = findView(hwnd);
        requestWebViewFocus(hwnd, timerView ? timerView->focusRetries : 0);
        return 0;
      }
      return DefWindowProcW(hwnd, msg, wp, lp);
    case WM_DESTROY:
      KillTimer(hwnd, WV_TIMER_FOCUS);
      // 摘开输入队列再关窗口 —— 见 [ensureThreadAttached]。漏了会留下
      // 「AWT 线程还接着一个已经死了的线程」的脏状态。
      detachThreadInput(findView(hwnd), hwnd);
      if (hwnd == g_dispatcher.load()) PostQuitMessage(0);
      return 0;
    default:
      return DefWindowProcW(hwnd, msg, wp, lp);
  }
}

static bool registerWindowClass() {
  static std::mutex m;
  static bool done = false;
  static bool ok = false;
  std::lock_guard<std::mutex> lock(m);
  if (done) return ok;
  done = true;

  WNDCLASSEXW wc{};
  wc.cbSize = sizeof(wc);
  wc.lpfnWndProc = wndProc;
  wc.hInstance = GetModuleHandleW(nullptr);
  wc.lpszClassName = kWindowClass;
  wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  wc.style = CS_HREDRAW | CS_VREDRAW;

  if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
    WV_LOG("RegisterClassExW failed (%lu)", GetLastError());
    return ok = false;
  }
  return ok = true;
}

/**
 * 建一个 WebView 宿主窗口。
 *
 * 两种用法都**从隐藏的顶层窗口起手**：
 *  - 后台任务：窗口一直隐藏，只当消息泵宿主，永远不会进 `SetParent`；
 *  - 可见视图：窗口先以 `WS_POPUP` 隐藏创建（没有 `WS_VISIBLE` 不会闪出来），
 *    等 `nativeViewAttach` 从 AWT 组件上取到父 HWND 后，再 `SetParent` +
 *    把样式换成 `WS_CHILD`。用「先顶层、后改样式」而不是直接 `WS_CHILD`，
 *    是因为创建时父 HWND 还不存在（AWT 组件刚在 `SwingPanel` 里 AddNotify）。
 */
static HWND createViewWindow(HWND parent, bool background) {
  const DWORD style =
      background ? (WS_POPUP) : (WS_POPUP | WS_CLIPCHILDREN | WS_CLIPSIBLINGS);
  return CreateWindowExW(0, kWindowClass, L"", style, 0, 0, 1, 1, parent,
                         nullptr, GetModuleHandleW(nullptr), nullptr);
}

static void failEnvInit(const std::wstring& message) {
  std::lock_guard<std::mutex> lock(g_envMutex);
  g_envError = message;
  g_envDone = true;
  g_envCv.notify_all();
}

static void webViewThreadMain() {
  // 无论从哪条 return 出去都要把「线程已退出」的信号发出去，否则 nativeStop 会
  // 等满超时再 detach。RAII 保证这点。
  struct ExitGuard {
    ~ExitGuard() {
      {
        std::lock_guard<std::mutex> lock(g_threadMutex);
        g_threadExited = true;
      }
      g_threadCv.notify_all();
    }
  } exitGuard;

  CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  // 让 PostThreadMessageW 能投到我们身上 —— 消息队列要等第一次
  // GetMessage/PeekMessage 才存在，但我们马上就要建窗口，所以这里不用像
  // wvbridge 那样先 PeekMessage 一下。
  g_threadId.store(GetCurrentThreadId());

  if (!registerWindowClass()) {
    failEnvInit(L"注册窗口类失败");
    CoUninitialize();
    return;
  }
  g_dispatcher.store(createViewWindow(nullptr, true));
  if (!g_dispatcher.load()) {
    failEnvInit(L"创建隐藏宿主窗口失败");
    CoUninitialize();
    return;
  }
  g_threadRunning = true;

  if (!ensureLoader()) {
    failEnvInit(L"找不到 WebView2Loader.dll（应和 webview.dll 放在同一目录）");
  } else {
    // 必须在建环境**之前**设好 —— loader 是在
    // CreateCoreWebView2EnvironmentWithOptions
    // 里读这个变量、并把环境块传给浏览器子进程的。
    applyDefaultBrowserArguments();
    const std::wstring dir = g_userDataDir;
    auto* handler = new (std::nothrow) EnvironmentHandler();
    if (!handler) {
      failEnvInit(L"内存不足");
    } else {
      const HRESULT hr = g_pfnCreateEnv(
          nullptr, dir.empty() ? nullptr : dir.c_str(), nullptr, handler);
      handler->Release();
      if (FAILED(hr))
        failEnvInit(describeEnvFailure(
            L"CreateCoreWebView2EnvironmentWithOptions 调用失败", hr, dir));
    }
  }

  // 消息泵。WebView2 的异步回调全靠它派发。
  MSG msg;
  while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
    TranslateMessage(&msg);
    DispatchMessageW(&msg);
  }

  // 兜底：正常路径上 nativeStop
  // 已经在泵还活着的时候把视图拆干净了，这里一般是空转。
  // 但万一线程是被别的途径结束的（比如 PostThreadMessage(WM_QUIT) 兜底命中），
  // 残留下来的视图必须在这里收掉。
  std::vector<HWND> handles;
  {
    std::lock_guard<std::mutex> lock(g_viewsMutex);
    for (const auto& entry : g_views) handles.push_back(entry.first);
  }
  for (const HWND h : handles) destroyView(h);

  g_env.Reset();
  g_dispatcher.store(nullptr);
  g_threadId.store(0);
  g_threadRunning = false;
  CoUninitialize();
}

/** 确保 WebView 线程和环境都起来了；返回 null 表示成功。 */
static std::wstring ensureStarted(const std::wstring& userDataDir,
                                  int timeoutMs) {
  if (g_thread.joinable()) {
    std::lock_guard<std::mutex> lock(g_envMutex);
    return g_envError;
  }
  if (!ensureLoader())
    return L"找不到 WebView2Loader.dll（应和 webview.dll 放在同一目录）";
  const std::wstring version = runtimeVersion();
  if (version.empty())
    return L"系统没有安装 WebView2 运行时（Win11 自带，Win10 需要装 Evergreen "
           L"Runtime）";
  WV_LOG("WebView2 runtime %ls", version.c_str());

  {
    std::lock_guard<std::mutex> lock(g_envMutex);
    g_userDataDir = userDataDir;
    g_envDone = false;
    g_envError.clear();
  }
  {
    // 必须在起线程之前清掉 —— 否则上一轮的「已退出」会让 nativeStop 立刻
    // 认为线程结束、跑去 join 一个刚开始跑的线程。
    std::lock_guard<std::mutex> lock(g_threadMutex);
    g_threadExited = false;
  }
  g_thread = std::thread(webViewThreadMain);

  std::unique_lock<std::mutex> lock(g_envMutex);
  if (!g_envCv.wait_for(
          lock, std::chrono::milliseconds(timeoutMs > 0 ? timeoutMs : 20000),
          [] { return g_envDone; })) {
    return L"等待 WebView2 环境就绪超时";
  }
  return g_envError;
}

// ---------------------------------------------------------------------------
// JNI 导出。Java 侧：soko.ekibun.acg.web.NativeWebView（Kotlin object）
// ---------------------------------------------------------------------------
#define WV_JNI(ret, name) \
  extern "C" JNIEXPORT ret JNICALL Java_soko_ekibun_acg_web_NativeWebView_##name
#define WV_JNI_PARAMS JNIEnv *env, jobject

/** 从 JVM 的 String[] 读摊平的头。 */
static std::vector<std::wstring> readHeaderArray(JNIEnv* env,
                                                 jobjectArray headers) {
  std::vector<std::wstring> out;
  if (!headers) return out;
  const jsize n = env->GetArrayLength(headers);
  for (jsize i = 0; i + 1 < n; i += 2) {
    auto* name = static_cast<jstring>(env->GetObjectArrayElement(headers, i));
    auto* value =
        static_cast<jstring>(env->GetObjectArrayElement(headers, i + 1));
    out.push_back(toWide(env, name));
    out.push_back(toWide(env, value));
    if (name) env->DeleteLocalRef(name);
    if (value) env->DeleteLocalRef(value);
  }
  return out;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)reserved;
  g_vm = vm;
  return JNI_VERSION_1_6;
}

/** 装了 WebView2 运行时吗？没装返回 null。 */
WV_JNI(jstring, nativeRuntimeVersion)(WV_JNI_PARAMS) {
  const std::wstring v = runtimeVersion();
  if (v.empty()) return nullptr;
  return fromWide(env, v);
}

/**
 * 起 WebView 线程、建环境、注册回调。
 *
 * 返回 null = 成功；非 null = 失败原因（给脚本看的人类可读消息）。
 * 会阻塞到环境就绪或超时 —— 调用方要在后台线程上调。
 */
WV_JNI(jstring, nativeStart)(JNIEnv* env, jobject, jobject callback,
                             jstring userDataDir, jint timeoutMs) {
  if (g_thread.joinable()) {
    std::lock_guard<std::mutex> lock(g_cbMutex);
    if (g_cb) env->DeleteGlobalRef(g_cb);
    g_cb = env->NewGlobalRef(callback);
    return nullptr;
  }

  jclass cls = env->GetObjectClass(callback);
  g_midIntercept = env->GetMethodID(
      cls, "nativeInterceptRequest",
      "(JLjava/lang/String;Ljava/lang/String;Z[Ljava/lang/String;)Z");
  g_midFinished =
      env->GetMethodID(cls, "nativeFinished", "(JLjava/lang/String;)V");
  g_midFailed = env->GetMethodID(cls, "nativeFailed", "(JLjava/lang/String;)V");
  g_midViewEvent = env->GetMethodID(
      cls, "nativeViewEvent", "(JILjava/lang/String;Ljava/lang/String;)V");
  g_midScriptResult =
      env->GetMethodID(cls, "nativeScriptResult", "(JJLjava/lang/String;)V");
  env->DeleteLocalRef(cls);
  if (!g_midIntercept || !g_midFinished || !g_midFailed || !g_midViewEvent ||
      !g_midScriptResult) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return fromWide(
        env,
        L"回调对象缺少 nativeInterceptRequest/nativeFinished/nativeFailed/"
        L"nativeViewEvent/nativeScriptResult 之一");
  }
  {
    std::lock_guard<std::mutex> lock(g_cbMutex);
    g_cb = env->NewGlobalRef(callback);
  }

  const std::wstring error =
      ensureStarted(toWide(env, userDataDir), static_cast<int>(timeoutMs));
  if (error.empty()) return nullptr;
  return fromWide(env, error);
}

/**
 * 跑一个后台任务。返回视图句柄（>0，就是窗口句柄）；立不起来返回 -1，
 * 失败详情通过 nativeFailed 回传。
 * `headers` 是摊平的 [name, value, ...]；`script` 为 null 表示不注入。
 */
WV_JNI(jlong, nativeRun)(JNIEnv* env, jobject, jstring url,
                         jobjectArray headers, jstring script, jlong token) {
  {
    std::lock_guard<std::mutex> lock(g_envMutex);
    if (!g_envDone || !g_envError.empty()) return -1;
  }
  WV_LOG("nativeRun: token=%lld url=%ls", static_cast<long long>(token),
         toWide(env, url).c_str());

  auto view = std::make_shared<View>();
  view->background = true;
  view->token = token;
  view->url = toWide(env, url);
  view->headers = readHeaderArray(env, headers);
  if (script) {
    view->script = toWide(env, script);
    view->hasScript = true;
  }
  ViewOptions opt;
  opt.token = token;
  opt.url = view->url;

  if (!runSync(
          [view, opt] {
            const HWND hwnd = createViewWindow(nullptr, true);
            if (!hwnd) {
              WV_LOG("nativeRun: CreateWindowExW failed (%lu)", GetLastError());
              return;
            }
            view->hwnd = hwnd;
            {
              std::lock_guard<std::mutex> lock(g_viewsMutex);
              g_views[hwnd] = view;
            }
            auto* handler = new (std::nothrow) ControllerHandler(hwnd, opt);
            if (!handler) {
              destroyView(hwnd);
              return;
            }
            const HRESULT hr =
                g_env->CreateCoreWebView2Controller(hwnd, handler);
            handler->Release();
            if (FAILED(hr)) destroyView(hwnd);
          },
          5000)) {
    WV_LOG("nativeRun: 建窗口/控制器同步任务超时 (token=%lld)",
           static_cast<long long>(token));
    // 任务可能已经建好窗口、只是被别的活儿堵住了消息泵（比如上一个视图的
    // `controller->Close()` 要等好几秒）。别让这个视图漏在 g_views 里，
    // 补投一个销毁 —— 消息泵一空出来它自然会跑到。
    if (view->hwnd) {
      const HWND stale = view->hwnd;
      postTask([stale] { destroyView(stale); });
    }
    return -1;
  }

  const HWND hwnd = view->hwnd;
  if (!hwnd) {
    WV_LOG("nativeRun: 宿主窗口没建起来 (token=%lld)",
           static_cast<long long>(token));
    return -1;
  }
  WV_LOG("nativeRun: handle=%p token=%lld", hwnd,
         static_cast<long long>(token));
  return static_cast<jlong>(reinterpret_cast<intptr_t>(hwnd));
}

/** 取消/销毁一个后台任务（JVM 侧拿到结果后调用）。 */
WV_JNI(void, nativeCancel)(JNIEnv*, jobject, jlong id) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(id));
  if (!postTask([hwnd] { destroyView(hwnd); })) {
    std::lock_guard<std::mutex> lock(g_viewsMutex);
    g_views.erase(hwnd);
  }
}

/**
 * 建一个可见 WebView 视图，返回它的窗口句柄；失败返回 0。
 *
 * 此时窗口还是隐藏的顶层 `WS_POPUP`，**什么都没显示** —— Kotlin 侧要等
 * `SwingPanel { Canvas() }` 里的 Canvas 变 `isDisplayable`（拿到
 * peer/HWND）之后， 再调 `nativeViewAttach(handle, canvas)` 把它挂上去，由
 * `nativeViewFitToParent` 负责尺寸。
 *
 * `parentHwnd` 保留在签名里只为兼容旧调用点，实际**不用** —— 父窗口是 attach
 * 时现取的（AWT 组件的 peer 在 attach 之前可能都还没建）。
 *
 * 建窗口必须在 WebView 线程上（窗口消息只在创建它的线程派发），所以这里同步等
 * 一下 —— 纯建窗口，没有 COM 调用，正常是微秒级。
 */
WV_JNI(jlong, nativeCreateView)(JNIEnv* env, jobject, jlong parentHwnd,
                                jstring userAgent, jstring url,
                                jboolean enableDevtools, jdouble zoom) {
  {
    std::lock_guard<std::mutex> lock(g_envMutex);
    if (!g_envDone || !g_envError.empty()) return 0;
  }

  auto view = std::make_shared<View>();
  view->background = false;
  ViewOptions opt;
  opt.userAgent = toWide(env, userAgent);
  opt.url = toWide(env, url);
  opt.enableDevtools = enableDevtools == JNI_TRUE;
  opt.zoom = zoom;
  (void)parentHwnd;  // 父窗口在 nativeViewAttach 里现取

  if (!runSync(
          [view, opt] {
            const HWND hwnd = createViewWindow(nullptr, false);
            if (!hwnd) {
              WV_LOG("nativeCreateView: CreateWindowExW failed (%lu)",
                     GetLastError());
              return;
            }
            view->hwnd = hwnd;
            {
              std::lock_guard<std::mutex> lock(g_viewsMutex);
              g_views[hwnd] = view;
            }
            auto* handler = new (std::nothrow) ControllerHandler(hwnd, opt);
            if (!handler) {
              destroyView(hwnd);
              return;
            }
            const HRESULT hr =
                g_env->CreateCoreWebView2Controller(hwnd, handler);
            handler->Release();
            if (FAILED(hr)) destroyView(hwnd);
          },
          5000)) {
    return 0;
  }
  return static_cast<jlong>(reinterpret_cast<intptr_t>(view->hwnd));
}

WV_JNI(void, nativeDestroyView)(JNIEnv*, jobject, jlong handle) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  if (!postTask([hwnd] { destroyView(hwnd); })) {
    std::lock_guard<std::mutex> lock(g_viewsMutex);
    g_views.erase(hwnd);
  }
}

/**
 * 更新可见视图的尺寸（兼容入口；正常路径是 attach 之后的
 * `nativeViewFitToParent`）。
 *
 * 已经 attach 的视图尺寸**以父窗口客户区为准**，这里报来的值只在还没 attach 时
 * 当缓存用 —— 免得 AWT 和父窗口客户区因为 DPI 取整差一两个像素互相打架。
 */
WV_JNI(void, nativeSetViewBounds)(JNIEnv*, jobject, jlong handle, jint w,
                                  jint h) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  const int width = w < 1 ? 1 : w;
  const int height = h < 1 ? 1 : h;
  postTask([hwnd, width, height] {
    std::shared_ptr<View> view = findView(hwnd);
    if (!view || view->destroyed) return;
    view->pendingBounds = RECT{0, 0, width, height};
    view->hasPendingBounds = true;
    if (view->parent) {
      fitViewToParent(view);
      return;
    }
    if (view->ready && view->controller)
      view->controller->put_Bounds(view->pendingBounds);
  });
}

/**
 * 把可见视图挂到 AWT 组件上（`SwingPanel { Canvas() }` 里那个 Canvas）。
 *
 * 调用前提：组件已经 `isDisplayable`（有 peer/HWND）。JAWT 取句柄这一步留在
 * **AWT 线程**上做（组件 peer 归属那个线程），只读、不碰绘制；`SetParent`
 * 之后的事全部投到 WebView 线程。
 *
 * 顺序不能反：必须先 `SetParent` 再改样式 —— 反过来在父链还没接上时把窗口改成
 * `WS_CHILD`，窗口会直接消失。改完样式 `ShowWindow`，此时才有东西可看。
 */
WV_JNI(void, nativeViewAttach)(JNIEnv* env, jobject, jlong handle,
                               jobject awtComponent) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  const HWND parent = awtHwndOf(env, awtComponent);
  if (!parent) {
    WV_LOG("nativeViewAttach: 拿不到 AWT 组件 HWND（组件还没 displayable？）");
    return;
  }
  postTask([hwnd, parent] {
    std::shared_ptr<View> view = findView(hwnd);
    if (!view || view->destroyed) return;
    view->parent = parent;
    SetParent(hwnd, parent);

    LONG_PTR style = GetWindowLongPtrW(hwnd, GWL_STYLE);
    style &= ~static_cast<LONG_PTR>(WS_POPUP | WS_CAPTION | WS_THICKFRAME);
    style |= WS_CHILD | WS_CLIPCHILDREN | WS_CLIPSIBLINGS;
    SetWindowLongPtrW(hwnd, GWL_STYLE, style);
    // 光 SetWindowLong 不够 —— 让系统按新样式重算一次非客户区/裁剪。
    // 尺寸交给下面的 fitViewToParent。
    SetWindowPos(hwnd, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE |
                     SWP_FRAMECHANGED);

    // 输入队列的接续就在这里做**一次**，之后不再动。
    //
    // `AttachThreadInput` 只吃两个线程 ID，跟窗口父子关系无关；目标线程是 AWT
    // 的 EDT（`SunAwtFrame` 的拥有者），进程内恒定 —— 所以接上就永久有效。
    // 会变的只有**父窗口 HWND**（SwingPanel 重建），而那本来就要重新
    // `SetParent`、 也就是重新走到这里；新的根窗口仍属 EDT，接上的那条不用换。
    // `ensureThreadAttached` 自带幂等判断，重复调用零成本。
    ensureThreadAttached(view, hwnd, GetWindowThreadProcessId(hwnd, nullptr));

    ShowWindow(hwnd, SW_SHOWNA);
    WV_LOG("view %p: attach 到 AWT 组件 %p", hwnd, parent);
    fitViewToParent(view);
  });
}

/**
 * AWT 组件尺寸变化时调：按父窗口客户区重新贴合窗口与控制器。
 * 还没 attach 的视图直接忽略（没父窗口就没有"贴合"可言）。
 */
WV_JNI(void, nativeViewFitToParent)(JNIEnv*, jobject, jlong handle) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  postTask([hwnd] {
    std::shared_ptr<View> view = findView(hwnd);
    if (!view || view->destroyed || !view->parent) return;
    fitViewToParent(view);
  });
}

WV_JNI(void, nativeSetViewVisible)(JNIEnv*, jobject, jlong handle,
                                   jboolean visible) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  const BOOL v = visible == JNI_TRUE ? TRUE : FALSE;
  postTask([hwnd, v] {
    std::shared_ptr<View> view = findView(hwnd);
    if (!view || view->destroyed) return;
    // 还没 attach 的可见视图仍是隐藏的顶层弹出窗口，这时 ShowWindow 会在屏幕
    // 左上角闪一个 1x1 的窗口。等 attach 之后自然会有 ShowWindow。
    if (view->background || view->parent)
      ShowWindow(hwnd, v ? SW_SHOWNA : SW_HIDE);
    if (view->controller) view->controller->put_IsVisible(v);
  });
}

WV_JNI(void, nativeViewLoadUrl)(JNIEnv* env, jobject, jlong handle, jstring url,
                                jobjectArray headers) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  const std::wstring u = toWide(env, url);
  const std::vector<std::wstring> h = readHeaderArray(env, headers);
  postTask([hwnd, u, h] {
    std::shared_ptr<View> view = findView(hwnd);
    if (!view) return;
    if (!view->ready || !view->webview) {
      // 控制器还没就绪：先存着，configureView 里补上。
      view->pendingUrl = u;
      view->pendingHeaders = h;
      return;
    }
    if (!h.empty() && view->env2) {
      ComPtr<ICoreWebView2_2> wv2;
      if (SUCCEEDED(view->webview->QueryInterface(
              IID_ICoreWebView2_2,
              reinterpret_cast<void**>(wv2.GetAddressOf())))) {
        ComPtr<ICoreWebView2WebResourceRequest> request;
        const std::wstring block = buildHeaderBlock(h);
        if (SUCCEEDED(view->env2->CreateWebResourceRequest(
                u.c_str(), L"GET", nullptr, block.c_str(),
                request.GetAddressOf())) &&
            request &&
            SUCCEEDED(wv2->NavigateWithWebResourceRequest(request.Get())))
          return;
      }
    }
    view->webview->Navigate(u.c_str());
  });
}

WV_JNI(void, nativeViewLoadHtml)(JNIEnv* env, jobject, jlong handle,
                                 jstring html) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  const std::wstring body = toWide(env, html);
  postTask([hwnd, body] {
    std::shared_ptr<View> view = findView(hwnd);
    if (view && view->webview) view->webview->NavigateToString(body.c_str());
  });
}

WV_JNI(void, nativeViewAction)(JNIEnv*, jobject, jlong handle, jint action) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  postTask([hwnd, action] {
    std::shared_ptr<View> view = findView(hwnd);
    if (!view || !view->webview) return;
    switch (action) {
      case 0:
        view->webview->GoBack();
        break;
      case 1:
        view->webview->GoForward();
        break;
      case 2:
        view->webview->Reload();
        break;
      case 3:
        view->webview->Stop();
        break;
      case 4:
        view->webview->OpenDevToolsWindow();
        break;
      default:
        break;
    }
  });
}

WV_JNI(void, nativeViewEvaluate)(JNIEnv* env, jobject, jlong handle,
                                 jstring script, jlong token) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  const std::wstring code = toWide(env, script);
  postTask([hwnd, code, token] {
    std::shared_ptr<View> view = findView(hwnd);
    if (!view || !view->webview) {
      notifyScriptResult(hwnd, token, L"", false);
      return;
    }
    auto* handler = new (std::nothrow) ScriptHandler(hwnd, token);
    if (!handler) {
      notifyScriptResult(hwnd, token, L"", false);
      return;
    }
    const HRESULT hr = view->webview->ExecuteScript(code.c_str(), handler);
    handler->Release();
    if (FAILED(hr)) notifyScriptResult(hwnd, token, L"", false);
  });
}

WV_JNI(void, nativeViewFocus)(JNIEnv*, jobject, jlong handle) {
  const HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(handle));
  postTask([hwnd] { requestWebViewFocus(hwnd, 2); });
}

/**
 * 探针：在**另一条** STA + 消息泵的线程上，用「在 WebView 线程上建出来的
 * environment」 建一个控制器。
 *
 * 动机：想把可见视图改成 wvbridge 那样**建在 AWT
 * 线程上**（父子同线程，焦点链天然连通，就不需要 `AttachThreadInput`
 * 那套），同时让后台视图留在 WebView 线程上 （脚本抓页面不该占用 UI
 * 线程）。但两边必须共用**同一个 environment** —— cookie 是按 user data folder
 * 分的，两个 environment 就是两套登录态。
 *
 * 官方文档只写了「控制器必须建在有消息泵的 UI
 * 线程上、所有调用都得在那条线程」，
 * **没写一个 environment 能不能跨两个 UI 线程**。这条探针就是补上这个答案。
 *
 * @return 创建回调收到的 HRESULT；0 (S_OK) = 这个架构可行。
 */
namespace {

/** 探针等「跨线程建控制器」回调的上界；超时当失败，不让 join 挂死。 */
#define PROBE_TIMEOUT_MS 25000

std::atomic<HRESULT> g_probeHr{E_FAIL};
std::atomic<bool> g_probeDone{false};

struct ProbeControllerHandler final
    : public ComCallback<
          ICoreWebView2CreateCoreWebView2ControllerCompletedHandler> {
  ProbeControllerHandler()
      : ComCallback(
            IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler) {}

  HRESULT STDMETHODCALLTYPE
  Invoke(HRESULT errorCode, ICoreWebView2Controller* controller) override {
    WV_LOG(
        "probe: 跨线程 CreateCoreWebView2Controller 回调 hr=0x%08lX "
        "controller=%p",
        static_cast<unsigned long>(errorCode), static_cast<void*>(controller));
    g_probeHr.store(errorCode);
    // 探针不留东西：拿到就关掉。Close
    // 的收尾要继续抽消息，所以下面还会泵一会儿。
    if (controller) controller->Close();
    g_probeDone.store(true);
    return S_OK;
  }
};

void probeThreadMain() {
  CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  struct Uninit {
    ~Uninit() { CoUninitialize(); }
  } uninit;

  const HWND hwnd = createViewWindow(nullptr, true);
  if (!hwnd) {
    WV_LOG("probe: 建窗口失败 err=%lu", GetLastError());
    g_probeHr.store(HRESULT_FROM_WIN32(GetLastError()));
    g_probeDone.store(true);
    return;
  }

  auto* handler = new (std::nothrow) ProbeControllerHandler();
  HRESULT hr = E_FAIL;
  {
    std::lock_guard<std::mutex> lock(g_envMutex);
    if (g_env && handler)
      hr = g_env->CreateCoreWebView2Controller(hwnd, handler);
  }
  if (handler) handler->Release();
  WV_LOG("probe: CreateCoreWebView2Controller 调用返回 hr=0x%08lX",
         static_cast<unsigned long>(hr));
  if (FAILED(hr)) {
    g_probeHr.store(hr);
    g_probeDone.store(true);
  }

  // 回调要靠这条线程自己抽消息才会到 —— 这正是「UI 线程必须有消息泵」的含义。
  const DWORD deadline = GetTickCount() + PROBE_TIMEOUT_MS;
  MSG msg{};
  while (!g_probeDone.load() &&
         static_cast<int>(GetTickCount() - deadline) < 0) {
    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
      TranslateMessage(&msg);
      DispatchMessageW(&msg);
    }
    if (!g_probeDone.load())
      MsgWaitForMultipleObjects(0, nullptr, FALSE, 30, QS_ALLINPUT);
  }
  if (!g_probeDone.load()) {
    WV_LOG("probe: 等不到创建回调（超时 %dms）", PROBE_TIMEOUT_MS);
    g_probeHr.store(HRESULT_FROM_WIN32(WAIT_TIMEOUT));
  }

  DestroyWindow(hwnd);
}

}  // namespace

WV_JNI(jint, nativeProbeCrossThreadController)(JNIEnv*, jobject) {
  g_probeHr.store(E_FAIL);
  g_probeDone.store(false);
  // 线程自己有超时上界，join 不会挂死。
  std::thread(probeThreadMain).join();
  return static_cast<jint>(g_probeHr.load());
}

/**
 * 停掉 WebView 线程并释放全局引用。进程退出前调。
 *
 * 顺序是照 wvbridge 的 `destroy_ctx` 学的，**必须先拆视图再停泵**：
 *
 *   1. 在消息泵**还活着**的时候 `controller->Close()` 掉所有视图。
 *      `Close()` 的收尾（浏览器进程通知、COM 引用释放）是异步的，要靠继续抽消息
 *      才能跑完 —— 之前我们是先 `WM_CLOSE` 停泵、再在泵外面 `Close()`，于是那些
 *      收尾工作永远没机会执行，`CoUninitialize()`（以及 DLL
 * 卸载时的静态析构）就卡在那里，表现成线程退不掉。
 *   2. 再让消息泵退出：先 `WM_CLOSE` 掉宿主窗口，**兜底**再
 * `PostThreadMessageW(WM_QUIT)` ——
 * 后者不依赖任何窗口存活，窗口已经被销毁过也不会失败。
 *
 * **等待必须是有界的**：环境一旦是坏的（实测过 WebView2 浏览器进程 `CHECK`
 * 失败之后， `controller->Close()` 能卡住十几秒甚至更久），无界 `join()` 会把
 * JVM 的 shutdown hook 一起吊死 —— 进程退不掉，外面看到的就是 `Test Executor
 * finished with non-zero exit value 3`（Windows 上 `abort()` 的退出码）。超时就
 * detach：线程还挂在里面，但至少不再阻塞退出，也不会因为静态 `std::thread`
 * 析构时还 joinable 而 `std::terminate`。
 */
WV_JNI(void, nativeStop)(WV_JNI_PARAMS) {
  if (g_thread.joinable()) {
    // ---- 1. 泵还活着，先把视图拆干净 ----
    const bool tornDown = runSync(
        [] {
          std::vector<HWND> handles;
          {
            std::lock_guard<std::mutex> lock(g_viewsMutex);
            for (const auto& entry : g_views) handles.push_back(entry.first);
          }
          WV_LOG("nativeStop: 拆 %zu 个视图", handles.size());
          for (const HWND h : handles) destroyView(h);
        },
        3000);
    if (!tornDown)
      WV_LOG("nativeStop: 拆视图的同步任务没跑完（3s），直接进退出流程");

    // ---- 2. 停泵 ----
    if (HWND dispatcher = g_dispatcher.load())
      PostMessageW(dispatcher, WM_CLOSE, 0, 0);
    // 兜底：宿主窗口可能已经没了（WM_CLOSE
    // 走的是窗口消息，窗口一销毁就再也投不进去）。
    // 线程消息不依赖窗口，只要消息泵还在 GetMessage 就一定能被抽到。
    if (const DWORD tid = g_threadId.load())
      PostThreadMessageW(tid, WM_QUIT, 0, 0);
  }

  bool threadExited = true;
  if (g_thread.joinable()) {
    std::unique_lock<std::mutex> lock(g_threadMutex);
    threadExited = g_threadCv.wait_for(lock, std::chrono::seconds(5),
                                       [] { return g_threadExited; });
    lock.unlock();
    if (threadExited) {
      g_thread.join();
    } else {
      WV_LOG(
          "nativeStop: WebView 线程 5s 没退出来（大概卡在关闭 WebView2 上），"
          "detach 掉，不再阻塞进程退出");
      g_thread.detach();
      // 让后续的 postTask 直接失败，而不是静默投给一个已经没人抽的消息泵。
      g_dispatcher.store(nullptr);
      g_threadRunning = false;
    }
  }

  // 线程还活着的话，这些东西它随时可能再摸，别去动 —— 进程马上也没了。
  if (!threadExited) return;

  {
    std::lock_guard<std::mutex> lock(g_viewsMutex);
    g_views.clear();
  }
  g_env.Reset();
  {
    std::lock_guard<std::mutex> lock(g_cbMutex);
    if (g_cb) {
      env->DeleteGlobalRef(g_cb);
      g_cb = nullptr;
    }
  }
  g_midIntercept = nullptr;
  g_midFinished = nullptr;
  g_midFailed = nullptr;
  g_midViewEvent = nullptr;
  g_midScriptResult = nullptr;
  g_loader = nullptr;
  g_pfnCreateEnv = nullptr;
  g_pfnGetVersion = nullptr;
}
