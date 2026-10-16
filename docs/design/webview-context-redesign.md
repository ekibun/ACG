# WebView 平台契约：WebviewContext

`WebView` 的平台契约层（接口形状、工厂、后台驱动归属）。这是**已落地的契约口径**，
动 webview 契约层之前读本文，避免把已裁定的设计重新打开。具体实现以源码为准
（`platform/.../web/WebView.kt` 与 `soko.ekibun.web` 下的平台实现、`shared/.../engine/JsEngine.kt`）。

与 `docs/design/architecture-design.md` §4「前后台同实例两种可见态」的关系：那条不变，本文只规定
它下面的平台契约层。

## 契约要点（D1–D10 裁定）

| # | 决策点 | 裁定 |
|---|---|---|
| D1 | headless 模式放哪 | 工厂可选参 `createWebviewContext(isHeadless: Boolean = false)`，接口不放 `setHeadless`（创建期决策，不是运行时旋钮） |
| D2 | 后台驱动落点 | 并入 `shared/.../engine/JsEngine.kt`（同文件 top-level），驱动只被 JsEngine 消费 |
| D3 | `WebViewConfig.allowNewWindow` / `zoom` | 删（无调用方改过值；`WebScreen` 显式设的 `false` 就是默认值） |
| D4 | 接口 `navigate` 带不带 `method` | 不带（Android 侧只有 GET；POST 走 `postUrl(url, bytes)`，没有 headers + method 组合；桌面调用方全传 `"GET"`） |
| D5 | Android `Context` 来源 | `lateinit var hostApplicationContext`（`platform` androidMain 根，进程级共用）+ 宿主（`MainActivity.onCreate`）注入一次；漏注入是宿主编程错误，`lateinit` 当场炸，平台层不兜底 |
| D6 | `evaluate` 默认实现 | 不留（两端都真实现，不为不存在的平台写兜底） |
| D7 | 测试迁移 | 任务级用例随驱动进 `shared/jvmTest`；直构平台类的留 `platform/jvmTest`（`internal` 跨模块不可见） |
| D8 | 接口函数形态 | 接口上的函数全部 `suspend`（`setUserAgent` / `setDevtoolsEnabled` / `navigate` / `evaluate` / `reload` / `stop` / `goBack` / `goForward` / `openDevTools` / `dispose`），无例外——每条命令落到归属线程并返回，`submit` 那种 fire-and-forget 报不出「已落实」 |
| D9 | 两个回调槽的形态 | `var onEvent` / `var onInterceptor` 两个可变属性，不是 suspend 函数、也不是 setter（回调是「每次任务各一份」的槽位，赋值是纯字段写） |
| D10 | 拦截回调签名 | `var onInterceptor: ((url: String, headers: Map<String, String>, method: String, isForMainFrame: Boolean) -> Boolean)?`（返回 `true` = 命中）；删除 `WebViewInterception` 与 `WebViewRequest`。`isRedirect`（Android 独有）不上接口；`method` / `isForMainFrame` 两端都给得到 |

## 接口形状（platform commonMain，`soko.ekibun.web`）

- 一个接口 `WebviewContext`，可见页与后台任务共用同一类型（差别只在调用方怎么用）。
- 所有函数都是挂起的：每条命令落到**归属线程**上（桌面 = WebView 宿主线程，Android = 主线程）执行完再返回。
- `onEvent` 与 `onInterceptor` 从归属线程上调进来：接收方自己保证线程安全，且**不要在回调里同步调回本接口的挂起方法**（桌面会钉住宿主线程消息泵；后台驱动里 `evaluate` 必须丢到独立 scope 上）。
- 两个槽位都是**创建之后、首次导航之前一次性设好**的状态，**不需要 `@Volatile` 之类的同步**。
- `evaluate` 返回结果的 JSON 字符串；没有返回值或失败都是 `null`（不是 `"null"`——后者是 WebView2 对 `undefined` 的序列化）。

## 实现连带约束

> **DO:** jvm 侧的 `submit { xxx(ptr) }` 全部换成 `withPtr { ptr -> xxx(ptr) }`（`Pointer.withPtr` 在归属线程上执行、带句柄，语义是「投递并等它落地」）。例外：`attach` / `detach` / `fitToParent` / `setBounds` / `focus` 保持非 suspend——它们不上接口，且调用点全在没有协程上下文的 AWT 回调里。

> **DON'T:** 给 `onEvent` / `onInterceptor` 加 `@Volatile` 或切线程——它们是「创建之后、首次导航之前一次性注入」的槽位，不是运行期反复改的状态。

> **DO:** `dispose()` 变 suspend 后，两处「没有协程上下文 / 已被取消」的收尾点单独处理：驱动超时进 `finally` 时协程已被取消（必须 `withContext(NonCancellable)`）；组合的 `onDispose` 里没有协程上下文（必须丢到不被取消的进程级 scope 上）。

## 后台驱动（D2）

- 后台任务骨架在 `shared/.../engine/JsEngine.kt`，由 JsEngine 驱动；每端只出一个实现类。
- JS 侧引导脚本（`init.js`）的请求拦截形态跟着 D10 改：原生回调本来就是「给不给命中」的布尔判断（桌面 `onWebResourceRequested` 返回 bool、Android 用返回值决定）。

## native 联动（D10 的硬代价）

- 拦截回调返回值语义从「返回请求对象」改成「返回布尔命中」时，native 符号与 JNI 导出随之调整——改 `onWebResourceRequested` / `shouldInterceptRequest` 路径时一并核对 `cxx/webview/webview.cpp` 的 JNI 表，不要只改 Kotlin 侧。
- 「命中」与「放行」是互斥的两种终局：想验「放行」就一次都别命中。

## 实现顺序与验证

1. 接口与工厂（`WebviewContext` / `createWebviewContext`）先定，再动两端实现。
2. 后台驱动搬进 `JsEngine.kt`，确认任务级用例随驱动进 `shared/jvmTest`。
3. native 拦截回调签名按 D10 调整，全量跑 `:platform:jvmTest` 与 `:shared:jvmTest` 的 WebView 套件确认命中/放行语义未破。
