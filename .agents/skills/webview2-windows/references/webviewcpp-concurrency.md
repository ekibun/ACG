# webview.cpp 并发、锁与生命周期分析

> 配套 `cxx/webview/README.md` 的架构与架构图，本文是**逐条静态走读**的结论，锁定并发陷阱。
> 纯分析，不改代码。行号对应当前 `webview.cpp`。
>
> 两个线程：**JNI 线程**（Kotlin dispatcher 固定单线程，所有 `Java_soko_ekibun_web_Webview2Context_*` 入口）
> 与 **STA 线程**（`WebviewEnviorment::threadMain`，唯一一条 WebView2 / COM 单线程套间线程）。
> 两把锁：`WebviewEnviorment::mu_`（**mu_E**）与 `WebviewContext::mu_`（**mu_C**）。

## 1. 锁序：E→C 单向，无死锁（设计/巧合地正确）

所有跨锁获取都是 **mu_E 在前、mu_C 在后**：

- `WebviewEnviorment::flushPending`（持 mu_E）→ 调 `WebviewContext::flushPending`（取 mu_C）。
- `callJava`（持 mu_E）→ lambda 内 `getCtx`（取 mu_C）。

反向边（mu_C → mu_E）**不存在**：`post_()` 持 mu_C 时调用的 `notify()` **刻意不取 mu_E**，只做无锁读。正因为没有 C→E 边，锁图是无环的，不会出现 A 持 E 等 C、B 持 C 等 E 的死锁。参数校验：没有一条路径在持 mu_C 期间去取 mu_E。

## 2. A：纸面 UB，不修（已核实 benign）

`notify()`（行 320-325）读 `isClosed` 与 `threadId_`，不加 mu_E；写者（`releaseEnvironment` 行 278、`threadMain` 行 407）都持 mu_E —— 确属 data race。

但功能后果为零：即便某次 `notify()` 在 `threadId_` 写入前读到陈旧的 `0`，最多只是少了一次 `PostThreadMessageW(WV_WM_TASK)`；`pending_` 里的任务本身还在，STA 线程在处理 `WV_WM_TASK`（行 424-429）或 env ready 时的 `flushPending`（行 395）时会**重新 `flushPending` 排空**，幂等补发。所以不会真丢唤醒，实践中被 `registerHandler`（行 296/299 持 mu_E 的 `PostThreadMessageW`）进一步掩盖。

结论：纯 C++ 内存模型的纸面 UB，无功能影响，按「最简实现」不修，只在此记一笔。

## 3. B：单线程 benign，不修（已核实非跨线程 race）

`jenv_` 的写者：`threadMain` 行 415 `AttachCurrentThread`、`exit_thread` 行 443-445 `DetachCurrentThread` + `jenv_=nullptr`；读者：`callJava` 行 311（在 `lock_guard(mu_E)` 内读 `jenv_`）。

核实所有 `callJava` 调用点：

- `callOnEvent`（行 495）、`WebResourceRequested`（行 708）、`ScriptHandler`（行 911）、`GetCookiesHandler`（行 966）—— 全部位于 STA 线程的 `Invoke` 回调里；
- JNI 入口 `executeScript` / `getCookie` / `setCookie` 等只是把任务 `post` 到 STA，由 STA 上的 `Invoke` 再去 `callJava`。

没有任何 JNI 线程直接调 `callJava`。因此 `jenv_` 的写者与读者**全在同一条 STA 线程上**，是单线程内的顺序访问，不是跨线程 race，benign。原文档把它列为缺陷是误判，按「最简实现」不修。

## 4. 缺陷 C（已修复）：对象生命周期由 STA 线程接管

**旧风险**：原先 `releaseContext`（JNI 线程）在 `release(env)` 后直接 `delete ptr`，而 STA 线程上所有 `Invoke` 回调（WebResourceRequested / Navigation* / Script / GetCookies）及其 `callJava` lambda 都捕获 `this` 并读 `this->ctx_`、`this->webview_`。`getCtx` 的 `ctx_==null` 守卫只在 mu_C 临界区内生效；守卫过后 lambda 仍用 `this`（如 WebResourceRequested 在 `callJava` 返回后还 `if (webview_) webview_->Stop()`，行 742），此时 mu_C 已释放。析构 `~WebviewContext` 里的 `flushPending(nullptr)` 只排干 `pending_` 待执行任务，不等待 STA 在飞的 `Invoke`/`callJava`。于是 `releaseContext` 与在飞 `Invoke` 并发时 `delete` 后 STA 会读已释放的 `this` → use-after-free（典型「不报错、只失效/偶发崩」）。

**修复（已在代码中落地）**：销毁权移交给 STA 线程。

- `release()`（行 543-559）：持 mu_C 做幂等释放（`ctx_==null` 直接 no-op、`DeleteGlobalRef`、`ctx_=null`），**锁外** `flushPending(nullptr)` 排空 pending_ 并 `unregisterHandler(this)`，再 `PostMessage(hwnd_, WM_CLOSE, 0, (LPARAM)this)`。
- `wndProc` 的 `WM_CLOSE`（行 888-891）：`DestroyWindow(hwnd)` 后 `delete reinterpret_cast<WebviewContext*>(lp)`。
- 析构 `~WebviewContext`（行 537-542）只剩 `ctrl_/webview_/webview2_/cookieManager_` 的 `Reset()`，不碰队列、不投递。
- JNI 入口 `initContext` / `releaseContext` 不再 `delete ptr`，只调 `release(env)`。

因为 `WM_CLOSE` 由 STA 消息泵按 FIFO 处理，必然排在**当前已在飞的 `Invoke` 回调结束之后**，`delete this` 不会再与在飞回调并发 —— use-after-free 消除。`PostMessage` 失败时（无窗口 / STA 线程已死，投递不了）回退为 `release()` 内 `delete this`：该路径下不可能有在飞 `Invoke` 引用本对象，同样安全。

> 补充：`releaseEnvironment` 仍会 `join(STA)`（行 283）。只要 Kotlin 侧保持「先 `releaseContext` 所有上下文、再 `releaseEnvironment`」的调用约定，STA 退出后无在飞回调；而即便顺序不乱，C 现在也由「寿命归 STA」从代码层面保证了。

## 5. 其他值得记一笔的点

- `WebviewContext::flushPending`（行 567-574）先 `swap(pending_)` 再释放 mu_C、最后跑 functor——**不在持锁期间执行 COM 任务**，正确；也因此不会在 env 侧 flushPending 持 mu_E 时长时间占 mu_C。注意 `flushPending(nullptr)` 现位于 `release()`（行 552）而非析构；真正的 `delete this` 交给 STA 的 `WM_CLOSE`（行 888-891）。
- 没有「在持 mu_E 期间通过 post_ 回调再调 callJava」的路径（post_ 的任务都不直接 callJava，ScriptHandler/GetCookiesHandler 的 `Invoke` 是后续独立回调，不在 env flush 持 mu_E 的窗口内），所以**没有 mu_E 重入死锁**（`std::mutex` 非递归）。
- `init` 事件在所有失败分支都会被 `SetEvent`（`env==null` 任务行 531、`ctrl Invoke` 失败行 625、成功行 657），`WaitForSingleObject(init,10000)` 不会永久挂。

## 结论

- **A**：纸面 UB，无功能后果，不修。
- **B**：单线程顺序访问，非跨线程 race，benign，不修。
- **C**：已由代码改动修复 —— 对象生命周期改为由 STA 线程经 `WM_CLOSE` 接管 `delete this`，消除跨线程 use-after-free。
