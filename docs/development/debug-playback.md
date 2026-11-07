# 调试播放（人工手册）

本文件是**人工调试播放链路**的命令与流清单，不在 agent 自动决策路径上；agent 跑测试不需这些。验收纪律与构建/测试命令见 [build-and-test 技能](../../.agents/skills/build-and-test/SKILL.md)。

## 本地媒体服务器

`debugPlay` 的 `main` 在**同一进程里**起 dev 服务器（端口由系统现给）并把控制条贴到窗口上，关窗口一起收场。端口看控制台那行 `serving at: http://127.0.0.1:<port>/media`，或直接看窗口地址栏：

```bash
curl http://127.0.0.1:<port>/__control/status
```

验证窗口是人工入口，每页一个（真 AWT 窗口，不进 `jvmTest`）：

```bash
./gradlew :shared:debugPlay                      # 播放：自带 dev 服务器与 DEV 控制条
./gradlew :shared:debugWeb                       # 浏览器 / WebView2 宿主
./gradlew :shared:debugCode                      # 脚本控制台
```

数据目录落 `shared/build/debug`（`-Dacg.debug.dir` 可改），设置与 WebView2 的 user data folder 跨启动保留。

素材全部按 `/media/<路径>` 从 `test-media/` 里取（仓库根，目录直接挂在测试 classpath 上，两个模块
共用、不进构建产物），没有「换一个文件」的参数：临时想换，丢进 `test-media/` 即可，下一次请求就读到
新的，不用重编也不用重跑 Gradle。

- `debugPlay` 默认 URL：`http://127.0.0.1:<port>/media/hls-ad/playlist.m3u8`（`<port>` 见上面的端口文件；夹广告的 HLS；
  `/media` 本身返回入库的那份 bbb 单文件）；应用主窗口右上角有 DEV 控制条（冻结 / 恢复 / 限速）。
- 播放中途热改：`curl http://127.0.0.1:$PORT/__control/freeze`（掐断，复现 stall）、`/resume`、`/throttle?bps=<n>`（0 = 解除）、`/shutdown`。
  这几个端点是 `DevServer` 自己挂到 `TestServer(routes = …)` 上的，夹具里没有 dev 专用逻辑。
- **测试不经过这个常驻进程**：用例各自进程内起 `TestServer`（ephemeral 端口、计数器隔离），常驻实例会让请求计数与冻结状态跨用例串味。

> 结果落盘再读，别靠终端实时输出。Windows 上 JVM 按控制台代码页编解码，中文日志直接读会乱码；加 `--console=plain` 让输出变成可线性读的纯文本。

## 在线测试流（公开、免鉴权）

改播放链路（avio / io_open / hls）后当快速回归：

| 测什么 | URL |
|---|---|
| TS 多码率 VOD | `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` |
| fMP4 多码率（杜比视界 / Atmos / 字幕组） | `https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8` |
| TS + 多音轨 + WebVTT 字幕（`EXT-X-MEDIA` 分组） | `https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8` |
| AES-128 加密（key URL 公开可拉） | `https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel-aes.ism/.m3u8` |
| 直播（轮播） | `https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8` |

- **AES-128 那条现在是「预期失败」**（`crypto+` 前缀 + key/iv 在 io_open 的 dict 里被丢弃），也是接上解密后的验收用例。
- 都不设 Referer / UA / cookie，**测不出 header 场景**——要校验请求头，用 `TestServer` 自己数，或本地生成后配抓包。
- **BYTERANGE 没有现成公开流**：要测就本地生成。注意**本仓 native 构建不出 ffmpeg CLI**（`ffmpeg.cmake` 带 `--disable-programs`），生成用独立安装的 ffmpeg：
  - AES-128：`ffmpeg -hls_key_info_file keyinfo …`。keyinfo 三行 = key 的 URL（进播放列表）/ key 本地文件路径（ffmpeg 拿它加密）/ IV hex（留空随机）；分片与 key 一起用任意静态服务器伺服。
  - BYTERANGE：`-hls_flags single_file`（分片并进单文件，播放列表改用 `EXT-X-BYTERANGE`）。
