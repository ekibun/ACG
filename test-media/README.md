# 测试媒体

素材**入库**而不是放在工作区外的临时目录（那份目录整体 gitignore，换台机器或 CI 跑就静默跳过、
用例形同不存在）。取用一律经 **`TestMedia.path("…")`**（`TestMedia.kt`）—— 优先把 classpath 资源
直接解析成真实路径给 `FileIO`（Gradle 跑测试时资源是目录形态，**一次拷贝都不用**）；只有资源
被打进 jar 时才退回复制到临时文件。

**这个目录直接挂在两个模块的测试 classpath 上**（`jvmTest.dependencies { runtimeOnly(files(...)) }`，
目录本身就当资源根），所以资源路径就是这里的相对路径，不带 `test-media/` 前缀。它不进
`build/processedResources`，换素材不用重编；往 `shared` 或 `platform` 里再放一份是多余的。

## bbb-640x360-12s-faststart.mp4

- **来源**：Blender 官方 Big Buck Bunny trailer（`download.blender.org/peach/trailer/trailer_720p.mov`，
  1280×720 25fps + AAC 5.1，32.995s，17.8 MB，CC-BY 3.0）的 **8s..20s** 一段，
  用本机 ffmpeg 重压成 640×360 + AAC 立体声。**抓取与压制：2026-10-06**。
- **用途**：**通用测试素材** —— 有画面、有音轨、12 秒够长、只有 423 KB（开流快）。
  需要真实画面/音频的用例优先用它，省得为一件事下一个大文件。
- **画面标记**：左上角烧了帧号 `#000`–`#299`（`drawtext` 的 `n`），逐帧步进时直接对号，
  一眼看出是退了一帧还是两帧。
- **ffprobe 核定**：h264 High 640×360 25fps、300 帧、12.000s + AAC 44.1kHz **立体声**
  （48 kbps，518 帧），**432,897 字节**。
- **`moov` 在头部**（`ftyp/moov/free/mdat`，**0 个 `moof`**，单块非碎片化）⇒ faststart，
  开流只读头部即可，`getStreams()` 不卡。
- **帧栅格**：`-g 50` @25fps ⇒ 关键帧每 **2 秒**一个（0/2/4/6/8/10 s = 帧号 0/50/…/250），
  帧时间戳恒为 **40_000µs** 的整数倍 —— 用例里「序列连续」直接对这个栅格下断言，不用另存参考数据。
- **视频流时基 1/12800** ⇒ `avformat_seek_file` 把微秒折回 tick 时的**半个 tick = 39µs**：
  比它小的 seek 偏移量会被折回原格。退帧空探的步长有两条取法 —— 量到帧间隔时取间隔的 3/4
  （30_000µs），冷启动就从这个刻度推（一格 78.125µs 的 3/4 ≈ 59µs）；两条都落在
  「半格 39µs」与「一个帧间隔 40_000µs」之间，见 `FFPlayerTest`。
- **内容**：字幕「ONE BIG RABBIT」→ 兔子站立/环顾（运动丰富）→ 字幕「AND ONE GIANT PAYBACK」
  → 黄昏森林暗场。全片 ffmpeg 全量解码零报错。
- **压制参数**（可复现）：
  ```
  ffmpeg -ss 8 -t 12 -i trailer_720p.mov -map 0:v:0 -map 0:a:0 \
    -c:v libx264 -profile:v high -level 3.1 -preset slower \
    -b:v 268k -maxrate 400k -bufsize 800k -vf scale=640:360 \
    -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 \
    -c:a aac -b:a 48k -ac 2 -ar 44100 -movflags +faststart out.mp4
  ```
  ⚠️ 目标体积极限 500 KB ⇒ 640×360 下码率不能超 ~300 kbps；想上 720p 得砍时长。
- **加帧号（2026-10-24）**：在上面那份的基础上二次压制，音轨原样 copy —— 仍 25fps / 300 帧 /
  时基 `1/12800` / 关键帧 0,50,…,250 / `moov` 在头部，体积 432,897 字节：
  ```
  ffmpeg -i bbb-640x360-12s.mp4 -vf "drawtext=text='#%{eif\:n\:d\:3}':x=12:y=12:fontsize=30:  fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=6"     -c:v libx264 -profile:v high -level 3.1 -preset veryslow -crf 24 -pix_fmt yuv420p     -g 50 -keyint_min 50 -sc_threshold 0 -c:a copy -movflags +faststart out.mp4
  ```

## hls-ad/playlist.m3u8

- **来源**：本机 ffmpeg 合成（纯色底 + 烧录时间码 + 帧号 + 分片序号），**2026-10-23**，2026-10-24 两次重压。
- **没有音频轨**：单输入纯色底合成，整包只有一条 h264 视频流。要验音频时间轴的用例得用
  `bbb-640x360-12s-faststart.mp4`（它对帧号那一节的「加帧号」二次压制也是 `-c:a copy`，音轨一直在）。
- **用途**：复现「主片中间夹一段广告」的 HLS —— 广告分片的 PTS 自己从头起算，主片跨过广告后
  **接着自己的 PTS 走**，于是广告之后「媒体轴」永远落后「声明轴」一个广告时长。
  真实的片源（`EXTINF` 累加 vs 分片内 PTS）就是这么错开的，`stepBack` 落不到目标之前也源于此。
- **结构**：`m000–m005`（2.0s ×6）+ `#EXT-X-DISCONTINUITY` + `a000–a002`（1.7s ×3，广告）
  + `#EXT-X-DISCONTINUITY` + `m006–m011`（2.0s ×6）。声明轴总长 29.1s。广告在声明 12s 处，
  媒体时间从 1.483s 重新起算 ⇒ 回跳 11.9s，够过判据的 `FF_AD_JUMP_US = 5s`。
- **画面**：主片黑底，中央两行是 `HH:MM:SS.mmm` 时间码（白）与帧号 `#000`–`#575`（黄），
  左下角是分片序号 `S00`–`S11`；广告红底 + 大 `AD`，下面是它自己的帧号 `#000`–`#122`，
  左上角时间码、左下角 `A0`–`A2`。
  逐帧步进、跨分片、跨广告时肉眼就能对上是哪一片、哪一帧（退一帧帧号就该减 1）。
  广告是把 5.1s 单独压的，帧号自己从 0 起 —— 这也是它的 PTS 会重新起算的原因。
  早先用的 `testsrc2` 彩条 + 滚动渐变会吃掉绝大部分码率，字糊成一团；换成纯色底后同样的
  体积限制下字是锐的，整包从 505 KB 降到 ~245 KB（带帧号比只有时间码时多 ~50 KB）。
- **时间轴**（µs 量级，实测）：主片首帧 `1.483s`；广告首帧也 `1.483s`；广告之后主片从 `13.483s`
  接上（声明轴已走到 `18.583s`）⇒ 广告后 `shift = 媒体 − 声明 = −5.1s`。
  5.1 不是 2.0 的整数倍，所以按声明轴选片后逐片找「媒体 ≥ 阈值」时，落点会比目标晚
  **0 ~ 一个分片**，正好复现真实片源的 0.674s。
- **可复现的压制参数**：
  ```
  ffmpeg -f lavfi -i "color=c=black:s=240x136:r=24:d=24" -vf "  drawtext=text='%{pts\:hms}':x=(w-tw)/2:y=(h-th)/2:fontsize=26:fontcolor=white,  drawtext=text='S%{eif\:trunc(t/2)\:d\:2}':x=8:y=h-th-8:fontsize=20:fontcolor=white"     -c:v libx264 -preset veryslow -crf 26 -g 48 -keyint_min 48 -sc_threshold 0 -pix_fmt yuv420p     -f hls -hls_time 2 -hls_list_size 0 -hls_segment_filename "m%03d.ts" main.m3u8
  ffmpeg -f lavfi -i "color=c=red:s=240x136:r=24:d=5.1" -vf "  drawtext=text='AD':fontsize=56:fontcolor=white:x=(w-tw)/2:y=(h-th)/2,  drawtext=text='%{pts\:hms}':x=8:y=8:fontsize=22:fontcolor=white,  drawtext=text='A%{eif\:trunc(t/1.7)\:d}':x=8:y=h-th-8:fontsize=20:fontcolor=white"     -c:v libx264 -preset veryslow -crf 26 -g 41 -keyint_min 41 -sc_threshold 0 -pix_fmt yuv420p     -f hls -hls_time 1.7 -hls_list_size 0 -hls_segment_filename "a%03d.ts" ad.m3u8
  ```
  然后按上面的顺序拼 `playlist.m3u8`（`EXTINF` 分别 2.0 / 1.7）。序号按 `trunc(t/片长)` 算，
  分片正是按整 2.0 / 1.7 s 切的，所以每片首帧的标注就是该片的序号。
- **三个用例共用它**：`AvFormatTest.adSegmentsNeverReachTheCaller` 看广告分片的包根本没到上层；
  `FFPlayerTest.skipRevertAndUndoRoundTrip` 看跳过 / 回退 / 撤销这一轮；
  `FFPlayerTest.framesStayContinuousAcrossBothAdBreaks`（先 `setSkipAd(false)`）看接缝 12s / 17.1s 两侧时间戳连续。
- **怎么用**：dev 媒体服务器按 `/media/<路径>` 直接取分包里的素材（`TestServer.resolveFile`），
  所以 `debugPlay` 起来后可以直接播 `http://127.0.0.1:<port>/media/hls-ad/playlist.m3u8`，
  端口就是窗口地址栏里那个（控制台也会打 `serving at: …`）。
