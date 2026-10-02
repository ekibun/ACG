# 架构设计（草案）

> 依据 2026-10-02 调研（Bangumi v0 / AniList / animeko / nezumi），完整记录见
> [research-2026-10-02.md](./research-2026-10-02.md)。未落地，仅设计。

## 1. 分层

```
UI (Compose: 收藏/搜索/条目/播放/设置)
 │
 ├─ model      领域模型（§2）            ← 只此一份，UI 唯一消费形状
 ├─ catalog    Bangumi v0 客户端 + 手写 mapper（P2: anilist）
 ├─ engine     QuickJS + JS 数据源契约（search / getEpisodeList? / resolveEpisode）
 ├─ web        可见/后台 WebView（同实例两种可见态，§4）
 └─ player     自研 FFmpeg 内核 + HttpIO/FileIO（m3u8 内核自解，headers 进 AvIO）

持久化：kotlinx-serialization JSON 文件分片（collection / lines / history）
同步：  本地先行 + 失败重试（直连 Bangumi，last-write-wins 用本地时间戳）
```

## 2. 领域模型

```kotlin
data class SubjectRef(val site: String, val id: String)   // site: "bgm"/"anilist"/JS 源站名

data class Subject(
  val ref: SubjectRef, val type: SubjectType,             // BOOK/ANIME/MUSIC/GAME/REAL（1/2/3/4/6）
  val name: String, val nameCn: String?,                  // AniList → nameCn=null
  val cover: String?, val coverColor: String?,            // 单 URL + 主色占位
  val summary: String?, val date: PackedDate,             // Int 位打包年月日，Invalid 哨兵
  val platform: String?,                                  // "TV"/"OVA"/"剧场版"
  val episodeCount: Int, val volumeCount: Int,
  val rating: Rating?, val nsfw: Boolean,
  val tags: List<Tag>, val aliases: List<String>,
  val infobox: List<InfoboxEntry>?, val airWeekday: Int?, // 1-7，放送表用
  val related: List<RelatedSubject>,                      // 13 值归一枚举 + raw 原串
)

sealed class EpisodeSort : Comparable<EpisodeSort> {       // "01"/"24.5"/"SP01"/"OVA"
  class Normal(val number: Float); class Special(val type: EpisodeType, val number: Float?); class Unknown(val raw: String)
}

data class Episode(                                        // 照 Bangumi v0 形状（唯一有剧集表的源）
  val id: String, val type: EpisodeType,                   // 0-6 本篇/SP/OP/ED/PV/MAD/其他
  val sort: EpisodeSort, val ep: EpisodeSort?,
  val name: String, val nameCn: String?,
  val airdate: PackedDate, val durationSeconds: Int, val disc: Int?,
)

data class Line(val site: String, val id: String, val title: String = "", val extra: String? = null)
// Line 绑定到 SubjectRef（哪些站点条目对应本条目 + 默认线路），对齐靠 EpisodeSort + extra 偏移

data class CollectionEntry(
  val subject: SubjectRef,
  val status: CollectionStatus,                            // WISH/DOING/DONE/ON_HOLD/DROPPED/NOT_COLLECTED
  val score: Int,                                          // 0-10，0=未评（AniList /10 归一）
  val progress: Int, val progressVolumes: Int,
  val comment: String?, val private: Boolean, val tags: List<String>,
  val updatedAt: Long,                                     // epochTimeMs()（新增 expect 墙钟原语）
)

data class HistoryEntry(                                   // 继续观看，仅本地
  val subject: SubjectRef, val line: Line, val episodeSort: EpisodeSort,
  val positionUs: Long, val durationUs: Long, val updatedAt: Long,
)

data class PlaybackInfo(val url: String, val headers: Map<String, String> = emptyMap())  // 运行时，不落盘
```

JS 数据源契约（不动现有四参签名）：

```js
search(key, page)                 → [{ name, image, summary, id, lines? }]
getEpisodeList(line)              → [{ sort, name, ... }]        // 可选：站点自己的剧集表
resolveEpisode(line, episode)     → { url, headers? }            // sort + line.extra 算 URL；m3u8 直链亦可
```

## 3. 关键决策

| 决策 | 理由 |
| --- | --- |
| 主键 `SubjectRef(site, id: String)` | Bangumi/AniList Int 撞号；JS 源 id 是 `"20090010/1"` 类串 |
| `Episode` 照 Bangumi 形状 | 唯一有剧集表的源；AniList 无 per-episode 数据 |
| 线路域与元数据域分离，sort 数值对齐 | onair/nezumi/BangumiPlugin 三代已验证；URL 不持久化 |
| 进度上报/同步按 episodeId；sort 只做排序、邻集与线路对齐 | animeko 全链路验证 |
| 收藏 5 态 = Bangumi 口径 + `NOT_COLLECTED` 哨兵 | 它是可同步主源；AniList REPEATING→DONE+本地 repeat |
| 同步 last-write-wins 用**本地**时间戳 | Bangumi `updated_at` 有官方 bug 不可靠 |
| 模型放 `acg.model`，客户端放 `acg.catalog.bgm`；不进 `acg.engine` | engine 类名是对外契约，改了静默失效 |
| 手写 DTO + 扩展函数 mapper，不引 OpenAPI generator | 只用 6-8 个接口；Animeko 的 mapper 也是这么写的 |
| `HtmlParser` 进 `_java` 桥（engine 包） | 补齐 JS 侧 DOM 解析缺口，同时是订阅解释器的依赖 |

## 4. WebView 前后台会话

**按任务一实例；前台 = 同一实例的可见态。** 悬浮球（App 级 overlay，非 WebScreen 私有）点击后把任务实例的 `WebViewSurface` 叠在浏览页之上；两层都保持在组合里，靠叠放次序切换。任务被显示期间 30s 超时暂停。不做全局单实例（并发互砸、clearCache 防串失效、砸用户浏览页）。

落地缺口：桌面 `WebView.jvm.kt:83` 强转缝（后台控制器改为继承）；桌面 C++ 摘 `View.background` 标志 + `put_IsVisible`（焦点闸 webview.cpp:929）；Android 显示时重配 `blockNetworkImage`、会话中不 `clearCache`；引擎侧任务注册表。纯登录墙可不投前台（cookie 已共享）。

## 5. 落地顺序

1. `epochTimeMs()` 原语 + kotlinx-serialization 依赖（改依赖先确认）+ `model` 包类型
2. `acg.catalog.bgm`：DTO + mapper + 限流退避（UA 必带，~3 req/s）
3. 持久化分片 + 收藏/历史仓库（本地先行）
4. `HtmlParser` 宿主原语 + JS 数据源契约（search/resolveEpisode）+ Line 绑定
5. 业务四页 UI（收藏 → 详情 → 搜索 → 设置）
6. WebView 投前台（先 Android 验证交互，再桌面 C++ 小改）
7. P2：AniList mapper、per-episode 精确进度、Animeko 订阅兼容解释器（`"a"`+`"no-channel"` 先行）、边播边缓存、torrent
