# WebView2（Windows）排查手册

本工程桌面端的可见页与后台页都跑在**自研 C++ 宿主**（`cxx/webview/webview.cpp`）上，
共享同一个 `ICoreWebView2Environment` → 同一份 user data folder → 同一套 cookie。

WebView2 通过 API 几乎不报告任何有用信息，下面每个症状都长得像"我们的代码错了"，
实际多半是引擎或浏览器参数的问题。**按顺序排查**，从最便宜、最能定性的开始。

> 相关入口：[`cxx/AGENTS.md`](../../cxx/AGENTS.md)（构建与 JNI、QuickJS 所有权硬约束）。

---

## 1. 先拿真错误，再推理

**symptom**：进程挂掉 / 视图集体死，但托管侧报错信息无法行动。

**what to check**：唯一信号是 `ICoreWebView2ProcessFailedEventHandler`。用
`ICoreWebView2ProcessFailedEventArgs2` 把 **kind / reason / exitCode 三个一起**读出来。

| 字段 | 关键取值的含义 |
|---|---|
| `kind` | 0 `BROWSER_PROCESS_EXITED`（**致命**）、1 `RENDER_PROCESS_EXITED`（**致命**）、2 `RENDER_UNRESPONSIVE`、3 `FRAME_RENDER_PROCESS_EXITED`（**致命**）、4 `UTILITY`、5 `SANDBOX_HELPER`、6 `GPU` |
| `reason` | 0 `UNEXPECTED`、1 `UNRESPONSIVE`、2 `TERMINATED`、3 `CRASHED`、4 `LAUNCH_FAILED` |
| `exitCode` | 进程退出码。`0x80000003` / `-2147483645` = `STATUS_BREAKPOINT`，即该进程内部发生了 Chromium 的 `CHECK` / `abort()` |

**fix（DO/DON'T）**：
- **DO**：只对 `kind` 0 / 1 / 3 上报失败。把 4 / 5 / 6 当失败本身就是 bug——GPU 与 utility
  进程本来就会反复重启（`reason=2 TERMINATED`），把它们当失败会把一次进展正常的导航拆掉。
- **DO**：environment 建失败时，宿主只会把 HRESULT 以 `0x%08X` 打进原生日志（如
  "environment failed, hr=0x80070005"）。要翻成人话，三样一起看：
  - 名字：`E_ACCESSDENIED`、`HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)`…；
  - `FormatMessageW` 文本（`FACILITY_WIN32` 的先还原成 Win32 错误码）；
  - user data folder 路径与 `GetAvailableCoreWebView2BrowserVersionString`。

---

## 2. 读 Chromium 自己的日志 —— 它在 user data folder 里

**symptom**：导航不触发 `NavigationStarting`，连 `about:blank` 之后什么都打不开。

**what to check / command**：引擎日志写在 `<userDataFolder>\EBWebView\chrome_debug.log`，
只有开了日志才有。在**创建 environment 之前**设进程环境变量：

```
WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--enable-logging --v=1
```

**fix（DO/DON'T）**：
- **DO**：grep 最该看的一行：
  ```
  FATAL:<file>:<line>] GPU process isn't usable. Goodbye.
  ```
  这是**浏览器进程**里的 `CHECK` 失败：整个浏览器进程随后以 `STATUS_BREAKPOINT` 退出，所有视图一起死。
  宿主侧永远不会告诉你原因，只有这个文件会。

---

## 3. 哪些开关真有用

**symptom**：GPU 进程崩溃循环 → 随后 `GPU process isn't usable` → 所有视图死。

**what to check / command**：
- **`--disable-gpu-process-crash-limit` —— 唯一真管用的那个。** 它让 GPU 依旧坏，但浏览器进程不再被它带走。
- **`--disable-gpu` 没用。** 它只关硬件加速；GPU **进程**照旧启动、照旧崩、照旧带走浏览器进程。
  同样无效的还有 `--disable-software-rasterizer`、`--use-angle=swiftshader`、
  `--disable-features=SkiaGraphite`、`--disable-gpu-shader-disk-cache`、`--disable-gpu-compositing`。
  这个失败模式下别在它们身上浪费时间。
- `--no-sandbox` / `--single-process` 治的是别的失败态（沙箱起不来、渲染进程拉不起来），救不了浏览器进程的 `CHECK` 失败。

**fix（DO/DON'T）**：
- **DON'T**：默认把 `--no-sandbox` 写进代码 —— 关掉 Chromium 沙箱是**安全降级**，靠环境变量按需打开。正解应去查 Windows「漏洞利用防护 / Exploit Protection」的 Mandatory ASLR 或安全软件。
- **DO**：诊断前先把 `WEBVIEW2_*` 环境变量清干净，否则会把环境态误判成代码回归。

### 怎么设这些开关

WebView2 支持两种方式，且会**合并**：环境变量 / 注册表的值会**追加**到
`CreateCoreWebView2EnvironmentWithOptions` 的 `environmentOptions` 参数之后（webview2-idl 的
`Globals` 明示此合并语义）。

1. **options 接口**（当前本工程采用）：一个实现了 `ICoreWebView2EnvironmentOptions` 的 COM 对象，
   作为第三实参传给 `CreateCoreWebView2EnvironmentWithOptions`。
2. **环境变量**：在 `CreateCoreWebView2EnvironmentWithOptions` **之前**调
   `SetEnvironmentVariableW("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", ...)`。loader 会读它，
   浏览器子进程继承这一块环境块。

**fix（DO/DON'T）**：
- **DON'T**：手写 `ICoreWebView2EnvironmentOptions` 的 vtable 错一格 —— 会出现「建环境成功、参数不生效」的假象。
- **DO**：优先走 options 接口。本工程的 `webview.cpp` 因 MinGW/g++ 缺 WRL `implements.h`，手写了一个零 WRL 依赖的 `ICoreWebView2EnvironmentOptions`，参数由 JVM 侧当构造参数给入。
- **DO**：提权宿主以高 IL 运行时，WebView2 **忽略** `WEBVIEW2_*` 环境变量（含
  `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS`），但**认**通过 API 设的 flags（Microsoft Learn《Develop secure WebView2 apps》）——这正是不走环境变量更稳的一条理由。
- **DO**：测试期换参数不重编，在 Gradle 里用 `providers.environmentVariable("WEBVIEW2_ARGS")`
  追加到 `Test` 任务的 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` 上（见 `platform/build.gradle.kts`）。
  注意这个变量**只对 jvmTest 生效**，`:desktopApp:run` 不吃它。

---

## 4. 别用独立探针下"开关有没有用"的结论

**symptom**：独立探针测出来的参数"全没用"。

**what to check**：独立探针（不带 JNI / Gradle / UI 框架）只擅长把"这台机器上的引擎坏了"和
"我们的宿主坏了"分开。它**不是**判断浏览器参数的有效载体——它会为自己的原因失败，连 `about:blank`
都到不了 `NavigationCompleted`，于是所有经它测的参数看起来都"没用"。

**fix（DO/DON'T）**：
- **DO**：任何参数结论都必须在真宿主里复测，那里才有已知可用的基线（例如 `about:blank` 能成功）。
- **DO**：始终保留一个健康检查用例，导航一个极简页面并断言脚本结果 —— 这样"引擎死了"会被报成一次 skip，而不是 N 个超时。

---

## 5. 关闭顺序：控制器要在消息泵还活着时关

**symptom**：退出时杀不掉的进程，或退出码 3（`abort()`）。

**what to check / command**：两个独立的坑：

1. **顺序**：`ICoreWebView2Controller::Close()` 是异步完成的，完成回调需要消息循环继续转。
   先停泵、后关闭会让拆除、`CoUninitialize()` 和 DLL 静态析构卡住。
2. **有界等待**：坏掉的引擎能让 `Close()` 阻塞几十秒。JVM shutdown hook 里无界的
   `std::thread::join()` 会把进程退出挂死；一个可 join 的静态 `std::thread` 析构会调 `std::terminate`。

**fix（DO/DON'T）**：
- **DO**：往 UI 线程投一个任务，关掉所有控制器，等它，**然后**才退出消息泵。
- **DO**：带超时等；超时就 `detach()`（并把 dispatcher 置空，让后续投递快速失败），不要永久 join。
- **DO**：退出消息泵用 `PostThreadMessageW(threadId, WM_QUIT, 0, 0)` 兜底（不依赖窗口存活；`WM_CLOSE` 需窗口还活着）。线程 id 在启动时记下来。
- **DO**：把 dispatcher 的 HWND 设成 `std::atomic` —— UI 线程退出时会清它，其他线程读它来投递任务。

---

## 6. Cookie：session 与 persistent 就是全部

**symptom**：视图 A 写了 cookie，视图 B（同 environment、同 user data folder）读不到。

**what to check**：先看这个 cookie 的生存期，别急着怀疑存储接线：

- `document.cookie = 'x=1; path=/'` 是 **session cookie** —— 活在浏览器进程内存里，**不会**写进 `Default/Network/Cookies`。
- 两个视图之间**没有任何 WebView 存活**时，这份内存状态被丢掉，第二个视图什么都看不到。
- 加上 `max-age=3600` 立刻可用，条目也会出现在 SQLite 的 `Cookies` 库里。

**fix（DO/DON'T）**：
- **DO**：记住存储本身是共享的，缺口只在内存里的 session 状态。要在没有任何存活视图时保住 session cookie，留一个 keep-alive 的 WebView 开着。

---

## 7. user data folder

**symptom**：多视图"共享登录"根本没法验证，每次跑都是全新 cookie 罐。

**what to check**：查路径来源。

**fix（DO/DON'T）**：
- **DON'T**：退到 `java.io.tmpdir` / `%TEMP%` —— 在 MSYS2 / Gradle 下那可能是一次性路径（MSYS2 自己的临时目录）。
- **DO**：按 `LOCALAPPDATA` → `user.home/AppData/Local` → `user.home` 逐级找，并 `mkdirs()`。所有视图必须用**同一个**目录 —— 共享 cookie 全靠它。

---

## 8. 把 WebView 嵌进 AWT / Compose Desktop（JAWT）

**symptom**：嵌进去后不可见或尺寸错误，或 JAWT 取不到 HWND。

**what to check / command**：重挂 HWND 到 AWT 组件，用 **JAWT** 取组件 HWND。

- **不要链 `jawt.lib`**（MinGW 处理 MSVC 导入库痛苦，而 JVM 起来后 `jawt.dll` 已映射进进程）。
  运行时解析：`GetModuleHandleW(L"jawt.dll")` → 退到 `LoadLibraryW` → `GetProcAddress(..., "JAWT_GetAWT")`。
  编译时把 `$JAVA_HOME/include` 和 `$JAVA_HOME/include/win32` 加进 include 路径。
- 取句柄：`JAWT_VERSION_1_4` → `GetDrawingSurface` → `Lock` → `GetDrawingSurfaceInfo`
  → `JAWT_Win32DrawingSurfaceInfo::hwnd`。**只取句柄**，不碰绘图状态。
  `FreeDrawingSurfaceInfo` / `Unlock` / `FreeDrawingSurface` 必须成对，否则泄漏。

**fix（DO/DON'T）**：
- **DO**：选一个真的用 AWT 的窗口后端（用标准 compose.desktop 的 `application { Window(...) }`）。换掉自绘后端给不出 HWND，整条路走不通。
- **DO**：附着顺序不可商量：
  1. 先建成**隐藏的顶层** popup（不带 `WS_VISIBLE`）。创建时不能建成 `WS_CHILD` —— AWT 组件尚无 peer、无父 HWND。
  2. 等 AWT 组件 `isDisplayable` 后，解析它的 HWND 并 `SetParent`。
  3. **然后**清掉 `WS_POPUP|WS_CAPTION|WS_THICKFRAME`，加上 `WS_CHILD|WS_CLIPCHILDREN|WS_CLIPSIBLINGS`。
  4. `SetWindowPos(..., SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED)` —— 光调 `SetWindowLongPtr` **不会**让新样式生效。
  5. 最后才 `ShowWindow`，然后设尺寸。
  - 把第 3 步做到第 2 步之前，窗口会消失（还没有父子链）；别对尚未附着的可见视图 `ShowWindow`，会闪一个 1×1 的 popup。
- **DO**：尺寸一律以父窗口的 client rect 为准，且在 native 侧算：`GetClientRect(parent)` 是唯一真值，推进控制器用 `put_Bounds`。**不要同时**从托管侧再传一次宽高（DPI 缩放下两边取整不同会互相打架）。`GetClientRect` 读别的线程的窗口安全（不发消息）。
- **DO**：预期 AWT 重型组件**永远画在 Compose 内容之上**，"盖在 web view 上的 Compose 覆盖层"会被盖住（见第 11 节）。

---

## 9. 焦点：渲染正常、点击有反应、但打字没反应

**symptom**：点得动、渲染正常，键盘死。根因永远同一个：**焦点落到宿主 HWND 不会自动传进 WebView2，而 WebView2 从不自己抢焦点。** 鼠标消息按位置投递、不需要焦点——这正是"点得动但键盘死"的原因。

> **2026-10-15 更新**：本节原先教的是「`AttachThreadInput` + `MoveFocus(PROGRAMMATIC)` + 120 ms 重试」三板斧，并称 `MoveFocus` 是"全部机制"。**实测结论相反：一次长期的 `AttachThreadInput` 就足以把跨线程焦点连起来，不需要 `MoveFocus`。** 宿主重写（`c3d50cf`）据此把整套焦点转发删掉了，只留 `attach()` 里那一次长期接续（`webview.cpp` 的 `attachThreadInput`：接 `GetAncestor(parent, GA_ROOT)` 的线程、接上不摘）；`Webview2Context.focus()`（裸 `SetFocus(host)`）也已删除。**下面关于 `MoveFocus` / 幂等闸门 / 重试定时器的段落全部作废**，保留它们是为了记录"曾经以为需要"的推理过程；窗口树与消息路由那半仍然有效，仍是判断"哪些办法不可能"的唯一依据。

### 先 dump 窗口树，因为它决定了哪些办法不可能

从宿主遍历子窗口，逐层打印 `GetWindowThreadProcessId` 和 `GetWindowLongPtr(GWLP_WNDPROC)`。
WebView2 给的是**跨进程的两层**嵌套：

```
host                            （你的进程 / 你的线程）
└ Chrome_WidgetWin_0            （你的进程/线程，WS_TABSTOP，有效 wndproc）
  └ Chrome_WidgetWin_1          （浏览器进程 —— wndproc 读到 0 == 跨进程）
    └ Chrome_RenderWidgetHostHWND  （浏览器进程）
```

在视图中心用 `WindowFromPoint` 拿到的是 `Chrome_RenderWidgetHostHWND`（点击落在宿主下面两层）。

**结论：「钩住父窗口的点击」这一整族做法结构性失效。** `WM_PARENTNOTIFY` 和 `WM_MOUSEACTIVATE`
**只投给直接父窗口**——那是 `Chrome_WidgetWin_0`，永远不是你的宿主。

| 宿主 HWND 上的消息 | 会到吗 | 说明 |
|---|---|---|
| `WM_SETFOCUS` | 只有有人 `SetFocus` 宿主时 | 官方路径；宿主是顶层时没问题，是线程上一次焦点时靠激活也能到 |
| `WM_MOUSEACTIVATE` | 只对落在宿主**自己像素**上的点击 | 点进 WebView 内部永远收不到 |
| `WM_PARENTNOTIFY` | **收不到** —— 去了 `Chrome_WidgetWin_0` | 不要建立在它之上 |
| `WM_LBUTTONDOWN` 等 | 只对宿主自己的像素 | 同上 |

所以**不存在**"用户点了页面"的 Win32 触发点。但这**不**意味着必须去问页面。

### 真修法：把输入队列早早接上

点击本身不需要传到你这里；真正要紧的是随后发生的激活把焦点交回宿主：
`点击 → 系统激活顶层祖先（SunAwtFrame）→ AWT 激活处理把焦点给 Canvas → 宿主收到 WM_SETFOCUS`。
焦点能不能真的落进 WebView2，取决于点击发生时**两条输入队列是否已经合并** —— 所以要在
**重挂进 AWT 窗口的那一刻**（`SetParent` + `SetWindowPos(..., SWP_FRAMECHANGED)`）就调
`AttachThreadInput`，而不是从焦点处理函数里接。**这一条就是全部修复**，不需要
`MoveFocus`、不需要重试定时器、不需要任何脚本注入（2026-10-15 实测，见下文）。

### `MoveFocus` 不是必需的（2026-10-15 实测结论，推翻本节原判）

本节原先写的是「`MoveFocus(PROGRAMMATIC)` 就是全部机制」，并配套 120 ms 重试定时器与
「绝不在 `MoveFocus` 之后 `SetFocus` 宿主」的次序纪律。**实测结论：那些都不需要** ——
一次长期的 `AttachThreadInput` 就足以把跨线程的焦点连起来，键盘直接进页面。

因此宿主重写（`c3d50cf`）把整套焦点转发删干净了，只留 `attach()` 里那一次长期接续
（`webview.cpp` 的 `attachThreadInput`：接 `GetAncestor(parent, GA_ROOT)` 的线程、接上不摘）。
`Webview2Context.focus()`（裸 `SetFocus(host)`）也一并删除 —— 全仓再无焦点转发调用点。

**给后来者的三条**：

- **DO**：键盘不通时，先确认 `attachThreadInput` 那一步真的跑了（native 日志有
  「输入队列已接到 AWT 线程」一行；没有就是 `attach` 没走到）。
- **DON'T**：再加 `MoveFocus` / 重试定时器 / DOM 焦点桥 —— 三条都试过，都不必要，
  而 `MoveFocus` 每次调用都会重置 Chromium 的 IME 组合状态（候选窗收起、拼音丢弃），
  非但不能修键盘，反而会弄坏输入法。
- **DON'T**：别把 `AttachThreadInput` 改成作用域式（接 → `SetFocus` → 析构里摘）——
  那条路径连「自己的 `GetFocus()` 读起来正确」都做不到，更别说前台队列。

### 9b 的原始推理（已作废，仅存档）

> 原判据：按键投递给 `GetGUIThreadInfo(GetForegroundWindow() 的线程).hwndFocus`，而不是
> 宿主自己线程的 `GetFocus()`。据此推论宿主在 B 线程、AWT 根窗口在 A 线程时，前台队列的
> `hwndFocus` 仍是 `SunAwtFrame`、按键被吞，`SetFocus` 则因只作用于调用线程队列而被静默拒绝。
> **该推论已被实测推翻**：`AttachThreadInput` 合并两条输入队列之后，前台队列的焦点记账
> 会跟着走，不需要额外推一把。留存只为记录"曾经以为需要"的推理链，避免重复走上。

### 不要在别人的进程里留下一个 attach

**DON'T**：外部探针对运行中应用 JVM 做长期 `AttachThreadInput`，应用自身的接/摘会和它打架，**UI 直接卡死**。把实验从探针删掉；实在要跑，跑完必须杀掉应用（只杀探针不够）。

### 不用 UI 也能验证焦点

`GetGUIThreadInfo(tid of GetForegroundWindow())` 的 `hwndFocus` =
`Chrome_WidgetWin_1` 说明键盘去了页面，其他值说明没去。
`GetFocus()`（按调用线程队列）和 `GetActiveWindow()`（按线程）都做不到。

---

## 10. Win32 没有事件冒泡 —— 且"子窗口拿到焦点" ≠ "按键会到"

**symptom**：误以为能靠钩点击 / 靠子窗口有焦点收按键。

**fix（DO/DON'T）**：
- **DON'T**：指望冒泡。鼠标消息只投给**一个** HWND（命中测试产生的那个），祖先不在投递路径上。DOM 会冒泡是因为浏览器自己在命中测试后走了祖先链；窗口管理器不会。仅存的"子→父"流量都是一跳且需子窗口配合：

  | 消息 | 谁收到 |
  |---|---|
  | `WM_PARENTNOTIFY` | 只有**直接**父窗口；会被 `WS_EX_NOPARENTNOTIFY` 抑制 |
  | `WM_MOUSEACTIVATE` | 只有子窗口把它交给 `DefWindowProc` 时父窗口才收到 |
  | `WM_COMMAND` / `WM_NOTIFY` | 控件自己实现发的，不是系统 |
  | `WM_MOUSEWHEEL` / `WM_MOUSEHWHEEL` | 真正的例外：`DefWindowProc` 沿父链上传 |

  对 WebView2 树：点击落在 `Chrome_RenderWidgetHostHWND`（浏览器进程），唯一能被通知的是直接父 `Chrome_WidgetWin_1`（也在浏览器进程），链到此为止。子类化 `Chrome_WidgetWin_0`（在你的线程上）没用——它不是直接父窗口。

- **DON'T**：把"子窗口有焦点"当成"按键会到"。子窗口完全可以拥有焦点（`SetFocus` 子窗口合法，跨线程也合法），但见 9b：按键去的是**前台**线程的焦点窗口，只有**顶层**窗口能成为前台。子窗口宿主可以"有焦点"却收零个按键。这是**层级**问题（子 ≠ 顶层），**不是**"跨线程挂父窗口非法"的问题——跨线程/跨进程的父子关系合法，这棵树用的正是它。

**架构推论**：若宿主是它**自己的顶层**窗口，那么 `GetForegroundWindow()` 就是宿主，它的线程就是前台线程，所有绕路一次性消失：不用追点击（激活给 `WM_ACTIVATE`，要钩 `WM_ACTIVATE` 而不只是 `WM_SETFOCUS`），也不需要 `AttachThreadInput`。

---

## 11. 做不到的事：把 Compose 内容画在真 HWND **之上**

**symptom**：想"把 Compose UI 画在 web view 上面" / "web view 放到 Compose 下面"。**做不到**，结构性的：

- WebView 宿主是真的子 HWND，是 Compose/Skia 表面的**兄弟**（两个同级 `SunAwtCanvas` HWND）。
  真 HWND **不**受 Skia 的 z-order 管辖，所以"Compose 画在上面、页面透出来"不可能——Skia 表面不透明，把它抬起只会盖住整个页面。
- AWT/Compose 的**重型**组件永远画在 Compose 内容之上；想做"压在 web view 上的覆盖层"会被盖住。

**能用的办法**（工作量递增）：

1. **区域不重叠** —— 用 `SetWindowRgn` 裁宿主，两者不相交。
2. **透明的顶层覆盖窗**（`JWindow` / Compose `Popup`）定位在 web view 区域之上，承载覆盖内容。真透明、真 z-order，背后没有页面像素。
3. **合成模式**（`ICoreWebView2CompositionController` + `put_RootVisualTarget`）——唯一能真交错的方式，但鼠标/指针/键盘输入得**你自己**接管（`SendMouseInput` / `SendPointerInput` / `SendKeyboardInput`），IME 也要在之上重做。注意 `CapturePreview` 只能出 PNG/JPEG，逐帧 BGRA 位图管线不可行。

**DO**：动手前先查 Compose 的 layer 类型（Compose for Desktop 1.5+）。`Popup`/`Dialog` 是
`ComposeSceneLayer`，具体实现由系统属性 `compose.layers.type` 决定（只读一次，全进程生效）：

| 取值 | layer | 能盖住重型 WebView 吗 |
|---|---|---|
| 未设（默认） | `OnSameCanvas` —— 画进主 Skia canvas | **不能** —— 那个 canvas 在宿主 HWND **下面** |
| `COMPONENT` | `SwingComposeSceneLayer` | 不能，同理 |
| `WINDOW` | `WindowComposeSceneLayer` —— 真的 `JWindow(parentWindow)`，`Window.Type.POPUP`，透明 | **能** |

`-Dcompose.layers.type=WINDOW` + `Popup(...)` 即可不写任何 native 代码，在 web view 之上得到一层浮动 Compose。实测属性：

- `layerWindow.setSize(drawBounds)` —— popup 窗被裁到**你实际画出来的范围**，没画到的区域对页面保持点击穿透。所以 `fillMaxSize` 覆盖层会挡掉页面**每一次**点击，小徽标不会。
- `PopupProperties.focusable` 映射到 `JWindow.focusableWindowState`。默认 `false` 让键盘留在页面；设 `true` 会从 WebView 夺走焦点——覆盖层关闭时把焦点还回去（宿主已无 `MoveFocus` 转发，让 AWT 自己重新激活宿主即可）。
- `dismissOnClickOutside` 默认 **true**，常驻覆盖层必须设 `false`。
- 这是**全进程**开关，JetBrains 源码注释列了已知问题：显示闪一下（#4475）、父窗口 resize 时对话框被裁（#4484）、Linux 渲染 bug（#4437）。
- `compose.interop.blending=true` 是另一个"把 Compose 画到 interop 之上"的开关，但其文档说它无法在 DirectX 之上叠另一个 DirectX 组件——WebView2 正好就是那个，别指望它。

AWT/Compose 做不了逐像素点击穿透；唯一办法是在覆盖层 HWND 上加 `WS_EX_TRANSPARENT | WS_EX_LAYERED`，但那样覆盖层自己也点不到了。

**DO**：记住 WebView2 无法真正做到 headless——`CreateCoreWebView2Controller` 需要父 HWND。
"后台视图"（隐藏 `WS_POPUP`、不带 `WS_VISIBLE`、1×1、自己的线程）已是无 AWT 的实际可用 headless 替代品。

---

## 12. 一个 environment 只能服务创建它的那条 UI 线程

**symptom**：拿在 WebView 线程上建出的 `ICoreWebView2Environment`，到**另一条** STA + 消息泵的线程上
`CreateCoreWebView2Controller` —— **稳定失败**，回调拿到 `hr=0x802A000C`。

这直接否掉了"可见视图建在 AWT 线程（父子同线程 ⇒ 不需要 `AttachThreadInput`）、后台视图留 WebView 线程"的架构：
两边必须共用同一个 environment（cookie 按 user data folder 分，两个 environment 就是两套登录态，且不能共用同一 user data folder）。

⇒ 「同线程」只有一种走法：**所有视图都放在同一条 UI 线程上**，要父子同线程那条线程就得是 AWT 的 EDT——代价是后台任务回调（含每个子资源拦截）全落 UI 线程。

**fix（DO/DON'T）**：
- **DO**：本工程当前取折中——所有视图都在自研 WebView 线程上，可见视图与父窗口**不同线程**，输入队列靠第 9 节的**长期 `AttachThreadInput`** 接起来。**不要为了省掉那次 attach 去动线程模型。**
- **DON'T**：在 `hr=0x802A000C` 仍是结论前重试「可见视图上 AWT 线程」。若哪天跨线程建控制器开始成功（`hr=0`），说明新版 WebView2 放宽了限制，那时再评估。

---

## 13. 拦截回调：「命中」与「放行」是互斥的两种终局

**symptom**：写了「命中 → 再放行」的时序断言，真引擎里挂死。

**what to check**：`cxx/webview/webview.cpp` 的 `RequestHandler::Invoke` 里，**一旦**回调返回命中就立刻
置 `view->settled = true` 并调 `webview->Stop()`；而同一个 `Invoke` 的**开头**就有
`if (!view || view->settled) return S_OK;` —— 也就是说**命中之后连回调都不会再被调一次**。
宿主侧还有第二道闸：`BackgroundWebView.kt` 的 `outcome.complete` 幂等、**只取第一个终态**。
两道闸叠起来 ⇒ 「命中」与「放行」是**互斥的两种终局**，命中了就不可能再放行。

**fix（DO/DON'T）**：
- **DON'T**：别写「命中 → 再放行」这种时序 —— 它在真引擎里不可达。
- **DO**：要验「放行」就一次都别命中——接了回调、每次都返回 `null`、只记录不复议，页面照样跑完（回调在每次请求上都被调不止一次，这本身也是「放行不影响加载」的证据）。回归在 `WebViewHostTest.releasingEveryRequestLetsPageFinish`。
- **DO**：写这类时序断言前先问——真实现里这条时序存在吗？别把桩（桩里的手攥 `CompletableDeferred` 有那道闸门，真实现没有对应物）的时序翻译成真引擎版。

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
   然后在 **`SetParent` 时**对 `GetAncestor(host, GA_ROOT)` 的线程做一次**长期**
   `AttachThreadInput`，接上不摘 —— 这一步就是全部修复（实测结论，见 §9 开头），不需要
   `MoveFocus`、不需要重试定时器、不需要任何脚本注入。
9. 从应用**外部**验证焦点，不装任何页面钩子：找到宿主的 `Chrome_WidgetWin_0`，
   在它屏幕中心 `SendInput` 一次真点击，然后对窗口树里每个线程调 `GetGUIThreadInfo(tid)` 读
   `hwndFocus`。是 `Chrome_WidgetWin_1` 说明键盘去了页面。
   对照 `GetGUIThreadInfo(GetForegroundWindow() 的 tid)` —— **那个**队列才是收按键的。
   `GetFocus()` 和 `GetActiveWindow()` 都做不到（都按线程），跨进程证明不了任何事。
10. 别指望「可见视图建在 AWT 线程、父子同线程」这条路 —— 一个 environment 只能在创建它的
    那条 UI 线程上建控制器（实测 `hr=0x802A000C`），见第 12 节。
11. **拦截回调的「命中」与「放行」是互斥的两种终局** —— 想验「放行」就一次都别命中，
    见第 13 节。
