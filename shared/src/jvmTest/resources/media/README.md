# 测试媒体

素材**入库**而不是放在工作区外的临时目录（那份目录整体 gitignore，换台机器或 CI 跑就静默跳过、
用例形同不存在）。取用一律经 **`TestMedia.path("…")`**（`TestMedia.kt`）—— 优先把 classpath 资源
直接解析成真实路径给 `FileIO`（Gradle 跑测试时资源是目录形态，**一次拷贝都不用**）；只有资源
被打进 jar 时才退回复制到临时文件。

## bbb-640x360-12s-faststart.mp4

- **来源**：Blender 官方 Big Buck Bunny trailer（`download.blender.org/peach/trailer/trailer_720p.mov`，
  1280×720 25fps + AAC 5.1，32.995s，17.8 MB，CC-BY 3.0）的 **8s..20s** 一段，
  用本机 ffmpeg 重压成 640×360 + AAC 立体声。**抓取与压制：2026-10-06**。
- **用途**：**通用测试素材** —— 有画面、有音轨、12 秒够长、只有 443 KB（开流快）。
  需要真实画面/音频的用例优先用它，省得为一件事下一个大文件。
- **ffprobe 核定**：h264 High 640×360 25fps、300 帧、12.000s + AAC 44.1kHz **立体声**
  （48 kbps，518 帧），**453,429 字节**。
- **`moov` 在头部**（`ftyp/moov/free/mdat`，**0 个 `moof`**，单块非碎片化）⇒ faststart，
  开流只读头部即可，`getStreams()` 不卡。
- **帧栅格**：`-g 50` @25fps ⇒ 关键帧每 **2 秒**一个（0/2/4/6/8/10 s = 帧号 0/50/…/250），
  帧时间戳恒为 **40_000µs** 的整数倍 —— 用例里「序列连续」直接对这个栅格下断言，不用另存参考数据。
- **视频流时基 1/12800** ⇒ `avformat_seek_file` 把微秒折回 tick 时的**半个 tick = 39µs**：
  比它小的 seek 偏移量会被折回原格。退帧空探的步长有两条取法 —— 量到帧间隔时取间隔的 3/4
  （30_000µs），冷启动就从这个刻度推（一格 78.125µs 的 3/4 ≈ 59µs）；两条都落在
  「半格 39µs」与「一个帧间隔 40_000µs」之间，见 `FFPlayerSeekFrameSequenceTest`。
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
