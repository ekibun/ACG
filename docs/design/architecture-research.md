# 调研与设计纪要（2026-10-02 会话）

本次会话的完整调研记录：nezumi 对比、Bangumi/AniList API、animeko 深度分析、
数据结构设计、订阅格式兼容评估、后台 WebView 投前台方案。
精简结论见 [architecture-design.md](./architecture-design.md)。

---

## 0. 参照仓库（`.workbuddy/ref/`）

| 仓库 | 是什么 |
| --- | --- |
| `nezumi/` | 当前项目的前身（Flutter 原型，1 个 commit），含 5 个 submodule 插件（已浅克隆） |
| `BangumiPlugin/` | ekibun 的插件框架 + JS 数据源测试脚本（video/book/music 三类站点的契约样例） |
| `bangumi-onair/` | ekibun 的生产版 bangumi 安卓应用（`Subject`/`Episode`/`LineInfo`/`ProviderInfo` 模型的出处） |
| `animeko/` | open-ani 的 KMP 番剧应用（2822 个 Kotlin 文件），三轮分析的对象 |

---

## 1. nezumi 比当前项目多实现了什么

nezumi 是"薄引擎 + 完整业务 UI"，当前 ACG 是"厚引擎 + 3 个调试页"。

| 功能 | nezumi 落点 | 说明 |
| --- | --- | --- |
| 业务 UI 五页 | `lib/page/` | 主页收藏网格 / 搜索 / 条目详情 / 播放 / 设置；ACG 只有 Play/Web/Code |
| 数据持久化 | `lib/store/` | Bencode 序列化 + 防抖 flush、收藏 DB、下载 DB、设置；ACG 零持久化 |
| 用户脚本数据源 | `lib/engine/engine.dart` | 热加载 `source/*.js`，契约 `search`/`getSubjectInfo`/`getEpisodeList`/`getEpisode`；条目多源 |
| 边播边缓存 | `lib/io/video.dart` | 播放时写盘 + 已下载区间表 + seek 优先读缓存；只有"边播边写"，无下载队列 |
| JS 侧 HTML 解析 | `lib/engine/wrapper.dart` | `HtmlParser`（query/queryAll/attr/text/html/remove）——ACG 的 `init.js` **没有** DOM 解析，是 JS API 面唯一实质缺口 |
| 多语言 | `lib/generated/` | en/zh + 跟随系统 |
| 主题 | `lib/store/app.dart` | 6 色主题 + 亮暗模式 |
| torrent 插件 | `plugins/torrent/` | 磁力 + ut_metadata + HTTP tracker + BEP 3/6/10；**库完整但从未接入**（app 只用了它的 bencode） |

打平：fetch/FormData、离屏 WebView 抓取、CryptoJS（47KB 单行）、编码转换。
ACG 反超：逐帧步进、人声消除、可见浏览器页、JS 控制台、完整可用的 Windows 端。

搬运优先级：持久化层 + 收藏 DB → 数据源契约 + HtmlParser → 业务四页 → 边播边缓存 → torrent（上游自己都没接）。

---

## 2. Bangumi v0 API 要点（https://bangumi.github.io/api/）

- base `https://api.bgm.tv`；**必须自定义 User-Agent**；限流无正式文档，社区经验 ~3 req/s，429 退避。
- 读接口可匿名（看不到 NSFW/私有收藏）；写接口 Bearer + scope。
- `Subject`：`id` / `type`(1=book 2=anime 3=music 4=game 6=real，**无 5**) / `name` / `name_cn` / `date`("YYYY-MM-DD" 可空) / `images`(large/common/medium/small/grid 五档) / `summary` / `platform`(自由串：TV/OVA/剧场版…) / `nsfw` / `locked` / `volumes` / `eps` / `total_episodes` / `rating{rank,total,score,count}` / `collection{wish,collect,doing,on_hold,dropped}` / `tags[{name,count,total_count}]` / `infobox`(数组，值是 string 或 `[{v}]`/`[{k,v}]`)。
- **剧集列表走 `GET /v0/episodes?subject_id=`**（不存在 `/v0/subjects/{id}/episodes`）。`Episode`：`id` / `type`(0=本篇 1=SP 2=OP 3=ED 4=PV 5=MAD 6=其他) / `sort`(Double，对齐线路的关键值) / `ep` / `airdate` / `duration_seconds` / `disc`(音乐碟片) / `desc`。
- 收藏 `CollectionType`：1=想看 2=看过 3=在看 4=搁置 5=抛弃。`UserSubjectCollection`：`rate`(0-10，0=未评) / `ep_status` / `vol_status` / `comment` / `private` / `tags`。
- **per-episode 进度存在**：`GET/PATCH /v0/users/-/collections/{subject_id}/episodes`，`EpisodeCollectionType` 0=未收藏 1=想看 2=看过 3=抛弃。
- 搜索 `POST /v0/search/subjects`（**官方标注实验性**）：`{keyword, sort(match/heat/rank/score), filter{type[], meta_tags, tag, air_date, rating, nsfw}}`。
- 关联条目 `relation` 是**中文自由串**（book/anime、music 3001+、game 4002+ 三套 ID 组，spec 未收口）。
- OAuth：authorize 在 `bgm.tv/oauth/authorize`（code 60 秒有效），token 7 天 + refresh_token；社区有 refresh 失败 issue（bangumi/api#203），要做重新授权兜底。**v0 不允许 query string 传 token**。
- 坑：收藏的 `updated_at` 有官方承认的 bug（改评分/进度可能不更新），同步别依赖它。
- 每日放送：legacy `GET /calendar`（v0 之外唯一官方在册的非 v0 读接口）。

## 3. AniList API 要点（https://docs.anilist.co/）

- GraphQL，仅 POST `https://graphql.anilist.co`；公开数据免认证；token 1 年无 refresh。限流当前降级 **30 req/min**（正常 90），按 `Retry-After` 退避。
- `Media`：`title{romaji,english,native,userPreferred}`（**无中文**）/ `coverImage{extraLarge,large,medium,color}` / `format`(TV/TV_SHORT/MOVIE/SPECIAL/OVA/ONA/MUSIC/MANGA/NOVEL/ONE_SHOT) / `status` / `season`+`seasonYear` / `episodes`(只是总数，**没有剧集表**) / `duration` / `startDate`/`endDate`(FuzzyDate{year,month,day} 全可空) / `averageScore`(0-100) / `isAdult` / `synonyms` / `relations`(MediaRelation 14 值稳定枚举) / `streamingEpisodes{title,thumbnail,url,site}`(官配外链) / `nextAiringEpisode`/`airingSchedule{airingAt,episode}`(Unix 秒)。
- `MediaList`：status 6 值（CURRENT/PLANNING/COMPLETED/DROPPED/PAUSED/REPEATING）/ `score`（按用户 `scoreFormat` 口径，`scoreRaw` 才是 0-100）/ `progress` / `progressVolumes` / `repeat` / `private` / `notes`。写走 `SaveMediaListEntry`（传 id 更新 / mediaId 新建）。
- 分页：`PageInfo.total` 官方明示不可靠，分页只依赖 `hasNextPage`。
- 对设计的意义：`FuzzyDateInt` 是 YYYYMMDD 补 0——与 animeko 的 `PackedDate`（Int 位打包年月日）同构，两方独立实现选了同一种形状。

---

## 4. 数据结构设计（结论沉淀到 architecture-design.md）

三个让设计简化的关键发现：

1. **集数元数据只有 Bangumi 有**（AniList 无 per-episode 表），领域 `Episode` 照 Bangumi v0 形状建即可。
2. **播放线路与元数据是两个域，靠 sort 数值对齐**——onair（生产版）已验证：`Episode.sort + Line.extra` 偏移生成 URL，用户手动绑定；nezumi 与 BangumiPlugin 的 JS 契约都是变体。**URL 不持久化**。
3. **主键不能是裸 id**：Bangumi/AniList 的 Int 会撞号，JS 源 id 是 `"20090010/1"` 这类串 → `SubjectRef(site, id: String)`。

animeko 分析带来的 9 条修正（详见 §6）：
`EpisodeSort` sealed class 替代裸 Double；`PackedDate` 式 Int 打包日期；进度上报按 episodeId、sort 只做对齐；收藏状态加 `NOT_COLLECTED` 哨兵；同步用本地先行 + 覆盖式队列；JS 契约参考 `MediaFetchRequest` 的"整条目上下文"；保持直连 Bangumi（不走它的自建服务器路线）；手写 DTO 不引 OpenAPI generator；`MediaCacheMetadata` 按 subjectId+episodeId+episodeSort 挂回条目。

工程注意：
- 持久化时间戳需要墙钟 → 照 `monoTimeMs()` 粒度新增 `expect fun epochTimeMs()`（`MonoTime` 注释明示别拿单调钟表达日历时间）。
- 序列化建议引 `kotlinx-serialization-json`（当前**零序列化依赖**，改依赖需先确认）；文件分片存（collection/lines/history）。nezumi 的 Bencode 不沿用。
- 包落点：领域模型 `acg.model`，API 客户端 `acg.catalog.bgm`；**不要放进 `acg.engine`**（类名是对外契约，改了静默失效）；JS 消费元数据由宿主桥转 JS 值。

---

## 5. animeko 分析（open-ani/animeko）

### 5.1 模块地图与三域接口

```
datasource/api      SPI 与统一资源模型（Media/MediaSource/Topic/EpisodeSort/PackedDate）
datasource/{bt,web,ikaros,jellyfin,bangumi}  数据源实现
client              Ani 自建服务器 api.animeko.org 的 OpenAPI 生成码（现在的主数据通道）
app/shared/app-data 领域模型 + Room(v27) + DataStore + 同步/匹配/缓存
danmaku             dandanplay（按 Bangumi subjectId 直接拉剧集弹幕）
```

**客户端已不直连 Bangumi**，全部走自建服务器聚合（Bangumi+TMDB+bangumi-data）——换来了精确放送表（`recurrence "R/<start>/P7D"`）和搜索增强，代价是服务器运维。`datasource/bangumi` 的生成客户端只做连通性探测。

三域（互不依赖，运行时用 `MediaFetchRequest` 桥接）：
- **元数据域**：`SubjectInfo` / `EpisodeInfo{episodeId, type(含 OVA/OAD), sort: EpisodeSort, ep}` / `SubjectCollectionInfo`（聚合根，含每集状态 + 实时算出的 progress/airing）。
- **资源域 SPI**：`MediaSource{mediaSourceId, kind, fetch(MediaFetchRequest), browse 协议}`；`Media{download: ResourceLocation(MagnetLink/HttpTorrentFile/WebVideo/HttpStreamingFile/LocalFile), episodeRange: Single|Range|Season, properties(字幕组/分辨率/字幕语言)}`。
- **收藏域**：`UnifiedCollectionType` = Bangumi 5 态 + `NOT_COLLECTED`（删除动作哨兵）；Room 表 + **outbox 模式同步**（本地先行写 + pendingOp 队列 + 后台批量推送；5xx 保留重试、4xx 丢弃不回滚本地）。

### 5.2 匹配流程（无 episode↔media 持久化映射表）

`MediaFetchRequest{subjectId, episodeId, subjectNames 全别名, episodeSort, episodeEp, episodes 全列表}` → 各源并发 `fetch` → `MediaSelector` 用 `episodeRange.contains(sort||ep)` 过滤 + 按字幕组/分辨率偏好收窄 → 分阶段自动选（exact 优先，5s/15s 放开 fuzzy）。持久化只有 subjectId 粒度：`MediaPreference`（不记具体资源）+ `ManualBrowseMemory(mediaSourceId, 站点条目, channelIndex, episodeIndex, playedAsSort)`——回放第 t 集取列表第 `k+(t−p)` 项，**与我们 Line.extra 偏移思想同源，但它存"列表下标+锚点集数"**。

### 5.3 播放源解析链

`Media.download` 的定位类型 → `MediaResolver` 链按 `supports()` 认领 → `MediaData`（mediamp）：

- **BT**：`fetchTorrent`（anitorrent = libtorrent JNI）→ `selectVideoFileEntry` 四步选文件（扩展名过滤 → 黑名单词 `[SP]/OVA/PV/NCOP/NCED/OP\d+/ED\d+/CM` 沉底 → 集名唯一命中 → `RawTitleParser` 解析 episodeRange 先按系列 sort 再按季内 ep 匹配 → 兜底）→ `TorrentMediaData`：piece 级随机读 `SeekableInput` 边下边播。云端（PikPak）优先，15s 预算回退本地引擎。
- **Web**：`Media.download` 是**播放页 URL** → 离屏 WebView 加载、逐请求问 matcher（配置正则 `matchVideoUrlRegex`，命名组 `<v>` 提直链）→ 命中即中止加载 → `WebVideo(m3u8Url, headers{UA/Referer/Sec-Fetch-*})` → `UriMediaData`，**HLS 由播放内核自解**。前置 `WebSessionManager` 暖会话（cookie/UA 回灌 HTTP 客户端）。
- **直链**（Jellyfin/Emby/Ikaros）：直通内核，headers 为空。
- **内核**：Android = ExoPlayer（`VideoDataDataSource` 把 SeekableInput 包成 DataSource）；桌面 = **mpv**（`AniMpvMediampPlayerFactory`）。

### 5.4 桌面端 WebView（JCEF）

- **JCEF 走 JBR 自带模块**（打包 `modules("jcef")` + `--enable-native-access=jcef`），不是外部依赖；前提是整个应用跑在 JetBrains Runtime 上。
- 单例宿主 `AniCefApp`：初始化 8s 超时（失败附 CEF 日志尾部 30 行）；**lockfile 自愈**（删不掉换临时缓存目录）；代理走 `getAuthCredentials` 回调；**线程模型 = 所有 CEF 调用都在 EDT**（`runOnCefContext`/`suspendCoroutineOnCefContext`）；**退出防死锁**（EDT 上 System.exit 触发 shutdown 时 invokeAndWait 会互锁 → invokeLater + 5s 有界等待）；`DataSourceBrowserGate` 限制后台浏览器并发 8 个。
- 可见浏览器 `CefCaptchaBrowser`：`SwingPanel(factory = { browser.uiComponent })`；**UA 从真实请求头捕获**（onBeforeResourceLoad 读 User-Agent），失败才回落拼串。
- 离屏提取 `CefVideoExtractor`：临时 createClient+createBrowser，`onBeforeResourceLoad` 三裁决（FoundResource 拦截收工 / LoadPage 用 `executeJavaScript("window.location.href=…")` 跟进嵌套 / Continue 放行）。
- Linux 补丁：`--ozone-platform=x11`（Wayland SIGTRAP）+ `--use-angle=swiftshader-webgl`（Chromium 137 硬件 ANGLE 崩 GPU 进程）+ `--password-store=basic`。

### 5.5 数据源分发（"从哪来、什么语言"）

- **所有内置源都是 Kotlin、编译进应用**（mikan+mikanCN / rss / jellyfin / emby / ikaros / selector / dmhy via ServiceLoader），**没有插件机制、不运行用户代码**。
- 可扩展性靠两个"零代码"通用源：`SelectorMediaSource`（JSON 配置的网页视频源）与 `RssMediaSource`。
- **订阅**：`MediaSourceSubscription{url, updatePeriod=1h}` → 拉 `SubscriptionUpdateData{exportedMediaSourceDataList}` → 每项 `ExportedMediaSourceData{factoryId, version, arguments: JsonElement}`。纯 JSON、无加密无 gzip；**分发的是配置不是代码**。实例持久化 = `MediaSourceSave{instanceId, factoryId, config}`（DataStore）。
- 上游站点：dmhy.org（HTML 抓表）、mikanani.me（RSS + Bangumi ID 映射缓存）、api.animeko.org（元数据聚合）、自建 Jellyfin/Emby/Ikaros、dandanplay（弹幕）。

---

## 6. 订阅 URL 兼容评估（我们用 QuickJS 消费 Animeko 订阅）

**结论：可行且值得做。** "解析"没有障碍（纯 JSON），工程量在"解释执行"——但 schema 封闭且小。

`web-selector` 的 `arguments`（`SelectorMediaSourceArguments.searchConfig`）：
- 列表规则：`searchUrl` + `rawBaseUrl`（空则从 searchUrl 推导）+ 条目格式三选一（`"a"` 一条 CSS 选 `<a>` / `"indexed"` 名字、链接两条 CSS 按序 zip / `"json-path-indexed"` 两条 JSONPath）+ 线路格式二选一（`"no-channel"` 一条 CSS 选剧集 / `"index-grouped"` CSS 选线路名 + 正则取名 + CSS 选剧集）+ `matchEpisodeSortFromName` 正则提集号。
- 播放规则 `matchVideo`：两个正则（嵌套页 / 直链，命名组 `<v>`）+ `cookies` + `addHeadersToVideo{referer,userAgent}`。
- 行为参数：`requestInterval`(ms) / `searchCacheTtl` / `autoMatch` 7 开关（自动匹配层，第一期可整体关闭——我们走手动 Line 绑定）。
- `rss` factory 简单；dmhy/mikan 是代码不在订阅里。

原语映射：JSON→`JSON.parse` 微；CSS 选择器→**缺口**，给 `_binding` 桥加 `HtmlParser` 插件类（顺手补 nezumi 有的 DOM 解析）；正则→JS `RegExp` 几乎同源（命名组/负向前瞻原生支持）；JSONPath→他们只用了小子集，JS 几十行；播放期嗅探→`webview(url, header, script, onInterceptRequest)` 的 onInterceptRequest 就是"命中即中止"，嵌套页 = 再 `await webview(...)`。

注意：kotlinx-serialization 输出**等于默认值的字段可能整个缺省**，解析器必须自带默认值表 + 宽容解析（未知键忽略）；`version` 超范围跳过。
限制：验证码（我们后台页无 UI，引导去 WebScreen 过一次，cookie 共享后可用）、schema 漂移、站点改版漂移（与 Animeko 同病）。
支持顺序：`"a"` + `"no-channel"` 先行 → 播放链路 → 再补 indexed/json-path-indexed/index-grouped。

---

## 7. 后台 WebView 投前台（悬浮按钮方案，定案方向）

**核心决策：按任务一实例；前台只是同一个实例的另一种可见状态。** JS 契约不动、无 `HandedOver` 分支、无会话转移——悬浮按钮只是让任务正在用的 controller 变得可见，任务骨架照常等终态。

**不能进一步合并成"全局一实例"**：① `webview()` 是 Promise 非阻塞契约，多任务并发会互相导航砸页；② Android 后台 `dispose` 的 `clearCache(true)` 防串态，共享长驻实例后失效；③ 任务导航会把用户正在看的页面顶掉——"悬浮按钮不打扰前台"的前提就是两实例分离。

形态：
1. 引擎侧小注册表（taskId → controller + 元数据），飞行中任务可枚举。
2. 悬浮球挂 **App 级 overlay**（`App()` 里曾挂的 `BackgroundWebViewHost()` 已随 2026-10-13 的接口重设计删除，别锁死在 WebScreen）。
3. 显示 = **叠层**：任务的 `WebViewSurface` 铺在用户浏览页上面；两层都保持在组合里（桌面组合退出会 dispose 原生窗口，浏览现场会丢），靠叠放次序切换。
4. 超时规则：任务被前台显示期间 30s 计时暂停（或按导航/拦截活动顺延）——否则骨架超时 dispose 会销毁用户正看着的页面。

平台缺口（精确位置）：
- 桌面 Kotlin：[WebView.jvm.kt](../../platform/src/jvmMain/kotlin/soko/ekibun/web/WebView.jvm.kt) 原先记的 `controller as DesktopWebViewController` 强转 / `by delegate` 委托对象 ClassCastException **已随 2026-10-13 的接口重设计消失**（两类合并成同一个 `WebviewContext` 实现类）。投前台前还需补注册 `addViewListener`（`Callbacks.pending` 会补发早期事件）。
- 桌面 C++（小改，动 `cxx/` 需重编 dll 同步四处）——**这条是按写这份研究时的那版
  `webview.cpp` 写的，里面的行号已随宿主重写作废；重写后的 `webview.cpp` 没有
  `View.background` / `requestWebViewFocus`，`put_IsVisible`、`MoveFocus`、
  `SetParent` + `WS_CHILD` 原生已有**：`View.background` 标志摘除——不摘则 `requestWebViewFocus` 直接 return（webview.cpp:929，**验证码打不了字**）、NavigationCompleted 后台分支会提前 resolve 脚本 promise、创建时没走 `put_IsVisible(TRUE)`（webview.cpp:1944-1950）。`nativeViewAttach` 本身对任何视图通用（webview.cpp:2621，SetParent+WS_CHILD+输入队列接续+ShowWindow）。
- Android：近零成本。`AndroidWebviewContext` 两模式同类，强转天然成立；尾巴是 `blockNetworkImage=true`（显示时重配）与 `clearCache`（会话中不清）。
- 公共层：任务注册表 + 超时暂停 + App 级 overlay。

替代方案对照：纯登录墙不用投前台——cookie 两端共享是现成的，WebScreen 登录一次后台重跑即可（Animeko 暖会话思路）；投前台的价值是**保住任务现场**（URL、表单进度、跳转链）。

---

## 8. neko 详细调研（2026-10-16 会话，实地阅读 `.workbuddy/ref/neko`）

ekibun 的 **neko** 是这条 lineage 最早的 Flutter/Dart 落地（文件头日期 2020-06~08），**早于 nezumi**，是 ACG `engine` 层（QuickJS + JS 数据源 + WebView 桥）的直接起源。源码已克隆到 `.workbuddy/ref/neko`（82 文件）。

### 8.1 工程形态

- Flutter + Dart（`pubspec.yaml`：`sdk ">=2.7.0 <3.0.0"`），状态管理用 `provider: 4.3.1`。
- 自研插件（本地 path 依赖，非 pub）：`flutter_qjs`（QuickJS 绑定）、`flutter_webview`（原生 WebView）、`flutter_iconv`（编码转换）、`neko_desktop`。
- 业务依赖：`dio: 3.0.10`（HTTP）、`moor` + `moor_ffi`（sqlite）、`xpath_parse`（XPath）、`highlight`/`flutter_highlight`（代码高亮，JS 控制台用）。
- 资源：`js/module/`、`js/provider/`、`js/init.js` 打进 assets。

### 8.2 引擎：`lib/engine/`

**`datasource.dart` —— `DataSource`（核心）**
- 持**单个** `IsolateQjs`（QuickJS 跑在 Dart isolate，跨线程不砸 UI）；`_ensureEngine` 懒初始化，先 `_ensureInfos` 再 `evaluate(init.js)`，最后 `init(_methodHandler, bind(webview))` 把 Dart 桥注入 JS。
- `_methodHandler(method, args)`：`method` 按 `_` 拆成 `class_method`；`_classWrappers` 目前只有 `"html": HtmlParser()`；其余是全局原语：`encode`/`decode`（flutter_iconv）、`xpath`/`xpath_query`（xpath_parse）、`fetch`（Http）、`console`。
- `_moduleHandler(module)`：`@init` → `js/init.js`；`@provider/<site>` → `js/provider/<site>.js`；其余 → `js/module/<name>.js`（用 `rootBundle.loadString` 读字符串后由 QuickJS 编译）。
- `getProvider(name)`：缓存 `_dataSource[name]`，未命中则 `import("@provider/$name")`，返回 provider 导出的函数表。
- `reset()` 关引擎清空缓存——**全局单引擎**，换源不重建。

**`init.js` —— 注入 JS 全局 API**（这就是 ACG `engine` 包要复刻的桥面）
- `TextEncoder`/`TextDecoder` → Dart `encode`/`decode`（flutter_iconv，fatal 标志透传）。
- `Request`/`Response`：把 Dart `fetch` 的返回包成 WHATWG 风格 `Response`，带 `arrayBuffer()`/`text(utfLabel)`/`json()`。
- `FormData`：JS 侧构造 `{__js_proto__:"FormData", __items__:[{name,value,filename}]}`，Dart `Http._wrapBody` 识别后转 `dio` 的 `MultipartFile`。
- `createClass(def)`：通用"Dart 对象代理"工厂——JS 侧 `new HtmlParser(...)` 背后是 `_dart("html", args)` 拿到不透明句柄，方法调用走 `_dart("html_<method>", [opaque, ...args])`。`HtmlParser` 用此机制暴露 `query/queryAll/attr/text/html/outerHtml/remove`。
- `Xpath(html)` → `(xpath) => _dart("xpath_query", [xpathObject, xpath])`。
- `console.log/info/debug/error` → Dart `print`。
- `fetch(input, init)` → `_dart("fetch", [Request])` → `new Response(...)`。
- `webview(url, options)` → `_webview(url, options)`（Dart 侧 `webview.dart`）。
- `encodeURI`/`encodeURIComponent`：基于 `_TextEncoder` 逐字符 `Uint8Array` + 十六进制转义（可指定编码）。

**`wrapper.dart` —— `HtmlParser` Dart 侧实现**
- `ClassWrapper<T>` 抽象：`constructor` + `_methods` 映射；`get methods` 懒包一层 `(obj,args)=>value(obj,args)`。
- `HtmlParser extends ClassWrapper<dom.Element>`：`constructor` 用 `html/parser` 解析 HTML 拿 `documentElement`；方法 `query`/`queryAll`（`querySelector(All)`）、`attr`（读/写）、`text`（读/写）、`html`（读/写 `innerHtml`）、`outerHtml`（只读）、`remove`。**这是 JS 侧 DOM 解析的全部能力，与 nezumi 的 `HtmlParser` 同构**（ACG 契约为此在 `_binding` 桥加 `HtmlParser` 插件类）。

**`webview.dart` —— WebView 桥**
- `webview(url, options)`：`FlutterWebview()` → `setMethodHandler` 注册 JS 回调（`onNavigationCompleted` 等，JS 传 `options[method]` 回调返回 `true` 即 `complete`）。
- 超时：导航完成后再等 **10s**（无回调则 `completeError`）；全局 **100s** 兜底。
- `options["ua"]` 可设 UA；`finally` 里 `destroy()`。
- **cookie 已共享**（WebScreen 登录一次后台重跑即可）——与 §7 的"纯登录墙不投前台"结论一致。

**`http.dart` —— `Http.fetch`**
- `dio` 单例，`validateStatus: (s)=>true`（不抛非 2xx），挂 `LogInterceptor`。
- `fetch(Map req)`：用 `dio.request`，`responseType: bytes`，`followRedirects` 按 `req["redirect"]=="follow"`；返回规范化对象：`{url, headers(map), ok, redirected, redirects[], status, statusText, body(bytes)}`。与 `init.js` 的 `Response` 对齐。

**`database.dart` —— moor 后台 isolate**
- `_backgroundConnection()`：`LazyDatabase` 指向 `neko.sqlite`（应用文档目录），跑在 `MoorIsolate.spawn` 的后台 isolate。
- `AppDatabase.subject = SubjectDatabase(DatabaseConnection.delayed(_connection))`。

### 8.3 JS 数据源契约（provider）

`js/provider/*.js` 是 ES module，导出函数；加载即 `import("@provider/<site>")`。

**`bgm.tv.js`（HTML 抓取型）**
- `search(key, page)`：`fetch` bangumi 搜索页 → `HtmlParser` 抓 `.item` 列表 → 映射出 `{id, name, image:{url}, summary, type}`（type 用 `subject_type_*(\d)` 正则归 book/video/music）。
- `getSubjectInfo(subject)`：抓条目页，补 `name/type/summary/image/tags/score/info`（infobox 拆成 `Object.fromEntries`）；**不补剧集**。
- 辅助：`resolveUrl`（相对/协议相对 → 绝对）、`parseImageUrl`（取封面，并把 `/l/`/`/c/` 等档位换成 `/m/`）。

**`acfun.cn.js`（JSON+HTML 混合 + WebView 嗅探型）**
- `search`：抓 ajaxpipe 接口，`JSON.parse` 后 `HtmlParser` 解析 `html` 字段里的条目卡。
- `getSubjectInfo`：移动端页抓 `.episodes-item` 列，每项带 `url`（如 `https://m.acfun.cn/v/?ab=<id>_<epid>`），**剧集以 `eps` 数组挂回 subject**。
- `getEpisode(episode)`：**用 `webview(episode.url, {ua, onRequest})` 嗅探**——`onRequest` 命中 `m3u8` 即 `return true` 结束；典型"网页播放页里抠直链"模式。

> 契约为**三函数** `search` / `getSubjectInfo` / `getEpisode`（acfun 版），bgm 版只实现了 `search`+`getSubjectInfo`。**没有** nezumi 后来加的 `getEpisodeList`、也没有线路（line）概念——剧集就在 `subject.eps` 里、URL 直接写死。**这是 ACG 草案把"按线路取剧集表 + 解析播放 URL"拆成 `getEpisodeList`/`resolveEpisode`（§2）的更早形态。**

**`js/module/crypto.js`**：vendored **CryptoJS** 整包（47KB 单行，MD5/SHA1/SHA256/RIPEMD160/SHA3/SHA512/AES/DES/3DES/RC4/Rabbit/PBKDF2/HMAC…），经 `_moduleHandler` 的 `module/crypto` 加载，供 provider `import` 做签名/哈希。对应 §1 里 nezumi 的 "CryptoJS（47KB 单行）"。

### 8.4 持久化：`lib/db/`（moor/sqlite）

- `subject.dart` → `Subjects(site, id, subjectType, name, image, summary)`；主键 `{site, id}`。
- `collection.dart` → `Collections(site, id, relate, lastViewEp, score, comment, tags, createTime, updateTime)`；主键 `{site, id}` + 外键 `subjects(site,id)`。
- `database.dart` → `SubjectDatabase`（`@UseMoor([Subjects, Collections])`，`schemaVersion = 2`；`onUpgrade` from==1 加 `collections.relate` 列；`beforeOpen` 开 `PRAGMA foreign_keys=ON`）。
  - 提供 `watchCollections()`（join 出 `SubjectCollection` 流）、`insertSubjectCollection`、`getCollection(s)`、`removeCollection`。
- **运行时 `Subject` 是松散 `Map`**（`subjectfromMap` 从 Map 转 moor 行），**非强类型模型**——ACG 改用强类型 `Subject`/`Episode`（§2）即对此改进。

### 8.5 UI：`lib/page/` + `lib/widget/` + `lib/view/`（Flutter/Provider）

- `main.dart`：`MultiProvider` 包 `AppTheme`，`MaterialApp` 路由 `home`/`search`/`subject`，`LifecycleWatcher` 监听前后台。
- `page/home`：底部/侧边 `TabItem` 标签栏；`OrientationBuilder` 横屏左侧 200 宽导航栏、竖屏底部 56 高 tab 条；`AnimatedSwitcher` 切换路由（`routes.firstWhere` 按 `currentRoute`）。
- `page/search`：`SearchPage`，默认 `provider = "acfun.cn"`；输入触发 `DataSource.getProvider(site).search(key, page)`，结果 `subjectfromMap` 成 `SubjectCollection` 并回填本地收藏状态（`getCollections`）；**重触发时把旧 `Completer` 全部 `completeError("cancel")` 取消**——可复用的"取消旧任务"范式。
- `page/subject`：`SubjectPage`：`getProvider(site).getSubjectInfo(subject)` 补元数据、`getEpisode(ep)` 取单集；同样用 `Completer` 取消旧 job；收藏读写走 `AppDatabase.subject.insertSubjectCollection/removeCollection`；封面用 `HttpImage`（`Http.wrapReq`）。
- 控件：`ActionBar`、`HttpImage`、`TabItem`、`Ripple`、`GradientBackground`、`subjectList` 等。

### 8.6 与 ACG 架构的对照（结论已沉淀进 `architecture-design.md` §0）

| 维度 | neko（2020） | ACG 草案 | 取舍 |
| --- | --- | --- | --- |
| 引擎 | 单 `IsolateQjs` + `_methodHandler`/`_moduleHandler` 桥 | `engine` 包，同名 QuickJS + Dart 原语桥 | **直接继承**桥机制与 provider module 加载 |
| JS 全局 API | `fetch`/`HtmlParser`/`Xpath`/`webview`/`encode`/`console`/`FormData` | `_binding` 桥暴露同款 | **继承**；`HtmlParser` 明确进 `_binding` |
| 数据源契约 | `search`/`getSubjectInfo`/`getEpisode`（无线路） | `search`/`getEpisodeList`/`resolveEpisode` | **演化**：拆出线路域与 URL 解析 |
| 主键 | `Subjects(site,id)` + 外键 | `SubjectRef(site, id: String)` | **继承**复合键思想，收为值对象 |
| 领域模型 | 松散 `Map` + moor 行 | 强类型 `Subject`/`Episode`（§2） | **改进**：类型安全 |
| 持久化 | moor/sqlite（后台 isolate） | kotlinx-serialization JSON 分片 | **偏离**：匹配 KMP + 纯本地先行 |
| 标题/封面 | bgm.tv `parseImageUrl` 切档位 `/m/` | `cover`+`coverColor` | 可参考档位策略 |
| 原生插件 | `flutter_qjs`(QuickJS) / `flutter_webview`(C++) / `flutter_iconv` | `cxx/quickjs` / `cxx/webview` | **同源 lineage**，ACG 用自研 C++ 替代 Flutter 插件 |

**要点**：neko 证明了"QuickJS 跑数据源 + Dart 暴露 `fetch`/`HtmlParser`/`webview` + provider 热加载"这条链路可行且足够小；ACG 不必重新验证引擎形态，只需沿用桥机制、把契约演进到线路/解析分离、并把模型强类型化、持久化换成分片。native 侧 QuickJS/WebView 绑定是同一血脉（ekibun 一路从 neko 的 Flutter 插件改到 ACG 的 `cxx/`），改 `cxx/` 前先读 `cxx/quickjs`、`cxx/webview` 本地 submodule，不联网。
