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

## 上下文实现的硬约束（jvm）

- 后台实例视口固定 `1×1`：`0×0` 时 WebView2 不加载页面、也不执行脚本，命令全部「成功但无效果」。
- environment 在 native 侧进程内共享，**同一 user data folder 上不允许并存多个**；cookie 的跨上下文可见性建立在这一点上。
- 动态库加载顺序固定为 `WebView2Loader` → `jawt` → `webview`：`webview.dll` 的导入表里有 `jawt.dll`，先加载才能让 loader 在「已加载模块」里解析到它。
- `releaseEnvironment()` 只由 `Runtime.addShutdownHook` 调用，产品层不显式调；静态析构阶段 STA 可能已失效，无界 `join()` 会让进程退不出去。
- `--disable-gpu-process-crash-limit` 是 `BROWSER_ARGUMENTS` 的唯一内容：GPU 进程反复崩时，默认策略会判「GPU 不可用」并终止整个浏览器进程，所有视图一起失效。
- 安全降级参数（`--no-sandbox` / `--disable-gpu-sandbox`）只在诊断时经环境变量 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` 注入，不写进 `BROWSER_ARGUMENTS`。
- `processFailed` 的 `kind ∈ {2, 4, 5, 6}` 不上报为 `LoadFailed`；其余上报。
- 脚本执行超时 15 s、cookie 读写超时 5 s；超时按失败处理（`evaluate` 给 `null`，读 cookie 给空串）。

> `evaluate` 的「没有返回值」两端都不是 Kotlin `null`，而是**字符串 `"null"`**（WebView2 把 `undefined` 序列化成 JSON null，`evaluateJavascript` 同形）。上文「接口形状」里「不是 `"null"`」一句按此为准。

> 拦截命中的两端口径不同：桌面由 native 回空 204 并 `Stop()`，Android 照常放行、只把结果交给回调。驱动只取第一个终态，可见行为因此一致；按「命中会中止加载」写脚本会在 Android 上失效。

## 宿主 Context 与外部浏览器

- `hostApplicationContext` 必须在宿主 `setContent` **之前**注入：`createWebviewContext` / `launchExternalBrowser` 都在命令式调用点取它，没有组合上下文，拿不到 `LocalContext`。漏注入是 `lateinit` 直接抛。
- 从进程级 Context 起 Activity 必须带 `FLAG_ACTIVITY_NEW_TASK`，否则系统当场抛。
- 桌面端 `launchExternalBrowser` 先查 `Desktop.isDesktopSupported()` 与 `Action.BROWSE`，headless JVM 上直接抛 `IllegalStateException`。
- Android 没装支持 Custom Tabs 的浏览器不需要兜底：`CustomTabsIntent` 本质是一条 `ACTION_VIEW` Intent，系统会派给默认浏览器。

## cookie 库

- WebView2 的 `ICoreWebView2CookieManager` 属于 profile，同 environment 下所有 webview 共享；写下的 cookie 对新建的独立 webview 同样可见。
- `SameSite=None` 必须同时 `Secure`：`AddOrUpdateCookie` 没有返回值，缺 `Secure` 时 WebView2 静默拒收，症状是「调用全正常但永远读不到」。
- 域要去掉前导点（`CreateCookie` 不认）；域 / 路径缺省时按 `url` 推，与 Android `CookieManager` 口径一致。
- `Max-Age` 优先于 `Expires`；两者都没有即会话 cookie。
- `Expires` 三种格式（RFC 1123 / RFC 850 / asctime）先剥时区后缀；星期前缀不能无条件先剥——非 asctime 格式缺星期几时剥掉的会是月份，整条 cookie 会降级成会话 cookie。
- HTTP 层没有 cookie 仓库：要带登录态必须由 JS 侧读出 cookie 写进请求头。Android 后台页的 `shouldInterceptRequest` 用 `headersWithCookie` 把系统 `CookieManager` 的 cookie 折进回调 headers，否则脚本按 url 再 `fetch` 一次时登录态是断的。

## 回环回调服务器

- 服务器是**进程级单例**，端口由 OS 分配（`bind(0)`）；会话靠 URL 路径区分，其余路径回 404，多登录并行不串扰。
- 只认 origin-form（`GET /<sessionId>?…`）：回环地址在浏览器里默认不过代理，经代理的绝对形式到不了这里。
- `awaitCallback` 原样返回 request-target 接上 origin，**不做百分号解码**：整串解会把参数值里的 `%26` 解成 `&`，把参数切开。
- `callbackUrl` 无查询串、无尾斜杠：换 token 时 `redirect_uri` 必须与授权请求里逐字相同。
- 回调响应带 `Cache-Control: no-store`（地址里含授权码）。
- `accept` 抛 `IOException` 即退出 accept 循环，服务器不再接受新连接；单条连接异常只结束该连接。
- `close()` 幂等：撤掉会话后同一地址回 404，未兑现的 `awaitCallback` 随之取消。

## HTTP 响应流

- `Http.request` 交给调用方的是一条活着的连接，**必须 `close()`**，否则连接不释放。
- `read` 与 `readAll` 不能混用：`readAll` 读完即关，之后再 `read` 只剩流尾。
- `contentLength` 未知时是 `-1` 而不是 `0`：`0` 会被 ffmpeg 当成零长度流，后续 seek 全判越界。
- `read` 返回原始字节数、不做 EOF 翻译（翻译在 `HttpIO.read`）：返回 `0` 会被 aviobuf 当成「读空，继续要数据」。
- 在已关闭的响应上再读抛 `IllegalStateException`，不是 EOF。
- `close()` 必须任意线程可调且幂等：JS 侧丢弃对象时 native 析构回调会替 JS 调它。
- 协程取消会 `cancel()` 掉底层 call 并把响应标记为已关闭；这条会话当场作废，下次读必须重开。
- 本层不装 `cookieJar`：cookie 完全由 `headers` 决定，不增不减。
- okhttp 要求方法与 body 匹配：`GET`/`HEAD` 不带 body，`POST`/`PUT`/`PATCH` 必须带一个（空 body 也要给 `ByteArray(0)`）。
- `FormData` 的项键名必须与 `init.js` 的 `_formItem` 逐字一致（`filename` 为小写 l）；内容类型解析失败留空，不让坏 `MediaType` 炸掉整条请求。

## 本地文件、路径与设置

- `openFileHandle` 不抛异常：它由 native 的 `io_open` 回调间接调起，异常穿过 JNI 后 native 仍会用返回值，表现为更难查的崩溃；失败统一让读取方返回 `-1`。
- `FileHandle` 是随机访问句柄，同一次打开内反复 `read` / `seek`；`read` 到末尾返回 `-1`。
- `Paths.init` 只能成功一次，且必须早于 `settingsStore` 首次读取与 WebView2 首次建上下文；未初始化就读取抛 `UninitializedPropertyAccessException`。
- DataStore 禁止同一文件上并存两个实例（各自缓存快照、互相覆盖），所以 `settingsStore` 是进程级单例。
- 删键用 `prefs -= key` 而不是 `remove(key)`：后者会把被删的值 `as T` 回给调用方，泛型擦除后没有保证。

## 编码与时钟

- `encodeTextToBytes` 遇到不认识的 charset 必须抛，不兜底成 UTF-8：静默换编码会让对端按声明的编码解出乱码却不报错。
- `monoTimeMs` 只保证两次读数之差有意义，不用于表达日历时间。
