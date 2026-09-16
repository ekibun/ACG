# WebView2（Windows）排查手册

本工程桌面端的可见页与后台页都跑在**自研 C++ 宿主**（`cxx/webview/webview.cpp`）上，
共享同一个 `ICoreWebView2Environment` → 同一份 user data folder → 同一套 cookie。

WebView2 通过 API 几乎不报告任何有用信息，下面每个症状都长得像"我们的代码错了"，
实际多半是引擎或浏览器参数的问题。**按顺序排查**，从最便宜、最能定性的开始。

> 相关入口：`cxx/AGENTS.md`（构建与 JNI）、`docs/native/quickjs-reference-ownership.md`。

---

## 1. 先拿真错误，再推理

`ICoreWebView2ProcessFailedEventHandler` 是唯一的信号。用
`ICoreWebView2ProcessFailedEventArgs2` 把 **kind / reason / exitCode 三个一起**读出来。

| 字段 | 关键取值的含义 |
|---|---|
| `kind` | 0 `BROWSER_PROCESS_EXITED`（**致命**）、1 `RENDER_PROCESS_EXITED`（**致命**）、2 `RENDER_UNRESPONSIVE`、3 `FRAME_RENDER_PROCESS_EXITED`（**致命**）、4 `UTILITY`、5 `SANDBOX_HELPER`、6 `GPU` |
| `reason` | 0 `UNEXPECTED`、1 `UNRESPONSIVE`、2 `TERMINATED`、3 `CRASHED`、4 `LAUNCH_FAILED` |
| `exitCode` | 进程退出码。`0x80000003` / `-2147483645` = `STATUS_BREAKPOINT`，即该进程内部发生了 Chromium 的 `CHECK` / `abort()` |

**只有 kind 0 / 1 / 3 算致命。** 把 4 / 5 / 6 当失败上报本身就是 bug：
GPU 与 utility 进程本来就会反复重启（`reason=2 TERMINATED`），
把它们当失败会把一次进展正常的导航直接拆掉。

另外：用 `nullptr` options 创建的 `ICoreWebView2Environment`，返回的错误串里只有一个 HRESULT。
**必须自己把 HRESULT 格式化** —— 名字（`E_ACCESSDENIED`、`HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)`…）
+ `FormatMessageW` 文本 + user data folder 路径 + `GetAvailableCoreWebView2BrowserVersionString`，
再附一行提示。"environment failed, hr=0x80070005" 无法行动；
"E_ACCESSDENIED, user data folder not writable" 可以。

---

## 2. 读 Chromium 自己的日志 —— 它在 user data folder 里

引擎把自己的日志写在 `<userDataFolder>\EBWebView\chrome_debug.log`，只有开了日志才有。
打开方式是在**创建 environment 之前**设进程环境变量：

```
WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--enable-logging --v=1
```

那个文件才会告诉你进程为什么死 —— 托管侧永远不会。

最该 grep 的一行：

```
FATAL:<file>:<line>] GPU process isn't usable. Goodbye.
```

这是**浏览器进程**里的 `CHECK` 失败：整个浏览器进程随后以 `STATUS_BREAKPOINT` 退出，所有视图一起死。
宿主侧看到的现象：第一个 `about:blank` 正常（不需要合成器），之后**什么都打不开，连
`NavigationStarting` 都不触发**。

---

## 3. 哪些开关真有用

- **`--disable-gpu-process-crash-limit` —— 唯一真管用的那个。**
  看到 GPU 进程崩溃循环、随后 `GPU process isn't usable` 时用它。GPU 依旧是坏的，
  但浏览器进程不再被它带走。实测端到端用例从 0/3 → 2/3 → 3/3。
- **`--disable-gpu` 没用。** 它只是关掉硬件加速；GPU **进程**照旧启动、照旧崩、照旧带走浏览器进程。
  同样无效的还有 `--disable-software-rasterizer`、`--use-angle=swiftshader`、
  `--disable-features=SkiaGraphite`、`--disable-gpu-shader-disk-cache`、`--disable-gpu-compositing`。
  这个失败模式下别在它们身上浪费时间。
- `--no-sandbox` / `--single-process` 治的是**别的**失败态（沙箱起不来、渲染进程拉不起来），
  救不了浏览器进程的 `CHECK` 失败。
  > ⚠️ 关掉 Chromium 沙箱是**安全降级**，不要默认写进代码，靠环境变量按需打开。
  > 正解应去查 Windows「漏洞利用防护 / Exploit Protection」的 Mandatory ASLR 或安全软件。
  > 诊断前先把环境变量清干净，否则会把环境态误判成代码回归。

### 怎么设这些开关：优先走 native 默认值，而不是构建开关

`ICoreWebView2EnvironmentOptions::AdditionalBrowserArguments` 需要在 WRL 缺 `RuntimeClass`
的工具链（如 MinGW）上手写一个 COM 类。等价且便宜的做法：在
`CreateCoreWebView2EnvironmentWithOptions` **之前**调
`SetEnvironmentVariableW("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", ...)`。
loader 会读它，浏览器子进程会继承这一块环境变量。
**只追加、不覆盖** —— 这样运维侧仍然可以从外面加参数做 A/B。

测试期想换参数又不改代码：在 Gradle 里用
`providers.environmentVariable("ACG_WEBVIEW2_ARGS")` 追加到 `Test` 任务的
`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` 上（见 `shared/build.gradle.kts`）。
注意这个变量**只对 jvmTest 生效**，`:desktopApp:run` 不吃它。

---

## 4. 别用独立探针下"开关有没有用"的结论

一个极小的独立探针（不带 JNI / Gradle / UI 框架）只擅长一件事：
把"这台机器上的引擎坏了"和"我们的宿主坏了"分开。它**不是**判断浏览器参数的有效载体，
因为它会为自己的原因失败 —— 实测过：它连 `about:blank` 都到不了 `NavigationCompleted`，
于是所有经它测的参数看起来都"没用"。

**任何参数结论都必须在真宿主里复测**，那里才有已知可用的基线（例如 `about:blank` 能成功）。

推论：始终保留一个健康检查用例，导航一个极简页面并断言脚本结果 ——
这样"引擎死了"会被报成一次 skip，而不是 N 个超时。

---

## 5. 关闭顺序：控制器要在消息泵还活着时关

两个独立的坑，都会产生杀不掉的进程或退出码 3（`abort()`）：

1. **顺序**：`ICoreWebView2Controller::Close()` 是异步完成的，完成回调需要消息循环继续转。
   先停泵、后关闭，会让拆除、`CoUninitialize()` 和 DLL 静态析构全部卡住。
   正确顺序：**往 UI 线程投一个任务，关掉所有控制器，等它，然后才退出消息泵。**
2. **有界等待**：坏掉的引擎能让 `Close()` 阻塞几十秒。JVM shutdown hook 里一个无界的
   `std::thread::join()` 会把进程退出挂死。要带超时等；超时就 `detach()`（并把 dispatcher 置空，
   让后续投递快速失败），不要永久 join —— 一个可 join 的静态 `std::thread` 析构会调 `std::terminate`。

退出消息泵时，窗口消息不够用（`WM_CLOSE` 需要窗口还活着）。
**兜底办法是投线程消息**：`PostThreadMessageW(threadId, WM_QUIT, 0, 0)`，不依赖窗口存活。
线程 id 在启动时记下来。

另外把 dispatcher 的 HWND 设成 `std::atomic` —— UI 线程退出时会清它，其他线程会读它来投递任务。

---

## 6. Cookie：session 与 persistent 就是全部

视图 A 写了一个 cookie，视图 B（同一个 environment、同一份 user data folder）读不到时，
先看这个 cookie 的生存期，别急着怀疑存储接线：

- `document.cookie = 'x=1; path=/'` 是 **session cookie** —— 它活在浏览器进程内存里，
  **不会**写进 `Default/Network/Cookies`。
- 两个视图之间**没有任何 WebView 存活**时，这份内存状态被丢掉，所以第二个视图什么都看不到。
- 加上 `max-age=3600` 立刻可用，条目也会出现在 SQLite 的 `Cookies` 库里。

所以**存储本身是共享的**，有缺口的只是内存里的 session 状态。
要在没有任何存活视图的情况下保住 session cookie，就留一个 keep-alive 的 WebView 开着。

---

## 7. user data folder

**不要退到 `java.io.tmpdir` / `%TEMP%`** —— 在 MSYS2 / Gradle 下那可能是个一次性路径
（MSYS2 自己的临时目录），于是每次跑都是全新的 cookie 罐，"多个视图共享登录"根本没法验证。

按 `LOCALAPPDATA` → `user.home/AppData/Local` → `user.home` 逐级找，并 `mkdirs()`。
所有视图必须用**同一个**目录 —— 共享 cookie 全靠它。

---

## 8. 把 WebView 嵌进 AWT / Compose Desktop（JAWT）

把它的 HWND 重挂到一个 AWT 组件上，用 **JAWT** 取组件 HWND：

- **不要链 `jawt.lib`。** MinGW 等工具链处理 MSVC 导入库很痛苦，而 JVM 一起来
  `jawt.dll` 就已经映射进进程了。运行时解析即可：
  `GetModuleHandleW(L"jawt.dll")` → 退到 `LoadLibraryW` → `GetProcAddress(..., "JAWT_GetAWT")`。
  编译时把 `$JAVA_HOME/include` 和 `$JAVA_HOME/include/win32` 加进 include 路径。
- 取句柄：`JAWT_VERSION_1_4` → `GetDrawingSurface` → `Lock` → `GetDrawingSurfaceInfo`
  → `JAWT_Win32DrawingSurfaceInfo::hwnd`。**只取句柄**，不要碰绘图状态。
  `FreeDrawingSurfaceInfo` / `Unlock` / `FreeDrawingSurface` 必须成对，否则泄漏。

**必须选一个真的用 AWT 的窗口后端。** 换掉自己非 AWT 后端的框架（故意不加载 AWT 的自绘后端）
给不出 HWND —— JAWT 取不到东西，整条路走不通。用某个库之前先找它有没有
`not compatible with AWT` 之类的守卫。本工程因此用的是标准 compose.desktop 的
`application { Window(...) }`。

**附着顺序不可商量：**

1. 先把宿主窗口建成**隐藏的顶层** popup（不带 `WS_VISIBLE`）。这时不能建成 `WS_CHILD` ——
   创建时 AWT 组件还没有 peer，没有父 HWND 可传。
2. 等 AWT 组件 `isDisplayable` 之后，解析它的 HWND 并 `SetParent`。
3. **然后**再改样式：清掉 `WS_POPUP|WS_CAPTION|WS_THICKFRAME`，加上
   `WS_CHILD|WS_CLIPCHILDREN|WS_CLIPSIBLINGS`。
4. `SetWindowPos(..., SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED)`
   —— 光调 `SetWindowLongPtr` **不会**让新样式生效。
5. 最后才 `ShowWindow`，然后设尺寸。

把第 3 步做到第 2 步之前，窗口会消失（还没有父子链）。
也别对尚未附着的可见视图 `ShowWindow` —— 会在屏幕上闪一个 1×1 的 popup。

**尺寸一律以父窗口的 client rect 为准，且在 native 侧算。**
`GetClientRect(parent)` 是唯一真值，推进控制器用 `put_Bounds`。
**不要同时**从托管侧再传一次宽高 —— DPI 缩放下两边取整方式不同，只会互相打架。
`GetClientRect` 读别的线程的窗口是安全的（不发消息）。

**要预期的合成行为**：AWT 组件是重型组件，**永远画在 Compose 内容之上**。
所以"盖在 web view 之上的 Compose 覆盖层"会被盖住，只在原身后端起不来时才可见。
这一点如实写进文档，不要假装能做到（真要做得看第 11 节）。

---

## 9. 焦点：渲染正常、点击有反应、但打字没反应

这是任何自研 / 子窗口宿主最常见的症状，根因永远是同一个：
**焦点落到宿主 HWND 上不会自动传进 WebView2，而 WebView2 从不自己抢焦点。**
鼠标消息是按位置投递的、不需要焦点 —— 这正是"点得动但键盘是死的"的原因。

### 先 dump 窗口树，因为它决定了哪些办法根本不可能

从宿主出发遍历子窗口，逐层打印 `GetWindowThreadProcessId` 和 `GetWindowLongPtr(GWLP_WNDPROC)`。
WebView2 给的是**跨进程的两层**嵌套：

```
host                            （你的进程 / 你的线程）
└ Chrome_WidgetWin_0            （你的进程/线程，WS_TABSTOP，有效 wndproc）
  └ Chrome_WidgetWin_1          （浏览器进程 —— wndproc 读到 0 == 跨进程）
    └ Chrome_RenderWidgetHostHWND  （浏览器进程）
```

在视图中心用 `WindowFromPoint` 拿到的是 `Chrome_RenderWidgetHostHWND`，
即点击落在**宿主下面两层**。

**结论：「钩住父窗口的点击」这一整族做法在这里结构性失效。**
`WM_PARENTNOTIFY` 和 `WM_MOUSEACTIVATE` **只投给直接父窗口** ——
那是 `Chrome_WidgetWin_0`，永远不是你的宿主。探针实测：对视图真实 `SendInput` 一次点击后，
宿主收到**零个** `WM_PARENTNOTIFY` / `WM_MOUSEACTIVATE`
（唯一的 `WM_PARENTNOTIFY` 是创建控制器时的 `code=1` / `WM_CREATE`）。
`WS_EX_NOPARENTNOTIFY` 是红鲱鱼，它根本没被置上。

| 宿主 HWND 上的消息 | 会到吗 | 说明 |
|---|---|---|
| `WM_SETFOCUS` | 只有在有人 `SetFocus` 宿主时 | 官方路径；宿主是顶层时没问题，是线程上一次焦点时靠激活也能到 |
| `WM_MOUSEACTIVATE` | 只对落在宿主**自己像素**上的点击 | 点进 WebView 内部永远收不到 |
| `WM_PARENTNOTIFY` | **收不到** —— 去了 `Chrome_WidgetWin_0` | 不要建立在它之上 |
| `WM_LBUTTONDOWN` 等 | 只对宿主自己的像素 | 同上 |

所以**不存在**"用户点了页面"的 Win32 触发点 —— 所有候选都在你下面一跳就断。
但这**不**意味着必须去问页面。

### 真修法：把输入队列**早早接上**，然后让激活去干活

点击本身不需要传到你这里。真正要紧的是**随后发生的激活**把焦点交回给宿主：

> 点击 → 系统激活顶层祖先（`SunAwtFrame`）→ AWT 的激活处理把焦点给 Canvas →
> 宿主收到 **`WM_SETFOCUS`** → 焦点转发器 → `MoveFocus`。

这条链只有在点击发生时**两个输入队列已经合并**才成立。
原来的 bug 是**懒接** —— 在第一次焦点请求时才接，既太晚又不可靠，
于是那条链根本没机会跑，才有人发明了 DOM 桥去补。

所以要在**重挂进 AWT 窗口的那一刻**（`SetParent` + `SetWindowPos(..., SWP_FRAMECHANGED)`）
就调 `AttachThreadInput`，而不是从焦点处理函数里接。
这样宿主上一个 `WM_SETFOCUS` 就够了，**完全不需要任何脚本注入**。

> 2026-09-15 以"删除验证"：整套 DOM 桥删掉**并且**把 `put_IsWebMessageEnabled` 设成 `FALSE`
> 之后，打字依然完全正常。删掉一个绕路办法、看着症状不再出现，是这里能拿到的最强证据。

`WM_SETFOCUS` → 焦点转发器仍是**主**入口（`MoveFocus` 还是需要 —— 光靠激活不会把焦点
移**进** WebView），一次性重试定时器也要留着。

### `MoveFocus` 就是全部机制

从宿主线程调 `ICoreWebView2Controller::MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC)`
就够。探针证据：调用后 `GetFocus()` 变成 `Chrome_WidgetWin_1`（在宿主子树内），
页面的 `document.hasFocus()` 从 `0 → 1`，随后的合成按键能让页面侧的 `keydown`
计数器上涨（`KEYS=0 → 2 → 4 → 6`）。

**但那个探针里宿主是它自己的顶层窗口。** 一旦宿主成了外部顶层窗口（AWT 的 frame）的
`WS_CHILD`，除非先把输入队列合并，这个调用会被**静默拒绝** —— 见下面 9b。

不要盲信，要验证：从 `GetFocus()` 沿 `GetParent` 往上走，确认走到了宿主；
走不到就退到 `SetFocus(GetWindow(host, GW_CHILD)` 的第一个可见子窗口 + `GW_HWNDNEXT)`，
再退到 `SetFocus(host)`。

三个陷阱，都是实测的：

- **绝不要在 `MoveFocus` 之**后再 `SetFocus` / `SetForegroundWindow` 宿主。**
  那会把焦点直接拽出 WebView。顺序必须是"先把顶层顶成前台，**再** `MoveFocus`"。
  有个探针反过来做，打印出 `HASFOCUS=1` 然后永远 `KEYS=0` ——
  一次自伤式的假阴性，白花一整轮排查。
- **AWT / Compose 会把焦点抢回去。** 它们的激活处理可能在你 `MoveFocus` **之后**
  `SetFocus` 自己的组件，于是第一次点击看起来是死的、第二次才好。
  要装一个短的一次性重试（`SetTimer`，约 120 ms）再跑一两次 `MoveFocus`。
  用"是否已经在我们的子树里"这个幂等判据保护它，否则第二次 `WM_SETFOCUS`
  在已经聚焦时会引发焦点拉锯 —— 现在没有页面侧的闸门了，**保护来自这个判据本身**。
- **`GetFocus()` 是按消息队列的，不是全局的。** 从宿主自己的线程调用、而焦点归另一个线程的
  队列时它返回 `NULL` —— 把它当"不在我们子树里"处理。
  `SetFocus` 同样只作用于调用线程队列上的窗口，而 WebView2 的子窗口正是（它们是宿主的子窗口）。

### 9b. 更深一层根因：按键是按**前台线程**的队列路由的

上面全部成立，但当宿主活在**自己的线程**上时（非 EDT 的 native 消息循环就是这种情况）还不够。
这条规则解释了"点得动打不了字、一改窗口大小就好了"：

> **按键投递给 `GetGUIThreadInfo(GetForegroundWindow() 的线程).hwndFocus`，
> 而不是你自己线程的 `GetFocus()`。**

**尽量早地接 —— 在 `SetParent` 时，而不是第一次焦点请求时。**
`AttachThreadInput` 收的是两个**线程** id，不关心窗口关系，
所以你完全可以在宿主重挂进 AWT 窗口的那一刻就接上。
在那一刻接，才能让第 9 节的激活链跑起来；懒接意味着"本该驱动这条链的那次点击"
发生在队列合并**之前**，于是整件事又得靠页面侧绕路。焦点处理函数里也留着那次调用无害
（幂等），但它不是承重的那一次。

宿主在 B 线程（WebView 线程）、顶层 AWT frame 在 A 线程（EDT）时：

| 队列 | "成功聚焦页面"之后的 `hwndFocus` |
|---|---|
| 线程 A（前台 `SunAwtFrame` 的属主） | `SunAwtFrame` —— **按键落在这里被吞掉** |
| 线程 B（你的宿主） | `Chrome_WidgetWin_1` —— 看起来对，但什么也没改变 |

此时从 B 线程 `SetFocus` 会被**静默拒绝**（它只作用于调用线程队列上的窗口）——
实测表现为 `focus=0000000000000000` 重复 33 次，而日志欢快地打印"焦点进入 WebView"，
页面收到零个 `keydown`。

改窗口大小时"好了"的原因：resize 让 AWT 重跑它的激活/焦点记账，
顺带把**前台队列的** `hwndFocus` 挪对了 —— 这正是修复方案必须主动做的那件事。

### 修法：对**根窗口**线程做一次**长期** `AttachThreadInput`

```cpp
const HWND root   = GetAncestor(host, GA_ROOT);              // SunAwtFrame
const DWORD rootTid = GetWindowThreadProcessId(root, nullptr);
if (rootTid && rootTid != hostTid && view->attachedTid != rootTid) {
    if (view->attachedTid) AttachThreadInput(hostTid, view->attachedTid, FALSE);
    if (AttachThreadInput(hostTid, rootTid, TRUE)) view->attachedTid = rootTid;
}
```

- **每个视图接一次并保持住**（把 tid 缓存在 view 上，只在 `WM_DESTROY` 里摘）。
  端到端实测：按键计数 `0~1 → 11`，焦点事件 `117 → 3`。
- **临时 / 作用域式**的接法（接 → `SetFocus` → 析构里摘）**不管用**：
  它只能让你自己的 `GetFocus()` 读起来正确，永远移不动前台队列的 `hwndFocus`。
  **不要把长期接改回作用域接** —— 留一行注释说明。
- 接**根**窗口的线程（`GA_ROOT`），不要接 `GetForegroundWindow()` 的线程，目标才稳定。
- 留一个环境变量关掉它的开关（如 `ACG_WEBVIEW_NO_THREAD_ATTACH=1`，读一次并缓存）：
  `AttachThreadInput` 在 `SendMessage` 层面把两个消息循环耦合在一起，
  万一在别的机器上出现卡死，你需要一个不改代码的 A/B。

**为什么某些库完全不需要这些**：它们是在 JNI 里**从 AWT 线程**用
`CreateWindowExW(WS_CHILD, parent_hwnd)` 建宿主的，父子同线程，焦点链天然连通，
`WM_MOUSEACTIVATE` 甚至能到达。代价是 WebView2 的消息泵跑在 AWT/EDT 线程上。
本工程要保留独立的非 EDT 线程（为了让 UI 保持响应），所以 `AttachThreadInput` 是等价物 ——
不能照抄它们那套。

### 焦点转发器必须**幂等**，否则会弄坏输入法

每一次 `MoveFocus` / `SetFocus` 都是一次新的焦点变更，Chromium 会在每次焦点变更时
重置 IME 组合状态（候选窗收起、打了一半的拼音被丢弃）。
这个问题最严重的时候是页面侧桥在 `mousedown`、`pointerdown`、`touchstart` **和** `focus`
上都 ping —— 天真的转发器在打字过程中不断重新聚焦，被报成"焦点一直被强制切换，没法用输入法连续输入"。
桥已经删了，但调用点并不少（激活驱动的 `WM_SETFOCUS`、AWT 的 `focusGained`、重试定时器），
所以闸门要留着。

**每个**调用点都过同一个判据：

```cpp
if (GetForegroundWindow() != GetAncestor(host, GA_ROOT)) return false;  // 队列值已过期
HWND f = GetFocus();                                    // 我们跑在宿主的线程上
return f && f != host                                   // 宿主自己不算
    && isSelfOrDescendant(f, host)
    && GetParent(f) != host;                            // Chrome_WidgetWin_0 不算
```

实测过的关键细节：

- 要问**宿主所在线程**，不是前台线程。焦点真的进了页面之后，
  **前台**（AWT）队列的 `hwndFocus` 是**空的** —— 拿它当判据会永远 false，
  闸门静默失效，IME 的 bug 就回来了。
- 宿主自身、以及直接子窗口 `Chrome_WidgetWin_0`，**都不算**"已聚焦" —— 按键会在那里停住。
  只有 `Chrome_WidgetWin_1` / `Chrome_RenderWidgetHostHWND` 算。
- **不要**用任何页面侧的东西当闸门。若确实要留 DOM 桥兜底，把它排除在闸门之外：
  在 AWT 嵌入的宿主里 `document.hasFocus()` 是 `true`，而键盘焦点还在 `SunAwtFrame` 上，
  拿它当守卫会直接把桥废掉。
- 日志：把**前台队列的** `hwndFocus` 和你自己的 `focus=` 并排打印。
  两者不一致时，你能一行看出队列断开，而不用猜。

### 不要在别人的进程里留下一个 attach

如果某个**外部探针**为了证明结论，对正在运行的应用 JVM 做了长期 `AttachThreadInput`，
应用自身的接/摘会和它打架，**UI 直接卡死**。把那个实验从探针里删掉；
实在要跑，跑完必须杀掉应用（只杀探针不够）。

### 不用 UI 也能验证焦点

`GetGUIThreadInfo(tid of GetForegroundWindow())` 的 `hwndFocus` =
`Chrome_WidgetWin_1` 说明键盘去了页面，其他值说明没去。
`GetFocus()` 做不到这件事（它是按调用线程队列的），`GetActiveWindow()` 也不行（按线程）。

---

## 10. Win32 没有事件冒泡 —— 且"子窗口拿到焦点" ≠ "按键会到"

两个独立误解，每条都花过大量时间，直接写死：

**1. 没有冒泡。** 鼠标消息只投给**一个** HWND —— 命中测试产生的那个。祖先不在投递路径上。
DOM 会冒泡是因为浏览器自己**在命中测试之后**走了祖先链；窗口管理器不会。

存在的"子→父"流量只有少数特例，**每条都只有一跳、且需要子窗口配合**：

| 消息 | 谁收到 |
|---|---|
| `WM_PARENTNOTIFY` | 只有**直接**父窗口；会被 `WS_EX_NOPARENTNOTIFY` 抑制 |
| `WM_MOUSEACTIVATE` | 只有当子窗口把它交给 `DefWindowProc` 时，父窗口才收到（MSDN 原文） |
| `WM_COMMAND` / `WM_NOTIFY` | 控件自己的实现发的，不是系统 |
| `WM_MOUSEWHEEL` / `WM_MOUSEHWHEEL` | 真正的例外：`DefWindowProc` 会沿父链上传 |

对 WebView2 的窗口树：点击落在 `Chrome_RenderWidgetHostHWND`（浏览器进程），
唯一能被通知的窗口是它的**直接**父窗口 `Chrome_WidgetWin_1`，也在浏览器进程里，链到此为止。
子类化 `Chrome_WidgetWin_0`（它**在**你的线程上）没用 —— 它不是直接父窗口。

**2. 焦点与按键投递是两件事。** 子窗口完全可以拥有焦点（`SetFocus` 子窗口是合法的，
跨线程也合法），WebView2 的文档就这么说。但**不**代表按键会到它 —— 见 9b：
按键去的是**前台**线程的焦点窗口，而只有**顶层**窗口能成为前台。

所以子窗口宿主可以"有焦点"却收到零个按键。这正是"点得动但打不了字"，而且它是
**层级**问题（子 ≠ 顶层），**不是**"跨线程挂父窗口非法"的问题 ——
跨线程/跨进程的父子关系是合法的，这棵树用的正是它。

**架构上的推论**：如果宿主是它**自己的顶层**窗口，那么 `GetForegroundWindow()` 就是宿主，
它的线程就是前台线程，上面所有绕路一次性消失：不用追点击（激活会给你 `WM_ACTIVATE`，
要钩 `WM_ACTIVATE` 而不只是 `WM_SETFOCUS`），也不需要 `AttachThreadInput`。

---

## 11. 做不到的事：把 Compose 内容画在真 HWND **之上**

"把 Compose UI 画在 web view 上面" / "把 web view 放到 Compose 下面" —— **做不到**，
原因是结构性的，不是缺一个开关：

- WebView 宿主是真的子 HWND，它是 Compose/Skia 表面的**兄弟**（实测：两个同级的 `SunAwtCanvas` HWND）。
  真 HWND **不**受 Skia 的 z-order 管辖，所以"Compose 画在上面、页面透出来"不可能 ——
  Skia 表面是不透明的，把它抬起来只会把整个页面盖住。
- 因此 AWT/Compose 的**重型**组件永远画在 Compose 内容之上；想做"压在 web view 上的覆盖层"会被盖住。

**能用的办法**，按工作量递增：

1. **区域不重叠** —— 用 `SetWindowRgn` 裁宿主，两者干脆不相交。
2. **透明的顶层覆盖窗**（`JWindow` / Compose `Popup`）定位在 web view 区域之上，
   承载覆盖内容。真透明、真 z-order，背后没有页面像素。
3. **合成模式**（`ICoreWebView2CompositionController` + `put_RootVisualTarget`）——
   唯一能真正做到真交错的方式，但鼠标/指针/键盘输入就得**你自己**接管
   （`SendMouseInput` / `SendPointerInput` / `SendKeyboardInput`），IME 也要在之上重做。
   注意 `CapturePreview` 只能出 PNG/JPEG，逐帧 BGRA 位图管线不可行。

   **动手之前先查 Compose 的 layer 类型（Compose for Desktop 1.5+）。**
   桌面端的 `Popup`/`Dialog` 是 `ComposeSceneLayer`，具体实现由系统属性
   `compose.layers.type` 决定（只读一次，全进程生效）：

   | 取值 | layer | 能盖住重型 WebView 吗 |
   |---|---|---|
   | 未设（默认） | `OnSameCanvas` —— 画进主 Skia canvas | **不能** —— 那个 canvas 在宿主 HWND **下面** |
   | `COMPONENT` | `SwingComposeSceneLayer` | 不能，同理 |
   | `WINDOW` | `WindowComposeSceneLayer` —— 真的 `JWindow(parentWindow)`，`Window.Type.POPUP`，透明 | **能** |

   所以 `-Dcompose.layers.type=WINDOW` + `Popup(...)` 就能不写任何 native 代码，
   在 web view 之上得到一层浮动 Compose。几个实测过的属性：

   - `layerWindow.setSize(drawBounds)` —— popup 窗被裁到**你实际画出来的范围**，
     没画到的区域对页面保持点击穿透。所以一个 `fillMaxSize` 的覆盖层会挡掉页面的**每一次**点击，
     一个小徽标不会。
   - `PopupProperties.focusable` 映射到 `JWindow.focusableWindowState`。默认 `false` 让键盘留在页面里，
     但覆盖层里也就没法输入文本；设 `true` 会从 WebView 夺走焦点 —— 覆盖层关闭时记得用 `MoveFocus` 还回去。
   - `dismissOnClickOutside` 默认 **true**，常驻覆盖层必须设 `false`。
   - 这是**全进程**开关，且 JetBrains 自己的源码注释里列了已知问题：显示闪一下（#4475）、
     父窗口 resize 时对话框被裁（#4484）、Linux 上的渲染 bug（#4437）。
   - `compose.interop.blending=true` 是另一个"把 Compose 画到 interop 之上"的开关，
     但其文档说它无法在 DirectX 之上叠另一个 DirectX 组件 —— WebView2 正好就是那个，所以别指望它。

   AWT/Compose 做不了逐像素点击穿透；唯一的办法是在覆盖层 HWND 上加
   `WS_EX_TRANSPARENT | WS_EX_LAYERED`，但那样覆盖层自己也点不到了。

另外：**WebView2 无法真正做到 headless** —— `CreateCoreWebView2Controller` 需要一个父 HWND。
"后台视图"（隐藏 `WS_POPUP`、不带 `WS_VISIBLE`、1×1、自己的线程）已经是无 AWT 的，
是实际可用的 headless 替代品。

---

## 速查清单

1. 记 `ProcessFailed` 的 kind/reason/exit，**只对致命 kind 报警**。
2. 创建 environment **之前**开 `--enable-logging --v=1`，读
   `<userDataFolder>\EBWebView\chrome_debug.log`。
3. 看到 `FATAL: GPU process isn't usable` → 加 `--disable-gpu-process-crash-limit`。
4. 每个开关都要在**真宿主**里复测，绝不用独立探针下结论。
5. 关控制器要在停消息泵**之前**；每次等待都有超时；退出用
   `PostThreadMessageW(WM_QUIT)` 兜底。
6. cookie 不共享？先看 session 还是 persistent，再动代码。
7. 嵌进 AWT/Compose：隐藏顶层 → `SetParent` → `WS_CHILD` → `SWP_FRAMECHANGED`
   → `ShowWindow` → 按 `GetClientRect(parent)` 适配。
8. 点得动打不了字 → **先 dump 窗口树**。看到
   `host → Chrome_WidgetWin_0 → Chrome_WidgetWin_1(浏览器进程)`，
   就知道 `WM_PARENTNOTIFY` / `WM_MOUSEACTIVATE` 永远到不了你，任何建立在它们之上的代码都是死的。
   然后**按这个顺序**：
   1. 宿主在自己的线程上？→ 在 **`SetParent` 时**就对 `GetAncestor(host, GA_ROOT)` 的线程做
      **长期** `AttachThreadInput`（见 9b），并让所有焦点调用过幂等判据，否则 IME 会坏。
      通常这一步就是全部修复 —— 打字再也不需要任何注入。
   2. 还是死的？**然后**才加 DOM 桥（`mousedown` → `postMessage` +
      `put_IsWebMessageEnabled(TRUE)` + `add_WebMessageReceived`）作为**兜底**。
   3. 每条路径都走 `MoveFocus(PROGRAMMATIC)`；**绝不在 `MoveFocus` 之后** `SetFocus` 宿主；
      装一个约 120 ms 的一次性重试，因为 AWT/Compose 可能把焦点抢回去。
   "改窗口大小就能打字"是"点击发生时队列没合并"的标志性症状。
9. 从应用**外部**验证焦点，不装任何页面钩子：找到宿主的 `Chrome_WidgetWin_0`，
   在它屏幕中心 `SendInput` 一次真点击，然后对窗口树里每个线程调 `GetGUIThreadInfo(tid)` 读
   `hwndFocus`。是 `Chrome_WidgetWin_1` 说明键盘去了页面。
   对照 `GetGUIThreadInfo(GetForegroundWindow() 的 tid)` —— **那个**队列才是收按键的。
   `GetFocus()` 和 `GetActiveWindow()` 都做不到（都按线程），跨进程证明不了任何事。
