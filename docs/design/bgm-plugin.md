# Bangumi 插件与收藏页的形态依据

> **状态（2026-11-09）：登录已改成站内网页登录** —— 插件自己用 `webview` 打开 `https://bgm.tv/login`，
> 登完把 cookie 收成账号会话交回宿主；`bgm.js` 不再读 `Settings`，本文的 §二 键归属与 §三 OAuth
> 都成了历史，现行做法见 [shared-layer §5.9](../development/shared-layer.md)。
>
> **状态（2026-11）：** Kotlin 侧的实现（`BgmSource`、收藏页、`CollectionState`）已删除，重写时按本文的
> 接口与分页结论来。插件脚本 `files/js/module/bgm.js` 仍在，下文描述的是它。

第一个**真实的**插件：`files/js/module/bgm.js` + Kotlin 侧的 `BgmSource` + 收藏页。
这份文档记的是分层裁定、键空间归属、OAuth 与 v0 接口的实测结论。改 `bgm.js` 的导出面、
改 `BgmSource` 的常量或字段映射、改收藏页的前先读本文；源码里只留裸约束与指针。

## 一、谁放什么

| | 放哪 | 为什么 |
| --- | --- | --- |
| 服务商知识（端点、参数名、OAuth 步骤、UA 串） | `files/js/module/bgm.js` | 全是字符串拼装，本来就是 JS 的活；换服务商只换一个文件 |
| 应答 → 强类型 | `BgmSource`（`shared/.../acg/source/`） | JS 侧拼串**没有编译期检查**，形状变化只会在运行期暴露；收口成 data class 后界面拿不到裸 `Map` |
| 界面状态与翻页 | `CollectionState` | 跨次点击的东西（登录态、当前筛选、翻到第几页），与 Compose 的重新组合无关 |

`BgmSource` 里**没有任何 HTTP 调用**：它只经 `JsEngine.callPlugin("bgm", …)` 走插件，网络出口
全在插件的 `fetch` 上。这样「换服务商」与「换 HTTP 栈」互不牵连。

> **DO:** 折字段时把「缺字段 / 类型不符」一律当**没有**（`BgmSource` 里的 `asStringOrNull` /
> `asLongOrZero`）。接口加字段是常事，为它把整页打不开不划算。只有「整个应答根本不是个对象」
> 才算真出错、才抛。数字要**同时认 `Long` 与 `Double`**：桥按 JS 内部标签分派（`JS_TAG_INT` →
> `Long`、`JS_TAG_FLOAT64` → `Double`），而 `JSON.parse` 对 `7` 与 `7.0` 给的标签不同 ——
> 同一个字段两次请求可能落到不同分支。

## 二、键空间的归属

设置是一张扁平的 `String → String` 表（[能力桥](./quickjs-ability-bridge.md) 的 `Settings`）。
三个键：

| 键 | 谁写 | 谁读 |
| --- | --- | --- |
| `bgm.client_id` / `bgm.client_secret` | **宿主**（收藏页的输入框） | 插件 |
| `bgm.session` | **插件**（换到 token 之后） | 插件 |

> **DO:** 守住这条归属。宿主写凭据是因为那个值来自界面、且要在登录**之前**就落盘；插件写
> 登录态是因为 token 的形状是 OAuth 的事、宿主不该知道。两边各写一份键名字面量 —— 用一条跨界的
> 用例钉住（原 `PluginHostTest.readsBackTheSessionItStored` 已删，现在没有自动覆盖，只能靠 `debugCode`
> 手工跑一次 bgm 源核对键名）。宿主侧只有凭据两个键有 Kotlin 常量。

> **DO:** 凭据缺任一个都当没配（`BgmSource.credentials()`）—— 少了 `client_secret` 换不到 token，
> 报错要报在用户填的地方，不要等浏览器弹出来再失败。

## 三、OAuth（实测）

授权码流程，`client_secret` 是**必需的**（不是公开客户端）。端点：

| 用途 | 端点 |
| --- | --- |
| 授权（外部浏览器打开） | `https://bgm.tv/oauth/authorize` |
| 换 token / 刷新 | `https://bgm.tv/oauth/access_token` |

回环回调的形态与「为什么是回环」见 [browser-login.md](./browser-login.md)（那里也记了
**换 token 的请求体是表单**这条）。本插件独有的几条：

- **`redirect_uri` 回传 `callbackUrl.split("?")[0]`**，即回调地址去掉查询串 —— 必须与授权请求里
  那个值逐字相同（服务商在换 token 这一步校验）。
- **`state` 自己出随机串再比一次**。回环服务器只绑 `127.0.0.1`、会话 id 又是宿主出的 24 位随机
  路径，别人本来也够不着这次回调；这一步是别让**手边的**页面把回调打进来。
- **token 有效期 604800 s（7 天）**，附 `refresh_token`。
- **换 token 失败要把服务端的报错原文带出来**（`oauth token request failed (400): …`）。
  凭据填错与回调过期在界面上是同一句话「登录失败」，不带原文就只能靠猜——实测填错
  `client_secret` 得到的是 `{"error":"invalid_client"}`，一眼能认出来。

> **DON'T:** 在引擎里做 token 交换或刷新。引擎里一条服务商相关的东西都没有（`client_secret`
> 也不进本仓库）。token 过期后的重新授权就是再调一次 `openBrowser`；主动刷新（`refresh_token`）
> 尚未做，见 §六。

## 四、v0 接口的实测结论

| 结论 | 证据 |
| --- | --- |
| **每个请求都必须带 UA**，格式 `<dev>/<app>/<version> (<url>)` | `ekibun/acg/0.1 (https://github.com/ekibun/ACG)` → 200；不带 → 403 `Please set a 'User-Agent'` |
| **网页端按 UA 认会话**：WebView 与 fetch 必须是同一个 UA | 同一份 cookie（从 WebView2 的 Cookies 库里解出的 `chii_auth`）：WebView2 的 UA（Edge 157）→ 登录页，`class="avatar"` 命中；换成 Chrome 131 或 `ekibun/acg/…` → 只有登录/注册。所以**这个串由宿主统一兜底**（`DEFAULT_USER_AGENT`，`webview` 与 `fetch` 默认都是它），`bgm.js` 不再自带 UA —— 参考实现也是把 `HttpUtil.ua` 同时设给 `WebSettings.userAgentString` |
| `/v0/me` 要 `Authorization: Bearer <token>` | 假 token → 401 |
| 收藏接口对公开用户**不需要鉴权** | 裸 `GET /v0/users/{u}/collections` → 200（本插件仍带 token，它要读自己的收藏） |
| `type` 是**整数 1..5**（想看/看过/在看/搁置/抛弃） | 不是 Bangumi 安卓客户端那套 `wish` / `do` 字符串 |
| `subject_type` 是 **1/2/3/4/6**（书籍/动画/音乐/游戏/三次元），**没有 5** | 「人物」那档已废 |
| 封面在图床 `lain.bgm.tv`，`images.grid` 是 `/r/100/` 那档（100px） | 直连 200、0 次跳转；列表里就是小图，取 `large` 白等一趟大文件 |
| `limit` 上限 50 | 取满省往返 |
| **数字 uid 能当网页路径段**，服务端 302 到用户名页 | `/user/1` → 302 `/user/sai`；`/anime/list/1/collect` → 302 `/anime/list/sai/collect`。别拿 `/v0/users/1` 反推 —— v0 只按 username 查，报的是 `can't find user with username "1"` |
| **账号 id 取 username**，不存数字 uid | 用户名本身就是收藏地址的路径段（`/anime/list/<user>/collect`）；uid 只多一跳 302 归一，存下来没有用处 |

收藏一项的形状是 `{subject: {…}, subject_id, type, rate, ep_status, …}`：条目的名字、封面、评分
挂在 **`subject`** 里，而**用户自己的进度**（`ep_status`）在**外层** —— 两边都要读，
`BgmCollectionPage` 折的就是这一件事。

## 五、界面为什么不是参考实现的形状

版面照 `.workbuddy/ref/Bangumi` 的 `fragment_collection.xml`（顶部一排筛选、下面一排列出条目类型、
再下面列表）。差异只有一处：参考实现用 `ViewPager` 让**每种条目类型一个 `Fragment`**，
各自的 `loadCollectionList` 持有一份列表；这里用**一份 `CollectionState` + 页签切换**。
理由是翻页、并发应答作废、登录态这些逻辑只写一遍——`ViewPager` 版要写五遍，而五份之间
唯一的差别是构造参数。

> **DO:** 筛选换得快时把**过期的应答**丢掉（`CollectionState` 的 `generation`）。界面上换筛选是
> 连点就能触发的，两轮请求会同时在飞；没有这道闸，先发的后回就把后发的挤掉，页面停在已经不被
> 选中的那个筛选上——**不报错，只错内容**。

- **收藏状态没有「全部」这一档**，条目类型也没有。接口按这两个维度分页，留空不等于全部；
  真要「全部」得把五档串起来，而那是跨五个分页游标的事，不是省一个参数。
- 封面用 `RemoteImage`：走 `Http` 拉字节再 `ByteArray.decodeToImageBitmap()`——共享层没有平台
  符号，而解码入口两端各有 `actual`（Android `BitmapFactory`、桌面 skiko），这是本层唯一能用
  的那个。它带一层**进程级 LRU**（8 MiB，`sizeOf` 按解码后的 `宽 × 高 × 4` 计）：`LazyColumn`
  滚出去的节点会被销毁、滚回来重建，没这层就是每滚一轮把整屏封面重下一遍。预算必须有上限 ——
  5 种收藏状态 × 5 种条目类型，无界表会累积成几百 MB；失败**不**入表（一次瞬时失败不该粘住）。
- 评分不用 `"%.1f".format()`：那是 JVM 的，`commonMain` 里没有。

## 六、没做的

- **不做 token 刷新**：7 天过期后要用户重新授权（再走一次 `oauth`）。做刷新要先有
  定时/失败重试的位置，现在没有。
- **不做收藏的增删改**：这一版只读。
- **不做条目详情页**：列表点进去还没有地方可去。
- **限流没做退避**：社区经验值 ~3 req/s，没有正式文档。当前只有「人手动点筛选/翻页」这一种
  节奏，够不着限流。
- **`limit` 固定 50、逐页追加**：没有「一次拉完」的路径。

## 七、测试面

| 验什么 | 在哪 |
| --- | --- |
| 能力桥的真派发：`args` 形状与返回值、`Settings` 读写删、`fetch` 的请求侧（方法 / Content-Type / body）与响应侧、`FormData`、`oauth` 模板校验与回跳编排 | `shared/src/jvmTest/.../engine/JsEngineTest.kt` |
| WebView2 宿主端到端：拦截回调看得见子资源、脚本结果拿得回、cookie 跨任务共享 | `shared/src/jvmTest/.../engine/JsEngineTest.kt`（末尾三条） |
| 任务注册表：前台互斥、后台倒计时、关闭兑现 null、注销 | `shared/src/jvmTest/.../engine/WebviewTaskTest.kt` |
| 标题栏状态从平台事件折出来（`reduce`） | `shared/src/jvmTest/.../engine/WebviewTaskTest.kt` |
| 应答 → 强类型（整数/浮点标签、缺字段、条目缺 `subject`） | `shared/src/jvmTest/.../source/BgmSourceTest.kt` |
| 设置落盘本身（DataStore、父目录、删除） | `platform/src/jvmTest/.../common/SettingsStoreTest.kt` |

**没有**自动覆盖的：插件的 `import()` / `callPlugin` 真实通路（原 `PluginHostTest` 已随 jvmTest 精简
删掉，现在只靠 `debugCode` 里手工跑一次 bgm 源）、真跑一遍 OAuth（要人在浏览器里点）、收藏页的界面。
OAuth 只能人工走一遍；
后者的验证方式是桌面端起应用、切到「收藏」页签（`CollectionScreen` 的 `load()` 会落到
`settingsStore`，出现 `%APPDATA%\ekibun\ACG\settings.preferences_pb` 即「组合 + 设置链路通」）。
