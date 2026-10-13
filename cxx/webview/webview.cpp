#include <jawt.h>
#include <jawt_md.h>
#include <jni.h>
#include <windows.h>
#include <wrl/client.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "WebView2.h"
// #include "WebView2EnvironmentOptions.h"
#define CORE_WEBVIEW_TARGET_PRODUCT_VERSION L"131.0.2903.40"

using Microsoft::WRL::ComPtr;

#define WV_WM_TASK (WM_APP + 1)

// ---------------------------------------------------------------------------
// 日志 —— 一律写 stderr。
//
// 原生日志是这个宿主唯一的可观测面（WebView2 自己不报告有用信息）。消费端只有
// Gradle 起的测试与桌面端进程，两个都有 stderr。
//
// 每条都 `fflush`：宿主进程与 JVM 各有各的缓冲，不刷新会和 `System.err` 的输出
// 错序，看到的时间戳就不再可信了。
// ---------------------------------------------------------------------------
/** 日志用的相对时间戳（从首次调用算起）。看时序问题全靠它。 */
static long long logNowMs() {
  static const auto start = std::chrono::steady_clock::now();
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now() - start)
      .count();
}

#define WV_LOG(...)                                         \
  do {                                                      \
    std::fprintf(stderr, "[webview +%lldms] ", logNowMs()); \
    std::fprintf(stderr, __VA_ARGS__);                      \
    std::fprintf(stderr, "\n");                             \
    std::fflush(stderr);                                    \
  } while (0)

// ---------------------------------------------------------------------------
// JAWT：从 AWT 组件取原生 HWND
//
// 「把这个上下文挂进 UI」就是把它 `SetParent` 到 AWT 组件（`SwingPanel` 里的
// Canvas）的 HWND 上。取句柄这一步留在**调用线程**上做（一般是 AWT 的 EDT ——
// 组件 peer 归属那个线程），只读、不碰绘制。
// ---------------------------------------------------------------------------

/**
 * 取 AWT 组件的原生窗口句柄；组件还没 peer（没 `isDisplayable`）就返回
 * nullptr。
 *
 * `Lock` / `Unlock` 必须成对，`GetDrawingSurfaceInfo` 拿到的东西必须
 * `FreeDrawingSurfaceInfo` 还回去 —— 漏了会泄漏。
 */
static HWND awtHwndOf(JNIEnv* env, jobject component) {
  if (!env || !component) return nullptr;
  JAWT awt{};
  awt.version = JAWT_VERSION_1_4;
  if (!JAWT_GetAWT(env, &awt)) {
    WV_LOG("JAWT_GetAWT 失败");
    return nullptr;
  }
  JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, component);
  if (!ds) {
    WV_LOG("GetDrawingSurface 返回 null（组件还没 displayable？）");
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
// 手写的 COM 回调基类
//
// MinGW 的 WRL 只移植了 ComPtr（wrl.h 里 implements.h / event.h 都是注释掉
// 的），没有 `Microsoft::WRL::RuntimeClass` / `Make<>` / `Callback<>`。所以
// 自己实现引用计数 + QueryInterface，IID 由子类显式传入。
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
// 字符串与请求头
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

/** 从 JVM 的 String[] 读摊平的请求头。 */
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

/** 摊平的请求头 → WebView2 要的 `name: value\r\n` 串。 */
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
// 窗口创建
// ---------------------------------------------------------------------------
static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);
HWND createWindow() {
  LPCWSTR kWindowClass = L"AcgNativeWebViewHost";
  WNDCLASSEXW wc{};
  wc.cbSize = sizeof(wc);
  wc.lpfnWndProc = &wndProc;
  wc.hInstance = GetModuleHandleW(nullptr);
  wc.lpszClassName = kWindowClass;
  if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
    WV_LOG("RegisterClassExW failed (0x%08lX)", GetLastError());
    return 0;
  }
  HWND hwnd = CreateWindowExW(
      0, kWindowClass, L"", WS_POPUP | WS_CLIPCHILDREN | WS_CLIPSIBLINGS, 0, 0,
      1, 1, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
  if (!hwnd) {
    WV_LOG("CreateWindowExW failed (0x%08lX)", GetLastError());
    return 0;
  }
  return hwnd;
}
// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
interface WebviewContextHandler {
  virtual void flushPending(ICoreWebView2Environment * env) = 0;
};

class WebviewEnviorment
    : public ICoreWebView2EnvironmentOptions,
      public ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler {
  std::wstring args_, lang_, udf_;
  std::wstring target_ = CORE_WEBVIEW_TARGET_PRODUCT_VERSION;
  BOOL sso_ = FALSE;

  std::mutex mu_;
  std::thread thread_;
  // 只设不清空，判断用thread_.joinable();
  DWORD threadId_ = 0;
  JavaVM* vm_ = nullptr;
  JNIEnv* jenv_ = nullptr;
  ComPtr<ICoreWebView2Environment> env_;
  ComPtr<ICoreWebView2Environment2> env2_;
  std::vector<WebviewContextHandler*> handlers_;
  bool isClosed = false;

  // 拉起Context消费，由外部加锁
  void flushPending(ICoreWebView2Environment* env) {
    for (auto& handler : handlers_) handler->flushPending(env);
  }

  void threadMain();

  WebviewEnviorment() = default;

 public:
  ICoreWebView2Environment* env() const { return env_.Get(); }
  ICoreWebView2Environment2* env2() const { return env2_.Get(); }

  /** 进程内唯一一份：environment 与宿主线程都挂在它上面。 */
  static WebviewEnviorment& instance() {
    static WebviewEnviorment host;
    return host;
  }
  ~WebviewEnviorment() { releaseEnvironment(); }

  // 初始化，应该只调用一次。
  void prepareEnvironment(JavaVM* vm, const std::wstring& args,
                          const std::wstring& udf) {
    // 只做准备不启动线程
    std::lock_guard<std::mutex> lock(mu_);
    vm_ = vm;
    args_ = args;
    udf_ = udf;
    isClosed = false;
  }
  // 释放，幂等，在程序结束后强制调用一次。
  void releaseEnvironment() {
    std::thread thread;
    {
      std::lock_guard<std::mutex> lock(mu_);
      isClosed = true;
      flushPending(nullptr);
      thread = std::move(thread_);
      if (threadId_) PostThreadMessageW(threadId_, WM_QUIT, 0, 0);
    }
    if (thread.joinable()) thread.join();
  }

  void registerHandler(WebviewContextHandler* handler) {
    std::lock_guard<std::mutex> lock(mu_);
    if (isClosed) return;
    handlers_.push_back(handler);
    if (!vm_) {
      WV_LOG("WebviewEnviorment not prepared");
      flushPending(nullptr);
      return;
    };
    if (thread_.joinable()) {
      if (threadId_) PostThreadMessageW(threadId_, WV_WM_TASK, 0, 0);
    } else {
      // 线程崩溃就重新打开
      thread_ = std::thread(&WebviewEnviorment::threadMain, this);
    }
  }

  void unregisterHandler(WebviewContextHandler* handler) {
    std::lock_guard<std::mutex> lock(mu_);
    handlers_.erase(std::remove(handlers_.begin(), handlers_.end(), handler),
                    handlers_.end());
  }

  bool callJava(const std::function<bool(JNIEnv*)>& body) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!jenv_) return false;
    bool ret = body(jenv_);
    if (jenv_->ExceptionCheck()) {
      jenv_->ExceptionDescribe();
      jenv_->ExceptionClear();
    }
    return ret;
  }

  bool notify() {
    if (isClosed) return false;
    DWORD threadId = threadId_;
    if (threadId) PostThreadMessageW(threadId, WV_WM_TASK, 0, 0);
    return true;
  }

  // IUnknown
  ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
  ULONG STDMETHODCALLTYPE Release() override { return 1; }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
    // 必须写 `*ppv` —— COM 契约要求返回 `S_OK` 时把接口指针交出去。只写
    // `return S_OK` 会让调用方拿到未初始化的指针再解引用，直接崩。
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (riid == IID_IUnknown || riid == IID_ICoreWebView2EnvironmentOptions) {
      *ppv = static_cast<ICoreWebView2EnvironmentOptions*>(this);
      return S_OK;
    }
    if (riid ==
        IID_ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler) {
      *ppv = static_cast<
          ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler*>(this);
      return S_OK;
    }
    return E_NOINTERFACE;
  }
  // ICoreWebView2EnvironmentOptions
  STDMETHODIMP get_AllowSingleSignOnUsingOSPrimaryAccount(
      BOOL* value) override {
    if (!value) return E_POINTER;
    *value = sso_;
    return S_OK;
  }
  STDMETHODIMP put_AllowSingleSignOnUsingOSPrimaryAccount(BOOL value) override {
    sso_ = value;
    return S_OK;
  }
#define IMPLEMENT_ICoreWebView2EnvironmentOptions(prop_name, variable) \
  STDMETHODIMP get_##prop_name(LPWSTR* value) override {               \
    if (!value) return E_POINTER;                                      \
    const SIZE_T cb = (variable.size() + 1) * sizeof(wchar_t);         \
    LPWSTR p = static_cast<LPWSTR>(::CoTaskMemAlloc(cb));              \
    if (!p) return E_OUTOFMEMORY;                                      \
    std::wmemcpy(p, variable.c_str(), variable.size() + 1);            \
    *value = p;                                                        \
    return S_OK;                                                       \
  }                                                                    \
  STDMETHODIMP put_##prop_name(LPCWSTR value) override {               \
    variable = (value ? value : L"");                                  \
    return S_OK;                                                       \
  }
  IMPLEMENT_ICoreWebView2EnvironmentOptions(AdditionalBrowserArguments, args_)
      IMPLEMENT_ICoreWebView2EnvironmentOptions(Language, lang_)
          IMPLEMENT_ICoreWebView2EnvironmentOptions(
              TargetCompatibleBrowserVersion, target_)
#undef IMPLEMENT_ICoreWebView2EnvironmentOptions
      // ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler
      HRESULT STDMETHODCALLTYPE
      Invoke(HRESULT hr, ICoreWebView2Environment* env) override {
    if (FAILED(hr) || !env) {
      WV_LOG("ICoreWebView2Environment create failed (0x%08lX)", hr);
      {
        std::lock_guard<std::mutex> lock(mu_);
        flushPending(nullptr);
      }
      // 初始化失败就直接退出线程
      PostQuitMessage(0);
      return S_OK;
    }
    WV_LOG("ICoreWebView2Environment created");
    env_ = env;
    env->QueryInterface(IID_ICoreWebView2Environment2, &env2_);
    {
      std::lock_guard<std::mutex> lock(mu_);
      flushPending(env_.Get());
    }
    return S_OK;
  }
};

// Webview线程
void WebviewEnviorment::threadMain() {
  WV_LOG("thread_ begin");
  {
    std::lock_guard<std::mutex> lock(mu_);
    WV_LOG("thread_ begin lock");
    threadId_ = GetCurrentThreadId();
  }
  MSG msg{};
  HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  if (FAILED(hr)) {
    WV_LOG("CoInitializeEx failed (0x%08lX)", hr);
    goto exit_thread;
  }
  vm_->AttachCurrentThread(reinterpret_cast<void**>(&jenv_), nullptr);
  hr = CreateCoreWebView2EnvironmentWithOptions(
      nullptr, udf_.empty() ? nullptr : udf_.c_str(), this, this);
  if (FAILED(hr)) {
    WV_LOG("CreateCoreWebView2EnvironmentWithOptions failed (0x%08lX)", hr);
    goto exit_thread;
  }
  while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
    // 线程消息（`hwnd == nullptr`）是投给本线程的任务
    if (msg.hwnd == nullptr && msg.message == WV_WM_TASK) {
      {
        std::lock_guard<std::mutex> lock(mu_);
        // 环境已经初始化，则直接执行，没初始化时由初始化回调触发
        if (env_) flushPending(env_.Get());
      }
      continue;
    }
    TranslateMessage(&msg);
    DispatchMessageW(&msg);
  }
exit_thread:
  WV_LOG("Closing Webview thread");
  {
    // 清空等待回调
    std::lock_guard<std::mutex> lock(mu_);
    flushPending(nullptr);
    env_.Reset();
  }
  if (jenv_) {
    vm_->DetachCurrentThread();
    jenv_ = nullptr;
  }
  CoUninitialize();
  WV_LOG("Webview thread closed");
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_prepareEnvironment(JNIEnv* env, jclass,
                                                        jstring args,
                                                        jstring userDir) {
  JavaVM* vm;
  env->GetJavaVM(&vm);
  WebviewEnviorment::instance().prepareEnvironment(vm, toWide(env, args),
                                                   toWide(env, userDir));
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_releaseEnvironment(JNIEnv*, jclass) {
  WebviewEnviorment::instance().releaseEnvironment();
}

class WebviewContext
    : public WebviewContextHandler,
      public ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
      public ICoreWebView2WebResourceRequestedEventHandler,
      public ICoreWebView2NavigationCompletedEventHandler,
      public ICoreWebView2NavigationStartingEventHandler,
      public ICoreWebView2DocumentTitleChangedEventHandler,
      public ICoreWebView2ProcessFailedEventHandler {
  std::mutex mu_;
  jobject ctx_ = nullptr;
  jmethodID onWebResourceRequested;
  jmethodID onEvent;
  HWND hwnd_ = nullptr;
  HWND parent_;
  HANDLE init_;

  ComPtr<ICoreWebView2Controller> ctrl_;
  ComPtr<ICoreWebView2> webview_;
  ComPtr<ICoreWebView2_2> webview2_;
  ComPtr<ICoreWebView2CookieManager> cookieManager_;

  std::vector<std::function<void(ICoreWebView2Environment*)>> pending_;
  bool post_(std::function<void(ICoreWebView2Environment*)> fn) {
    std::lock_guard<std::mutex> lock(mu_);
    pending_.push_back(std::move(fn));
    return WebviewEnviorment::instance().notify();
  }

  void callOnEvent(std::wstring event, std::wstring data) {
    WebviewEnviorment::instance().callJava([&, event, data](JNIEnv* env) {
      jobject ctx = getCtx(env);
      if (!ctx) return false;
      jstring jevent = fromWide(env, event);
      jstring jdata = fromWide(env, data);
      env->CallVoidMethod(ctx, onEvent, jevent, jdata);
      env->DeleteLocalRef(jevent);
      env->DeleteLocalRef(jdata);
      return true;
    });
  }

 public:
  ICoreWebView2* webview() { return webview_.Get(); }
  ICoreWebView2_2* webview2() { return webview2_.Get(); }
  ICoreWebView2Controller* controller() { return ctrl_.Get(); }
  ICoreWebView2CookieManager* cookieManager() { return cookieManager_.Get(); }

  WebviewContext(JNIEnv* env, jobject ctx, HANDLE init) : init_(init) {
    if (env && ctx) {
      ctx_ = env->NewGlobalRef(ctx);
      jclass cls = env->GetObjectClass(ctx);
      onWebResourceRequested = env->GetMethodID(
          cls, "onWebResourceRequested",
          "(Ljava/lang/String;Ljava/lang/String;Z[Ljava/lang/String;)Z");
      onEvent = env->GetMethodID(cls, "onEvent",
                                 "(Ljava/lang/String;Ljava/lang/String;)V");
      env->DeleteLocalRef(cls);
    }
    post_([this](ICoreWebView2Environment* env) {
      if (env) {
        HWND hwnd = createWindow();
        if (!hwnd) return;
        hwnd_ = hwnd;
        env->CreateCoreWebView2Controller(hwnd, this);
      } else {
        SetEvent(init_);
      }
    });
    WebviewEnviorment::instance().registerHandler(this);
  }

  ~WebviewContext() {
    // 顺序不能反。归还跑在**调用线程**上（Kotlin 侧 dispatcher 的线程，
    // 不是建窗口那条 STA），而 `pending_` 里的闭包归属宿主线程 ——
    // 先清掉它们，宿主线程才不会在下面那几个 `Reset` 之后还去跑残留
    // 闭包（那时 `webview_` / `ctrl_` 已经空了）。传 `nullptr` 让每个
    // 闭包在 `post` 的包装层直接返回，所以这一步只清队列、不碰 COM。
    flushPending(nullptr);
    // 摘除之后宿主线程遍历 `handlers_` 时不会再拿到本对象（遍历持
    // `mu_`，与本函数互斥），此时的 `Reset` 才没有并发对手。
    WebviewEnviorment::instance().unregisterHandler(this);
    ctrl_.Reset();
    webview_.Reset();
    webview2_.Reset();
    cookieManager_.Reset();
    if (hwnd_) PostMessage(hwnd_, WM_CLOSE, 0, 0);
  }
  void release(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(mu_);
    if (ctx_) {
      env->DeleteGlobalRef(ctx_);
      ctx_ = nullptr;
    }
  }
  bool post(std::function<void(WebviewContext*)> fn) {
    return post_([&, fn](ICoreWebView2Environment* env) {
      if (!env) return;
      fn(this);
    });
  }
  // WebviewContextHandler
  void flushPending(ICoreWebView2Environment* env) override {
    std::vector<std::function<void(ICoreWebView2Environment*)>> todo;
    {
      std::lock_guard<std::mutex> lock(mu_);
      todo.swap(pending_);
    }
    for (auto& fn : todo) fn(env);
  }
  jobject getCtx(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!ctx_) return nullptr;
    return env->NewLocalRef(ctx_);
  }
  // IUnknown
  ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
  ULONG STDMETHODCALLTYPE Release() override { return 1; }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
    // 同上：`S_OK` 必须伴随写出的接口指针。本对象一次充当六个回调接口，
    // 少答一个，WebView2 注册那类事件时就会拿到空指针再调用。
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (riid == IID_IUnknown) {
      *ppv = static_cast<
          ICoreWebView2CreateCoreWebView2ControllerCompletedHandler*>(this);
      return S_OK;
    }
    if (riid == IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler) {
      *ppv = static_cast<
          ICoreWebView2CreateCoreWebView2ControllerCompletedHandler*>(this);
      return S_OK;
    }
    if (riid == IID_ICoreWebView2WebResourceRequestedEventHandler) {
      *ppv = static_cast<ICoreWebView2WebResourceRequestedEventHandler*>(this);
      return S_OK;
    }
    if (riid == IID_ICoreWebView2NavigationCompletedEventHandler) {
      *ppv = static_cast<ICoreWebView2NavigationCompletedEventHandler*>(this);
      return S_OK;
    }
    if (riid == IID_ICoreWebView2NavigationStartingEventHandler) {
      *ppv = static_cast<ICoreWebView2NavigationStartingEventHandler*>(this);
      return S_OK;
    }
    if (riid == IID_ICoreWebView2DocumentTitleChangedEventHandler) {
      *ppv = static_cast<ICoreWebView2DocumentTitleChangedEventHandler*>(this);
      return S_OK;
    }
    if (riid == IID_ICoreWebView2ProcessFailedEventHandler) {
      *ppv = static_cast<ICoreWebView2ProcessFailedEventHandler*>(this);
      return S_OK;
    }
    return E_NOINTERFACE;
  }
  // ICoreWebView2CreateCoreWebView2ControllerCompletedHandler
  HRESULT STDMETHODCALLTYPE
  Invoke(HRESULT hr, ICoreWebView2Controller* controller) override {
    if (FAILED(hr)) {
      WV_LOG("ICoreWebView2Controller create failed (0x%08lX)", hr);
      SetEvent(init_);
      return hr;
    }
    WV_LOG("ICoreWebView2Controller created");
    ctrl_ = controller;
    EventRegistrationToken token;
    controller->get_CoreWebView2(&webview_);
    webview_->AddWebResourceRequestedFilter(
        L"*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
    webview_->add_WebResourceRequested(this, &token);
    webview_->add_NavigationCompleted(this, &token);
    webview_->add_NavigationStarting(this, &token);
    webview_->add_DocumentTitleChanged(this, &token);
    webview_->add_ProcessFailed(this, &token);

    webview_->QueryInterface(IID_ICoreWebView2_2, &webview2_);
    // 取出本上下文自己的 cookie manager 缓存下来，供后续 cookie
    // 读写复用（不必每次 get_CookieManager）。它属于 profile，随 controller
    // 就绪时一次取好即可。排在 SetEvent(init_) 之前 ⇒ initContext
    // 返回时它已就位，Kotlin 侧无需再等。
    if (webview2_) webview2_->get_CookieManager(&cookieManager_);

    // ---- 设置项：后台页不要界面 ----
    ComPtr<ICoreWebView2Settings> settings;
    if (SUCCEEDED(webview_->get_Settings(settings.GetAddressOf())) &&
        settings) {
      settings->put_IsScriptEnabled(TRUE);
      settings->put_AreDefaultScriptDialogsEnabled(FALSE);
      settings->put_IsStatusBarEnabled(FALSE);
      settings->put_IsZoomControlEnabled(FALSE);
      settings->put_IsBuiltInErrorPageEnabled(FALSE);
    }
    SetEvent(init_);

    return S_OK;
  }
  // ICoreWebView2WebResourceRequestedEventHandler
  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2WebResourceRequestedEventArgs* args) override {
    ComPtr<ICoreWebView2WebResourceRequest> request;
    if (FAILED(args->get_Request(&request)) || !request) return S_OK;
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
    bool isForMainFrame;
    if (ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT) {
      isForMainFrame = true;
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
    const bool hit = WebviewEnviorment::instance().callJava(
        [&, uri, method, isForMainFrame, flat](JNIEnv* env) {
          jobject ctx = getCtx(env);
          if (!ctx) return false;
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
          bool ret = env->CallBooleanMethod(ctx, onWebResourceRequested, jUri,
                                            jMethod, isForMainFrame, jHeaders);
          env->DeleteLocalRef(ctx);
          env->DeleteLocalRef(jUri);
          env->DeleteLocalRef(jMethod);
          env->DeleteLocalRef(jHeaders);
          return ret;
        });
    if (!hit) return S_OK;
    // 命中：给一个空的 204 并把整轮加载停掉。JVM 侧此刻已经拿到结果了，之后
    // nativeCancel 会来收摊。
    WV_LOG("Intercepted: %ls", uri.c_str());
    ComPtr<ICoreWebView2WebResourceResponse> response;
    if (auto* env = WebviewEnviorment::instance().env()) {
      if (SUCCEEDED(env->CreateWebResourceResponse(
              nullptr, 204, L"No Content", L"", response.GetAddressOf())) &&
          response) {
        args->put_Response(response.Get());
      }
    }
    if (webview_) webview_->Stop();
    return S_OK;
  }
  // ICoreWebView2NavigationCompletedEventHandler
  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2NavigationCompletedEventArgs* args) override {
    BOOL ok = FALSE;
    args->get_IsSuccess(&ok);
    if (ok) {
      // 成功才报这两个：失败的那轮没有新文档，历史栈也没动。
      const std::wstring source =
          takeString([&](LPWSTR* out) { return sender->get_Source(out); });
      callOnEvent(L"documentLoaded", source);
      BOOL back = FALSE;
      BOOL forward = FALSE;
      sender->get_CanGoBack(&back);
      sender->get_CanGoForward(&forward);
      // data 是 "canGoBack canGoForward"，两个都是 "1" / "0"。
      callOnEvent(L"historyChanged",
                  std::to_wstring(back) + L" " + std::to_wstring(forward));
    }
    callOnEvent(ok ? L"navigationCompleted" : L"navigationFailed", L"");
    return S_OK;
  }
  // ICoreWebView2NavigationStartingEventHandler
  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2NavigationStartingEventArgs* args) override {
    // 地址在**开始**导航时就有了（完成时再取的话，失败那轮拿不到）。
    const std::wstring uri =
        takeString([&](LPWSTR* out) { return args->get_Uri(out); });
    callOnEvent(L"urlChanged", uri);
    callOnEvent(L"navigationStarting", L"");
    return S_OK;
  }
  // ICoreWebView2DocumentTitleChangedEventHandler
  HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2* sender,
                                   IUnknown* args) override {
    const std::wstring title =
        takeString([&](LPWSTR* out) { return sender->get_DocumentTitle(out); });
    callOnEvent(L"documentTitleChanged", title);
    return S_OK;
  }
  // ICoreWebView2ProcessFailedEventHandler
  HRESULT STDMETHODCALLTYPE
  Invoke(ICoreWebView2* sender,
         ICoreWebView2ProcessFailedEventArgs* args) override {
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
    callOnEvent(L"processFailed", std::to_wstring(kind) + L" reason=" +
                                      std::to_wstring(reason) + L" exit=" +
                                      std::to_wstring(exitCode));
    return S_OK;
  }
  // ui
  void focus() {
    if (hwnd_) SetFocus(hwnd_);
  }
  void attachThreadInput(HWND parent) {
    const DWORD selfTid = GetCurrentThreadId();
    const HWND root = GetAncestor(parent, GA_ROOT);
    const DWORD rootTid = root ? GetWindowThreadProcessId(root, nullptr) : 0;
    if (!rootTid || rootTid == selfTid) return;  // 同线程不用接
    if (AttachThreadInput(selfTid, rootTid, TRUE)) {
      WV_LOG("输入队列已接到 AWT 线程 (%lu -> %lu)", selfTid, rootTid);
    } else {
      WV_LOG("AttachThreadInput 失败 (%lu -> %lu) err=%lu", selfTid, rootTid,
             GetLastError());
    }
  }
  void attach(HWND parent) {
    if (!hwnd_ || !parent) return;
    if (parent_ == parent) return;
    parent_ = parent;
    // 顺序不能反：先接父链再改样式 —— 反过来窗口会在父链还没接上时就已经是
    // `WS_CHILD`，然后直接消失。
    SetParent(hwnd_, parent);
    LONG_PTR style = GetWindowLongPtrW(hwnd_, GWL_STYLE);
    style &= ~static_cast<LONG_PTR>(WS_POPUP | WS_CAPTION | WS_THICKFRAME);
    style |= WS_CHILD | WS_CLIPCHILDREN | WS_CLIPSIBLINGS;
    SetWindowLongPtrW(hwnd_, GWL_STYLE, style);
    // 只改样式不够 —— 让系统按新样式重算一次非客户区 / 裁剪。尺寸交给下面的
    // fitToParent。
    SetWindowPos(hwnd_, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE |
                     SWP_FRAMECHANGED);
    attachThreadInput(parent);
    ShowWindow(hwnd_, SW_SHOWNA);
    WV_LOG("attach to AWT: %p", static_cast<void*>(parent));
    fitToParent();
  }
  void detach() {
    if (!hwnd_ || !parent_) return;
    // detachThreadInput();
    if (ctrl_) ctrl_->put_IsVisible(FALSE);
    ShowWindow(hwnd_, SW_HIDE);
    SetParent(hwnd_, nullptr);
    LONG_PTR style = GetWindowLongPtrW(hwnd_, GWL_STYLE);
    style &= ~static_cast<LONG_PTR>(WS_CHILD | WS_CAPTION | WS_THICKFRAME);
    style |= WS_POPUP | WS_CLIPCHILDREN | WS_CLIPSIBLINGS;
    SetWindowLongPtrW(hwnd_, GWL_STYLE, style);
    SetWindowPos(hwnd_, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE |
                     SWP_FRAMECHANGED);
    WV_LOG("detach from AWT: %p", static_cast<void*>(parent_));
    parent_ = nullptr;
  }
  void fitToParent() {
    if (!hwnd_ || !parent_) return;
    RECT rc{};
    if (!GetClientRect(parent_, &rc)) return;
    const int width = rc.right > 0 ? rc.right : 1;
    const int height = rc.bottom > 0 ? rc.bottom : 1;
    // 子窗口坐标相对父窗口客户区，所以位置恒为 (0, 0)。
    SetWindowPos(hwnd_, nullptr, 0, 0, width, height,
                 SWP_NOZORDER | SWP_NOACTIVATE);
    if (ctrl_) {
      ctrl_->put_Bounds(RECT{0, 0, width, height});
      ctrl_->put_IsVisible(TRUE);
    }
  }
  void setBounds(int width, int height) {
    if (!ctrl_) return;
    ctrl_->put_Bounds(
        RECT{0, 0, width > 0 ? width : 1, height > 0 ? height : 1});
  }
};

static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
  // WV_LOG("wndProc: 0x%08lX", msg);
  switch (msg) {
    case WM_CLOSE:
      DestroyWindow(hwnd);
      return 0;
  }
  return DefWindowProcW(hwnd, msg, wp, lp);
}

class ScriptHandler final
    : public ComCallback<ICoreWebView2ExecuteScriptCompletedHandler> {
  jobject token_;
  WebviewContext* ctx_;
  jmethodID mid_;

 public:
  ScriptHandler(jobject token, jmethodID mid)
      : ComCallback(IID_ICoreWebView2ExecuteScriptCompletedHandler),
        token_(token),
        mid_(mid) {}
  HRESULT STDMETHODCALLTYPE Invoke(HRESULT errorCode,
                                   LPCWSTR resultJson) override {
    const jboolean ok = SUCCEEDED(errorCode);
    const std::wstring result = resultJson ? resultJson : L"";
    WebviewEnviorment::instance().callJava([&, ok, result](JNIEnv* env) {
      jstring jResultJson = fromWide(env, result);
      env->CallVoidMethod(token_, mid_, ok, jResultJson);
      env->DeleteLocalRef(jResultJson);
      // 回调只来一次：全局引用在这里还回去。`executeScript` 那边只在 post 失败
      // 时自己删，成功这一支的责任在这里。（上下文先被关掉、回调没跑到的情形
      // 会漏一个 —— 那时 JVM 也在退了，不值得为它加一条跨线程的清理链。）
      env->DeleteGlobalRef(token_);
      return true;
    });
    return S_OK;
  }
};

/**
 * `GetCookies` 的结果收件人。
 *
 * 摊平成 `"n1=v1; n2=v2"` 交给回调的 `onResult(String)` —— 与 Android
 * `CookieManager.getCookie` 同一口径，JVM 侧不必再拼。取不到（失败或没有
 * cookie）时给空串。
 */
class GetCookiesHandler final
    : public ComCallback<ICoreWebView2GetCookiesCompletedHandler> {
  jobject token_;
  jmethodID mid_;

 public:
  GetCookiesHandler(jobject token, jmethodID mid)
      : ComCallback(IID_ICoreWebView2GetCookiesCompletedHandler),
        token_(token),
        mid_(mid) {}
  HRESULT STDMETHODCALLTYPE Invoke(HRESULT errorCode,
                                   ICoreWebView2CookieList* list) override {
    std::wstring cookies;
    if (SUCCEEDED(errorCode) && list) {
      UINT32 count = 0;
      if (SUCCEEDED(list->get_Count(&count))) {
        for (UINT32 i = 0; i < count; ++i) {
          ComPtr<ICoreWebView2Cookie> cookie;
          if (FAILED(list->GetValueAtIndex(i, cookie.GetAddressOf())) ||
              !cookie)
            continue;
          const std::wstring name =
              takeString([&](LPWSTR* out) { return cookie->get_Name(out); });
          if (name.empty()) continue;
          const std::wstring value =
              takeString([&](LPWSTR* out) { return cookie->get_Value(out); });
          if (!cookies.empty()) cookies += L"; ";
          cookies += name;
          cookies += L"=";
          cookies += value;
        }
      }
    }
    WV_LOG("GetCookiesHandler: %ls", cookies.c_str());
    WebviewEnviorment::instance().callJava([&, cookies](JNIEnv* env) {
      jstring jCookies = fromWide(env, cookies);
      env->CallVoidMethod(token_, mid_, jCookies);
      env->DeleteLocalRef(jCookies);
      // 回调只来一次：全局引用在这里还回去（同 ScriptHandler）。
      env->DeleteGlobalRef(token_);
      return true;
    });
    return S_OK;
  }
};

extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_web_Webview2Context_initContext(JNIEnv* env, jclass,
                                                 jobject ctx) {
  HANDLE init = CreateEventW(nullptr, FALSE, FALSE, nullptr);
  WebviewContext* ptr = new WebviewContext(env, ctx, init);
  WaitForSingleObject(init, 10000);
  if (ptr->webview()) {
    return reinterpret_cast<jlong>(ptr);
  } else {
    ptr->release(env);
    delete ptr;
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_releaseContext(JNIEnv* env, jclass,
                                                    jlong pctx) {
  if (pctx != 0) {
    auto ptr = reinterpret_cast<WebviewContext*>(pctx);
    ptr->release(env);
    delete ptr;
  }
}

extern "C" JNIEXPORT void JNICALL Java_soko_ekibun_web_Webview2Context_navigate(
    JNIEnv* env, jclass, jlong pctx, jstring url, jstring method,
    jobjectArray headers) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
    std::wstring url_ = toWide(env, url);
    std::wstring method_ = toWide(env, method);
    const std::vector<std::wstring> headers_ = readHeaderArray(env, headers);
    ctx->post([url_, method_, headers_](WebviewContext* ctx) {
      WV_LOG("Navigate to %ls", url_.c_str());
      auto* webview = ctx->webview();
      auto* webview2 = ctx->webview2();
      auto* env2 = WebviewEnviorment::instance().env2();
      if (env2 && webview2) {
        ComPtr<ICoreWebView2WebResourceRequest> request;
        const std::wstring block = buildHeaderBlock(headers_);
        if (SUCCEEDED(env2->CreateWebResourceRequest(
                url_.c_str(), method_.c_str(), nullptr, block.c_str(),
                request.GetAddressOf())) &&
            request &&
            SUCCEEDED(
                webview2->NavigateWithWebResourceRequest(request.Get()))) {
          WV_LOG("NavigateWithWebResourceRequest succeed");
          return;
        }
      }
      if (webview) webview->Navigate(url_.c_str());
    });
  }
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_executeScript(JNIEnv* env, jclass,
                                                   jlong pctx, jstring script,
                                                   jobject callback) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);

    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onResult", "(ZLjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    std::wstring script_ = toWide(env, script);
    jobject token = env->NewGlobalRef(callback);
    if (!ctx->post([script_, mid, token](WebviewContext* ctx) {
          auto webview = ctx->webview();
          if (!webview) return;
          auto* handler = new ScriptHandler(token, mid);
          webview->ExecuteScript(script_.c_str(), handler);
          handler->Release();
        })) {
      env->DeleteGlobalRef(token);
    };
  }
}

/**
 * 读一个 url 下的 cookie，用**调用方传入的那个上下文自己的 manager**。
 *
 * manager 在 controller 就绪时（controller-completed 回调里、`SetEvent(init_)`
 * 之前） 一次取好缓存在 `WebviewContext::cookieManager_` 上，随取随用；它属于
 * profile 而非 单个 CoreWebView2，所以同一 environment 下各上下文看到的是同一套
 * cookie 库。摊平成
 * `"n1=v1; n2=v2"` 经 callback 的 `onResult(String)` 回来；没有 cookie
 * 也给空串， 回调只来一次。任务投到宿主线程上跑（COM 调用要留在建 manager
 * 的那条 STA 线程上）。
 */
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_getCookie(JNIEnv* env, jclass, jlong pctx,
                                               jstring url, jobject callback) {
  if (pctx == 0) return;
  WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
  jclass cls = env->GetObjectClass(callback);
  jmethodID mid = env->GetMethodID(cls, "onResult", "(Ljava/lang/String;)V");
  env->DeleteLocalRef(cls);
  const std::wstring url_ = toWide(env, url);
  jobject token = env->NewGlobalRef(callback);
  auto* handler = new GetCookiesHandler(token, mid);
  ctx->post([url_, handler](WebviewContext* ctx) {
    auto* manager = ctx->cookieManager();
    if (manager) {
      WV_LOG("GetCookies: %ls", url_.c_str());
      manager->GetCookies(url_.c_str(), handler);
    } else {
      // 没有 manager（环境没起来 / 已释放）也给个空结果 —— token 的全局引用由
      // handler 归还，少这一回调就漏一个、JVM 侧只会干等超时。
      handler->Invoke(E_FAIL, nullptr);
    }
    handler->Release();
  });
}

/**
 * 写 cookie（结构化参数）到**调用方传入的那个上下文自己的 manager**。
 * 域 / 路径缺省由 Kotlin 端按 url 推好再传进来。`expiresEpochSeconds > 0`
 * 表示带过期 时间（自 Unix 纪元起的秒），否则会话 cookie。`sameSite`：0=NONE,
 * 1=LAX, 2=STRICT。 空 name 在投递前就拦掉（fail
 * fast，不占宿主线程）。任务投到宿主线程上跑。
 */
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_setCookie(
    JNIEnv* env, jclass, jlong pctx, jstring name, jstring value,
    jstring domain, jstring path, jdouble expiresEpochSeconds,
    jboolean isSecure, jboolean isHttpOnly, jint sameSite) {
  if (pctx == 0) return;
  WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
  const std::wstring name_ = toWide(env, name);
  if (name_.empty()) {
    WV_LOG("setCookie: name must not empty");
    return;
  }
  const std::wstring value_ = toWide(env, value);
  const std::wstring domain_ = toWide(env, domain);
  const std::wstring path_ = toWide(env, path);
  const double expires = expiresEpochSeconds;
  const bool secure = isSecure != JNI_FALSE;
  const bool httpOnly = isHttpOnly != JNI_FALSE;
  COREWEBVIEW2_COOKIE_SAME_SITE_KIND ss =
      COREWEBVIEW2_COOKIE_SAME_SITE_KIND_NONE;
  switch (sameSite) {
    case 1:
      ss = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_LAX;
      break;
    case 2:
      ss = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_STRICT;
      break;
    default:
      ss = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_NONE;
      break;
  }
  ctx->post([name_, value_, domain_, path_, expires, secure, httpOnly,
             ss](WebviewContext* ctx) {
    auto* manager = ctx->cookieManager();
    if (!manager) {
      WV_LOG("setCookie: no cookie manager");
      return;
    }
    ComPtr<ICoreWebView2Cookie> c;
    if (FAILED(manager->CreateCookie(name_.c_str(), value_.c_str(),
                                     domain_.c_str(), path_.c_str(), &c)) ||
        !c) {
      WV_LOG("CreateCookie failed for %ls", name_.c_str());
      return;
    }
    if (expires > 0) c->put_Expires(expires);
    if (secure) c->put_IsSecure(TRUE);
    if (httpOnly) c->put_IsHttpOnly(TRUE);
    c->put_SameSite(ss);
    WV_LOG("Set cookie %ls on %ls%ls", name_.c_str(), domain_.c_str(),
           path_.c_str());
    manager->AddOrUpdateCookie(c.Get());
  });
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_setUserAgent(JNIEnv* env, jclass,
                                                  jlong pctx, jstring ua) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
    std::wstring ua_ = toWide(env, ua);
    ctx->post([ua_](WebviewContext* ctx) {
      auto webview = ctx->webview();
      if (!webview) return;
      ComPtr<ICoreWebView2Settings2> settings2;
      if (SUCCEEDED(webview->QueryInterface(
              IID_ICoreWebView2Settings2,
              reinterpret_cast<void**>(settings2.GetAddressOf()))) &&
          settings2) {
        settings2->put_UserAgent(ua_.c_str());
      }
    });
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_web_Webview2Context_attach(JNIEnv* env, jclass, jlong pctx,
                                            jobject view) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
    // 取 HWND 这一步留在调用线程上做（组件 peer 归属 AWT 的 EDT），之后动窗口的
    // 事全投到 WebView 线程。
    const HWND parent = awtHwndOf(env, view);
    if (parent == nullptr) {
      WV_LOG("awtHwndOf failed");
      return false;
    }
    return ctx->post([parent](WebviewContext* ctx) { ctx->attach(parent); });
  }
  return false;
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_detach(JNIEnv* env, jclass, jlong pctx) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
    ctx->post([](WebviewContext* ctx) { ctx->detach(); });
  }
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_focus(JNIEnv*, jclass, jlong pctx) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
    ctx->post([](WebviewContext* ctx) { ctx->focus(); });
  }
}

// 尺寸：组件大小变了重新贴合；没挂到组件上的用法给一个显式视口。
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_fitToParent(JNIEnv*, jclass, jlong pctx) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
    ctx->post([](WebviewContext* ctx) { ctx->fitToParent(); });
  }
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_web_Webview2Context_setBounds(JNIEnv*, jclass, jlong pctx,
                                               jint width, jint height) {
  if (pctx != 0) {
    WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);
    ctx->post([width, height](WebviewContext* ctx) {
      ctx->setBounds(static_cast<int>(width), static_cast<int>(height));
    });
  }
}

#define JNI_WEBVIEW_METHOD(method_name, body)                         \
  extern "C" JNIEXPORT void JNICALL                                   \
  Java_soko_ekibun_web_Webview2Context_##method_name(JNIEnv*, jclass, \
                                                     jlong pctx) {    \
    if (pctx != 0) {                                                  \
      WebviewContext* ctx = reinterpret_cast<WebviewContext*>(pctx);  \
      ctx->post([](WebviewContext* ctx) {                             \
        auto webview = ctx->webview();                                \
        if (!webview) return;                                         \
        body;                                                         \
      });                                                             \
    }                                                                 \
  }
JNI_WEBVIEW_METHOD(reload, webview->Reload())
JNI_WEBVIEW_METHOD(stop, webview->Stop())
JNI_WEBVIEW_METHOD(goBack, webview->GoBack())
JNI_WEBVIEW_METHOD(goForward, webview->GoForward())
JNI_WEBVIEW_METHOD(openDevTools, webview->OpenDevToolsWindow())
#undef JNI_WEBVIEW_METHOD
