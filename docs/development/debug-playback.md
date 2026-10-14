# 调试播放（人工手册）

本文件是**人工调试播放链路**的命令与流清单，不在 agent 自动决策路径上；agent 跑测试不需这些。验收纪律与构建/测试命令见 [build-and-test 技能](../../.agents/skills/build-and-test/SKILL.md)。

## 本地媒体服务器

hotRun 的应用进程由 premain agent（`DevToolsAgent`）托管 dev 服务器，应用退出时经 shutdown hook 收场；素材与端口用 `-PdevMedia=<路径>` / `-PdevPort=<n>` 指定。硬杀 Gradle 客户端的残留，下一次 hotRun 前按 PID 文件自愈。手动常驻（curl 调试，无控制条）：

```bash
./gradlew :shared:devMediaServer   # 默认素材即入库测试媒体、端口 8099
./gradlew :shared:devMediaServer --args="--port 9000 --media D:/x.mp4"
```

- PlayScreen 默认 URL：`http://127.0.0.1:8099/media`；应用主窗口右上角有 DEV 控制条（冻结 / 恢复 / 限速）。
- 播放中途热改：`curl http://127.0.0.1:8099/__control/freeze`（掐断，复现 stall）、`/resume`、`/throttle?bps=<n>`（0 = 解除）、`/shutdown`。
- **测试不经过这个常驻进程**：用例各自进程内起 `TestMediaServer`（ephemeral 端口、计数器隔离），常驻实例会让请求计数与冻结状态跨用例串味。

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
- 都不设 Referer / UA / cookie，**测不出 header 场景**——要校验请求头，用 `TestMediaServer` 自己数，或本地生成后配抓包。
- **BYTERANGE 没有现成公开流**：要测就本地生成。注意**本仓 native 构建不出 ffmpeg CLI**（`ffmpeg.cmake` 带 `--disable-programs`），生成用独立安装的 ffmpeg：
  - AES-128：`ffmpeg -hls_key_info_file keyinfo …`。keyinfo 三行 = key 的 URL（进播放列表）/ key 本地文件路径（ffmpeg 拿它加密）/ IV hex（留空随机）；分片与 key 一起用任意静态服务器伺服。
  - BYTERANGE：`-hls_flags single_file`（分片并进单文件，播放列表改用 `EXT-X-BYTERANGE`）。
