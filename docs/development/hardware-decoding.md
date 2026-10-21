# 硬解：帧的归属、输出池与两端改动面

只收录**从本地 submodule 源码核实过的外部行为**，以及由它们推导出的改动面估算。当前仓库**未实现硬解**，
本文是动手前的评估基线；实现落地后只把「不报错只失效」的判据补进
[playback-invariants.md](./playback-invariants.md)。不在 agent 自动决策路径上。
播放时序的前提见 [playback-invariants.md](./playback-invariants.md)，模块划分见 [../architecture.md](../architecture.md)。
所有 ffmpeg 行号对应 `cxx/ffmpeg/ffmpeg/` 下的当前 submodule 版本。

---

## 一、帧是租约，不是像素

### 1.1 三种解码路径下 AVFrame 的形态

| 路径 | `frame->data[0..2]` | 像素在哪 | 取回 CPU 的出口 |
| --- | --- | --- | --- |
| 软解（现两端） | 直接指向 CPU 内存 | 进程堆 | 本来就在 |
| 桌面 `d3d11va` | `data[0]` = `ID3D11Texture2D*`，`data[1]` = 子资源索引（`libavutil/hwcontext_d3d11va.c:356-357`） | 显存 | `av_hwframe_transfer_data` |
| Android `mediacodec` + surface | 全 `NULL`，`data[3]` = `AVMediaCodecBuffer*` | codec 的输出 buffer（gralloc） | 无（见 §2.1） |

⇒ 硬解帧**本身不带像素**，它是一张句柄。`postFrameVideo`（`cxx/ffmpeg/ffmpeg.cpp:522-556`）对
`AV_PIX_FMT_MEDIACODEC` 的帧调 `sws_scale` 只能读到空平面 —— 不是「转得慢」，是「没有可读的数据」。

`frame->data[3]` 的取法在编码侧也有同款（`libavcodec/mediacodecenc.c:809`）。

### 1.2 输出池由 codec 在 configure 时建

`ff_AMediaCodec_configure(codec, format, s->surface, NULL, 0)`（`libavcodec/mediacodecdec_common.c:850`）
里的 `s->surface` 就是输出 Surface。buffer 由 codec 实现经 gralloc 分配、挂在 Surface 的 BufferQueue 上，
**数量由平台与 codec 协商**：FFmpeg 侧不假设任何上限，只用 `hw_buffer_count` 记账（`:354` 增、`:285` 减）。

⇒ 一张未归还的帧 = 一个被占住的输出槽位。**蓄帧深度受池子约束，与进程内存无关** —— 这是硬解与软解在
调度层最本质的差别。

⚠️ `ff_mediacodec_dec_close` 在 `hw_buffer_count != 0` 时**跳过 `AMediaCodec_stop`**，只打一条 debug 日志
（`:1137-1142`）。持有未归还 buffer 的实例被 close 时 codec 不会停 —— 没有报错。

### 1.3 归还与渲染是同一块资源的两个动作

`av_mediacodec_release_buffer`（`libavcodec/mediacodec.c:88-102`）用 `atomic_fetch_add(&buffer->released, 1)`
判「是否首调」⇒ **幂等**：显式 release 之后，`av_frame_free` 触发的 free 回调（`mediacodecdec_common.c:279-295`）
不会二次释放。

三个出口（`libavcodec/mediacodec.h:76-101`）：

| 调用 | 语义 |
| --- | --- |
| `av_mediacodec_release_buffer(buf, 0)` | 丢弃，槽位归还 |
| `av_mediacodec_release_buffer(buf, 1)` | 渲染进 Surface，槽位归还 |
| `av_mediacodec_render_buffer_at_time(buf, ns)` | 交给 Surface 按时间上屏 |

⇒ free 回调里固定是 `releaseOutputBuffer(index, 0)`（`mediacodecdec_common.c:283-291`），
也就是说 **`av_frame_free` 天然等价于「丢帧」**，渲染必须在此之前显式做一次。

⚠️ serial 判据（`mediacodec.c:92`）：flush / seek 之后旧 serial 的 buffer 释放变成 no-op ——
不崩溃、不泄漏，但槽位要等 codec 重建才回。

### 1.4 现有的背压已经够用

`FFPlayer.kt:431` 的闸门是 `sendingPacket > 3 || min(queue) > 3` 就停读 ⇒ 预读深度 ≤ 3~4 帧，
与池子同一量级。**不需要新增限速器**；要改的是另一件事：

⚠️ `ffmpeg.cpp:390-403` 的 `kMaxSendRetry = 16` 建立在软解前提上（注释：「输出的帧全部读完之后再送，
不会再返回 EAGAIN」）。硬解下帧已经交给 Java、却仍占着池子：`receive_frame` 收不出新帧、
`send_packet` 也进不去，于是 16 次空转后**丢包并打一条 "reference chain will be broken"**。
这是误判 —— 硬解落地时必须让 EAGAIN 原样上浮，交给 §1.4 那个闸门去等。

---

## 二、Android：surface 传不传决定输出形态

### 2.1 传 surface → 只剩 GPU 出口

`mcdec_map_color_format` 第一条判据（`mediacodecdec_common.c:226-228`）：`s->surface` 非空时直接返回
`AV_PIX_FMT_MEDIACODEC`。而 `libavutil/hwcontext_mediacodec.c:107-113` 的 `HWContextType` 只有
`device_create`，没有 `frames_alloc` / `transfer_get_formats` / `map_to` / `map_from` ⇒
`av_hwframe_transfer_data` 与 `av_hwframe_map` 对该格式都不可用。

⇒ 出口只有 §1.3 那两个渲染动作，没有 CPU 回读。

### 2.2 不传 surface → ByteBuffer 模式，直接拿 NV12

同一个函数在 `s->surface == NULL` 时落到映射表（`:210-217`）：
`COLOR_FormatYUV420Planar → AV_PIX_FMT_YUV420P`；SemiPlanar / QCOM / TI packed → `AV_PIX_FMT_NV12`。

出帧走 `mediacodec_wrap_sw_video_buffer`（`:444-518`）：`ff_get_buffer` 分配自有帧 → 按
stride / slice-height / crop 拷贝 → `releaseOutputBuffer(index, 0)` **当场归还**。源码注释原文：
「MediaCodec buffers needs to be copied to our own refcounted buffers」（`:459-460`）。

⇒ 这条路上帧是普通 NV12、**不占池子**、`postFrameVideo` → sws → RGBA 全部沿用，
不需要 ImageReader，也不需要自造 Surface。

### 2.3 Annex B 由 libavcodec 自动套

`h264_mediacodec` / `hevc_mediacodec` 的 `FFCodec.bsfs` 分别是 `"h264_mp4toannexb"` / `"hevc_mp4toannexb"`
（`libavcodec/mediacodecdec.c:635,639`，`cxx/ffmpeg/ffmpeg/configure:3647-3648,3674-3675` 的
`_decoder_select` 只是保证这两个 bsf 被编进库）。`decode_bsfs_init`（`libavcodec/decode.c:189-220`）
在 `avcodec_open2` 路径上读该字段并 `av_bsf_list_parse_str` 建链（`:198`），
`avcodec_flush_buffers` 会同步 flush 它（`:2372-2373`）。

⇒ **不需要自己接 bsf 管线**：按名字换成 `*_mediacodec` 即可，AVCC→Annex B 由库完成。

### 2.4 这条路的三个前提

- **厂商 codec 必须支持 ByteBuffer 输出**。硬件 codec 常只声明 `COLOR_FormatSurface`；此时
  `configure` 失败，或系统退到软件 codec（等于白费）。
- **`COLOR_FormatYUV420Flexible`（0x7F420888）不在映射表**（`:210-217`）⇒ 报
  `Output color format ... is not supported`（`:238-241`），init 直接失败。
- **只有 8bit**。表里全是 8bit YUV420 族，10bit / HDR 片源在这条路上拿不到 P010。

---

## 三、桌面：d3d11va 只换解码器，渲染管线不动

### 3.1 构建侧

`d3d11va` 在 `target-os=mingw32` 下是 **autodetect**（`configure:356` 只提供 `--disable-d3d11va`），
依赖是 `dxva_h ID3D11VideoDecoder ID3D11VideoContext`（`:3402`），mingw-w64 自带这些头
⇒ 不需要 Windows SDK / MSVC。BtbN 的官方 Windows 构建同样是 MinGW 产物，带全套硬解。

⚠️ 头文件缺失时 configure **静默关闭**该组件，不报错。当前 `cxx/ffmpeg/ffmpeg.cmake` 未显式开关，
且 `cxx/build/` 已清空 ⇒ `CONFIG_D3D11VA` 尚未确认。落地时应显式写 `--enable-d3d11va`
（缺依赖则 configure 直接失败，而不是悄悄降级）。

### 3.2 接线与两个坑

`av_hwdevice_ctx_create(AV_HWDEVICE_TYPE_D3D11VA)` → `ctx->hw_device_ctx` → `get_format` 挑
`AV_PIX_FMT_D3D11` → `postFrameVideo` 里 `av_hwframe_transfer_data` 成 NV12 → 原 sws 管线。

⚠️ **设备必须由 FFmpeg 自建**。`ID3D10Multithread_SetMultithreadProtected(TRUE)` 只在
`d3d11va_device_create` 内部执行（`hwcontext_d3d11va.c:685-689`）。传外部 device 时这段不执行，
而 `AvCodec`（自有 `avcodec` 线程）与 `postFrame`（播放线程）分处两个线程。

⚠️ **sws 的缓存键必须取 transfer 结果**，不能取 `frame->format`（硬解下恒为 `AV_PIX_FMT_D3D11`，
10bit 片源 transfer 出来是 P010）。同一类坑已经在 `ffmpeg.cpp:543-550` 记过一次。

---

## 四、改动面

| 平台 | 落点 | 内容 | 估算 |
| --- | --- | --- | --- |
| 桌面 | `cxx/ffmpeg/ffmpeg.cpp` + `ffmpeg.cmake` | 建设备、`get_format`、transfer 分支 | 60-80 行，Kotlin 0 |
| Android（ByteBuffer 路线） | `AvCodec.kt` + `ffmpeg.cpp` | 按名字换解码器、失败回落软解 | ~20 行 |
| Android（ImageReader 回读路线） | 上述 + `AvSurfaceContext` / `FFPlayer` / `AndroidSurfaceContext` | 自建离屏 Surface、回读、帧/Image 配对 | ~150 行 |

回读路线只在 ByteBuffer 路线不可行时才需要，形态与代价：

- `ImageReader(YUV_420_888, maxImages = 2)` 的 Surface 交给 `av_mediacodec_default_init`；每帧
  `release_buffer(buf, 1)` → `acquireLatestImage` → 三个 plane 按 `rowStride` / `pixelStride` 拷成 NV12
  （UV 是 interleaved，`pixelStride == 2`，按 1 逐字节拷会得到半宽的错色）。
- 帧与 Image 的配对是**异步**的（release 之后才有图），只能靠 `Image.getTimestamp()` 对 `frame->pts`，
  必要时缓 1-2 帧重排 —— 这是整套方案唯一会抖的地方。
- `Image.close()` 必须当场调用，`maxImages` 不还就 stall —— 与 §1.2 同形的池子。

---

## 五、验证顺序

两端都没有 `ffmpeg` CLI 可用（`--disable-programs`），验证只能靠 native 日志。

1. **Android 先走便宜的**：`avcodec_find_decoder_by_name("h264_mediacodec")`（HEVC 同理）、**不传 surface**，
   打 `frame->format` 与 configure 失败码。拿到 `NV12` / `YUV420P` 即成立，不必上 ImageReader。
2. **桌面**：先只建设备、打 `frame->format`（配合 `av_hwdevice_iterate_types`），确认拿到
   `AV_PIX_FMT_D3D11` 再接 transfer —— 反过来先接 transfer，出问题分不清是设备还是格式。
3. 两端都要确认 `--disable-encoders` 不影响解码侧硬解（只关编码）；`--disable-avfilter` 与
   `postFrameVideo` 的 sws 无关（sws 在 libswscale）。

---

## 六、未决

- 桌面 `CONFIG_D3D11VA` 未确认（构建目录已清），需重跑 configure。
- Android ByteBuffer 模式在目标机型上的实际 `color-format` 未知，需真机探针。
- 10bit / HDR 无方案：两条 Android 路线都拿不到 P010。
- 硬解下 `EAGAIN` 的处理方式（§1.4）尚未设计。
