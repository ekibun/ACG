# 硬解：帧的归属、输出池与两端改动面

只收录**从本地 submodule 源码核实过的外部行为**，以及它们决定的实现形态。已落地：桌面 D3D11VA、
Android MediaCodec 的 ByteBuffer 路线，落点在 `cxx/ffmpeg/ffmpeg.cpp` 的 `AvCodec_initNative` 与
`frameToMemory`；「不报错只失效」的判据在 [playback-invariants.md](./playback-invariants.md) §2.6。
不在 agent 自动决策路径上。
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

### 1.4 背压来自收帧端的窗口，不来自池子

桌面零拷贝下 `AVFrame` 带着解码器的 surface 一路到送显，所以收帧端按 `extra_hw_frames` 记
「交出去、还没还回来」的张数（`video_output.h` 的 `videoOutputFrameRoom` /
`videoOutputHoldFrame` / `videoOutputReleaseFrame`）：到上限就不再收，帧留在解码器里 ⇒
`buffer_frame` 非空 ⇒ 下一次 `send_packet` 只塞输入单槽、不解码 ⇒ 再下一次 EAGAIN ⇒ 上层重试、
读包停下。池里给解码器的那一份因此一分没动，`send_packet` 的 ENOMEM 不可达，**送包端不需要闸门**。

⚠️ 窗口满 + 解码器输出槽还压着一帧时，两个入口都不会自己前进：播放轮靠 playJobs 当场消费帧
自己解开，步进轮会先停消费。所以解码作业**不能**因为轮次结束就丢掉已经读出来的包 ——
`stepForward` 不 seek，靠的正是「解复用器停在上一帧之后」，丢一个包下一次步进就跳帧
（`FFPlayerTest` 会红）。现在只允许在片尾冲刷（`packet == null`）时提前退出。

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

### 2.5 已落地：直出 Surface（真机通过）

`AvCodec_initNative` 多一个 `surface` 参数。给了就在 `avcodec_open2` 之前
`av_mediacodec_default_init`，于是帧是 `AV_PIX_FMT_MEDIACODEC`：

- 收帧处**不 transfer**：这条帧没有内存可转，`data[3]` 才是待渲染的 buffer；
- 上屏在 `flushFrame` 里做（Android 走 `presentVideoFrame`，桌面走 `renderSurfaceFrameNative`）：
  `av_mediacodec_release_buffer(buffer, 1)` 把 buffer 还给 codec 就是渲染，像素全程不进内存，
  sws 与那次 memcpy 都省了。要不要直出由**帧自己的格式**决定（`AV_PIX_FMT_MEDIACODEC`），
  上层不再记「有没有走直出」这个标志 —— 拿不到 Surface 时解码器根本产不出这种帧；
- `av_frame_free` 触发的是 `release(index, 0)`（丢弃），而 release 幂等（§1.3），
  所以上屏之后再 close 不会二次释放。

⚠️ **判据只能是「暂停后连按单帧步进，每一次都换帧」**，光看播放正常会骗人：下面两次翻车
都是播放正常、步进坏掉。

⚠️ **模拟器的 codec 直出在暂停态不刷新屏幕，且与 GPU 加速无关。** 同一份代码在 Pixel 模拟器
（Android 14，`c2.goldfish.h264.decoder`）上：`Using surface` 有、`release_buffer(1)` 每步都调、
`SurfaceTexture.getTimestamp()` 每步推进，时间轴也照走，但屏幕像素不变。换 SurfaceView
（Animeko 的做法）只是变成时好时坏。同一份代码换到真机（华为 ANG-AN00，Android 12，arm64）
就完全正常。⇒ 这条路的验证必须在真机上做。

判据与对照实验（同一台设备、同一份片源、同一串点击，各 6 次 +1f）：

| 条件 | 结果 |
| --- | --- |
| 模拟器 `-gpu swiftshader_indirect`（软件渲染） | 时间轴 3.68s → 3.92s，画面停在 `#068` |
| 模拟器 `-gpu host`（宿主 NVIDIA，GLES 3.1，SurfaceFlinger 认到这块卡） | 同上，仍然停在 `#068` |
| 同一台 GPU 模拟器，`hardwareOutput = 0`（逐帧拷贝） | 6 次步进 6 个不同帧号，退帧原路返回 |

所以**不是合成 / GPU 的问题**：像素在 codec 交出去以后就没再上屏，而同一台机器上
「解码 → NV12 → sws → `copyBufferToSurface`」这条路完全正常。模拟器把这个面交给我们验不了。

⇒ **不为此加设备类型分支**：`hardwareOutput` 恒为 Surface 的 global ref，模拟器与真机走同一条路。
模拟器上「暂停后步进不换帧」是它的 codec 垫片问题（同机的 mediamp/ExoPlayer 也一样），
自测这类交互请上真机，别在模拟器上判死。

真机实测：暂停在 `00:00.680`，`Using surface` + `video decode: h264_mediacodec (surface)`；
步进 6 次帧号逐次变化、退帧 6 次原路返回并回到 `00:00.680`；播放跟着走，
全程没有 `EAGAIN` / `reference picture missing`。

两个前提，缺一个画面就是白的（时间轴照走）：

- **`get_format` 必须显式返回 `AV_PIX_FMT_MEDIACODEC`**。`avcodec_parameters_to_context` 把容器的
  `pix_fmt`（yuv420p）写进了上下文，默认的 `get_format` 拿它去匹配 mediacodec 的候选列表
  （只有 MEDIACODEC），匹配不上就退回 ByteBuffer 模式 —— mediacodec 一声不吭地按 NV12 出帧，
  `av_mediacodec_default_init` 白挂。日志判据：正常时 ffmpeg 会打 `Using surface 0x...`。
- **SurfaceTexture 的缓冲尺寸要等于视频尺寸**（`setDefaultBufferSize`），在 Surface 交给解码器
  **之前**设好；少了它画面同样不出来。

⚠️ 这条路的帧占着 codec 的 surface 池（§1.2），而本仓库的调度会在送显前蓄三四帧（§1.4）。
池子深度小于蓄帧深度时 `send_packet` 会一直 EAGAIN —— 现在的处理是**不丢包**：native 把
「没送进去」回传给上层，`FFPlayer` 按轮次重试同一个包（见 `cxx/AGENTS.md`）。真机的 12s
片源没触发，**换机 / 换片源时仍要盯这条**。

⚠️ seek 之后旧 serial 的 buffer 释放变成 no-op（§1.3），槽位要等 codec 重建才回 ——
连续快速 seek 可能表现为「跳转后黑一下再动」。

---

## 三、桌面：d3d11va 只换解码器，渲染管线不动

### 3.1 构建侧

`d3d11va` 在 `target-os=mingw32` 下是 **autodetect**（`configure:356` 只提供 `--disable-d3d11va`），
依赖是 `dxva_h ID3D11VideoDecoder ID3D11VideoContext`（`:3402`），mingw-w64 自带这些头
⇒ 不需要 Windows SDK / MSVC。BtbN 的官方 Windows 构建同样是 MinGW 产物，带全套硬解。

⚠️ 头文件缺失时 configure **静默关闭**该组件，不报错。所以 `cxx/ffmpeg/ffmpeg.cmake` 显式写了
`--enable-d3d11va`（缺依赖则 configure 直接失败，而不是悄悄降级），并在桌面侧补上
`d3d11` / `dxgi` 两个链接库 —— `hwcontext_d3d11va.o` 直接调它们。

### 3.2 接线与两个坑

`av_hwdevice_ctx_create(AV_HWDEVICE_TYPE_D3D11VA)` → `ctx->hw_device_ctx` → `get_format` 挑
`AV_PIX_FMT_D3D11` → **收帧处**（`frameToMemory`）`av_hwframe_transfer_data` 成软件帧
（通常 NV12，10bit 片源是 P010）→ 原 sws 管线。

⚠️ **设备必须由 FFmpeg 自建**。`ID3D10Multithread_SetMultithreadProtected(TRUE)` 只在
`d3d11va_device_create` 内部执行（`hwcontext_d3d11va.c:685-689`）。传外部 device 时这段不执行，
而 `AvCodec`（自有 `avcodec` 线程）与 `postFrame`（播放线程）分处两个线程。

⚠️ **transfer 必须在收帧处做，不能留给 `postFrameVideo`**。两处原因：一是 `newAvFrame` 读的
`best_effort_timestamp` 只有 `av_frame_copy_props` 之后才有（transfer 只搬像素，
`hwcontext.c:398` 不搬属性），晚一步每帧的时间戳就是 0；二是 sws 的缓存键于是天然取到 transfer
结果（10bit 片源是 P010），不会退化成恒为 `AV_PIX_FMT_D3D11` 的常量。

---

## 四、改动面

已落地的是前两行（桌面 D3D11VA、Android ByteBuffer 路线），第三行的回读路线没有做：

| 平台 | 落点 | 内容 |
| --- | --- | --- |
| 桌面 | `cxx/ffmpeg/ffmpeg.cpp` + `ffmpeg.cmake` | `--enable-d3d11va` + `d3d11`/`dxgi`、建设备、`get_format`、`frameToMemory` |
| Android（直出 Surface） | `AvCodec.kt` + `SurfaceContext.android.kt` + `ffmpeg.cpp` | 曾按此实现，**暂停态画面不更新，已回退**（§2.5） |
| Android（ImageReader 回读路线） | 上述 + `AvSurfaceContext` / `FFPlayer` / `AndroidSurfaceContext` | 自建离屏 Surface、回读、帧/Image 配对（**未实现**） |

Kotlin 侧只多一个 `hardware: Boolean`（`FFPlayer` 默认开、`AvCodec` 默认关），
送显链路一行没改 —— 两种情况在 `frameToMemory` 之后就分不出来了。

回读路线只在 ByteBuffer 路线不可行时才需要，形态与代价：

- `ImageReader(YUV_420_888, maxImages = 2)` 的 Surface 交给 `av_mediacodec_default_init`；每帧
  `release_buffer(buf, 1)` → `acquireLatestImage` → 三个 plane 按 `rowStride` / `pixelStride` 拷成 NV12
  （UV 是 interleaved，`pixelStride == 2`，按 1 逐字节拷会得到半宽的错色）。
- 帧与 Image 的配对是**异步**的（release 之后才有图），只能靠 `Image.getTimestamp()` 对 `frame->pts`，
  必要时缓 1-2 帧重排 —— 这是整套方案唯一会抖的地方。
- `Image.close()` 必须当场调用，`maxImages` 不还就 stall —— 与 §1.2 同形的池子。

---

## 五、桌面零拷贝：共享纹理

上面两条路都还在「解码 → 显存 → CPU → Skia → GPU」上走一趟。桌面端另有一条不经 CPU 的路，
形态照 mediamp（Animeko 的 mpv 后端）在 Skiko 上已经跑通的那套：

```
D3D11 解码纹理 ──VideoProcessorBlt──▶ RGBA 共享纹理(NT 句柄) ──OpenSharedHandle──▶ Skia D3D12
                                                                     │
                                                        BackendRenderTarget → Surface
                                                                     │
                                                        blit 到 GPU surface → 快照 → drawImageRect
```

落点：native `cxx/ffmpeg/video_output.windows.cpp`，Kotlin `SkiaHwSurface.kt` / `HwVideoSink.kt`。

### 5.1 三处必须对齐

- **adapter 必须同一个**。`skikoDevicePtr` 是 Skiko 原生 `DirectXDevice` 的指针（64 位下前两个槽
  是 `IDXGIAdapter1*` 与 `ID3D12Device*`），取出来用 LUID 反查确认是一对，然后在这个 adapter 上
  `D3D11CreateDevice`。跨 adapter 的 NT 共享句柄打不开。
- **解码设备由播放器给**，不用 `av_hwdevice_ctx_create` 自建：手动填
  `AVD3D11VADeviceContext.device` 再 `av_hwdevice_ctx_init`，解码纹理才与共享纹理同一块 GPU。
- **输出格式固定 `DXGI_FORMAT_R8G8B8A8_UNORM`**：Skia 的 D3D 后端拒绝包装不可渲染的 color type
  （`RGB_888x` 会返回 null），所以按 RGBA_8888 包。

### 5.2 五个「不报错只失效」

- **帧带着 surface 往上走，池子是静态的**。零拷贝下收帧不 `av_frame_unref`：`AVFrame` 一路带到
  送显，blit 完才随帧关闭还回池子。池的大小是两个加法，见 `libavcodec/decode.c:1099`（补足 4 张
  基础工作帧）与 `:1155`（加 `extra_hw_frames`、帧线程数）：

  ```
  池 = 参考帧 + 1 张输出帧 + 3 张工作帧 + extra_hw_frames      // H.264/HEVC 参考帧 16
  ```

  `extra_hw_frames` 是**纯增量，只覆盖「上层同时压住的帧数」**，不覆盖参考帧与工作帧；上层即取即
  `unref` 时可以取 0，照样跑。现值 `ctx->extra_hw_frames = 3` 就等于「上层最多压 3 帧」，没有额外
  余量——收帧端按它停手（见 `playback-invariants.md` §2.10）。
  装不下时 `d3d11va_pool_alloc` 打 `Static surface pool size exceeded` 并返回 NULL
  （`libavutil/hwcontext_d3d11va.c:263`），一路变成 `avcodec_send_packet` 的 **ENOMEM，不是
  EAGAIN**；判据与「只发不收先撞哪个」的实测见 `playback-invariants.md` §2.10。
  取 3 的实测（2026-11-02，`bbb-640x360-12s`）：整片播到底 300 帧一帧不丢，wall 12.08 s，
  没有读超时、没有池溢出；音视频同播同样跑满 11.79 s。更小会撞上「一次 receive 交几帧」的粒度，
  更大只是多占 surface。
- **`DirectContext` 每次绘制现读**：Skiko 会重建 redrawer（后端回退、显示切换），旧上下文画出的
  图像不能再用，包装与快照都要丢弃重来。
- **`notifyContentWillChange(DISCARD)` 不能省**：内容由 D3D11 写入，Skia 否则复用上一张快照，
  画面停在第一帧。也不要 `toComposeImageBitmap()` —— 那是每帧一次 GPU→CPU 回读。
- **blit 完要等 GPU 写完**。现在写环和画环是同一拍（`flushFrame` 里 blit 完立刻定为当前帧），
  Skia 的 D3D12 队列紧接着读同一块显存，而 D3D11/D3D12 之间**没有 fence**。所以
  `VideoProcessorBlt` → `Flush` → `End(eventQuery)` → 自旋到 `GetData` 返回 `S_OK` 再交出去。
  早先版本是解码线程预写、显示时刻才顶帧，中间隔着好几帧的余量，不等待也不会撞上。
- **中转纹理只要盖住 Skia 的绘制滞后**：`kBufferCount = 3`。帧本身带着解码器的 surface 上来，
  这几张只是 Skia 能画的 RGBA 中转；blit（D3D11）和 Skia 的拷贝（D3D12）碰同一块显存、
  跨 API 没有 fence，所以至少两张轮流用。实测画布最多落后 blit 1 帧（`maxLag=1`，Compose
  画布、640x360/12s），3 张留两帧余量。早先的 8 格是为「解码线程预写、显示才顶帧」准备的。
  ```
  环 3：blits=290 shown=290（纯视频，尾部几帧照旧被丢帧收敛跳过）
  环 3：blits=300 shown=300（音视频同播）
  ```
### 5.3 回退

`HwVideoSink.create` 返回 null（Skiko 不是 Direct3D 后端、设备不可共享、拿不到 device 指针）即
退回 §四 的逐帧拷贝路径：`DesktopSurfaceContext.isHardwareVideo` 为 false，
`hardwareOutput` 为 0，解码器按普通硬解建、帧在收帧处 transfer 回内存。

### 5.4 实测（2026-10-29，本机）

测法：同一个 mp4 全片解码到 EOF，`AvCodec` 直接喂包，视频分支走
`postFrame` + `flushFrame`，三条路径比**进程 CPU 时间 / 帧**（壁钟受 UI 刷新节流影响，不作判据）。
素材用 `ffmpeg -f lavfi -i testsrc2 ... -c:v libx264 -preset ultrafast` 现场生成，各跑 3 轮取中位数。

| 素材 | 软解 + CPU 上传 | 硬解 + transfer + CPU 上传 | 硬解 + 零拷贝 |
| --- | --- | --- | --- |
| 1920×1080 / 300 帧 | 2.76 ms | 5.57 ms | **2.71 ms** |
| 3840×2160 / 150 帧 | 10.73 ms | 17.92 ms | **3.75 ms** |

读数：

- **零拷贝把硬解的 transfer 成本整个去掉了**，这是硬解能真正赢的原因 —— 相对
  「硬解 + transfer」，1080p 省 51%、4K 省 79%。
- **分辨率越高收益越大**：4K 相对软解快 **2.9 倍**（10.73 → 3.75 ms/帧）；
  1080p 与软解基本持平（2.76 → 2.71）。
- 1080p 持平不是零拷贝没生效，而是**这台机器软解太快**（1080p 只要 2.76 ms/帧，约 360 fps），
  硬解每帧的 GPU 提交与 VideoProcessor 通道成本与它相当。弱 CPU 上交点会更低。
- 数字对零拷贝**偏保守**：软解两路用无窗口的 `TestSurface`（不含每帧 GPU 上传与 Compose 重绘），
  零拷贝那路跑在真实窗口里，进程 CPU 里含了 UI 线程的 blit + snapshot。

---

## 六、验证

两端都没有 `ffmpeg` CLI 可用（`--disable-programs`），验证只能靠 native 日志。

1. **走的是哪条路**：`AvCodec_initNative` 在选中的分支上打 `video decode: d3d11va` /
   `video decode: d3d11va (shared device)` / `video decode: h264_mediacodec`，都不成立时打
   `no hardware decoder for <name>, decoding on the cpu`。看到最后一条就是回落，不是失败。
2. **桌面**：`CONFIG_D3D11VA` 由 `--enable-d3d11va` 保证（configure 缺依赖会直接失败）。
   帧形态看 `frameToMemory` 里 `hardware frame transfer failed` 有没有出现。
3. **桌面零拷贝**：`video_output.windows.cpp` 建好环时打 `hw video: <w>x<h> shared ring ready`；
   不能共享时打 `hw video: Skiko's Direct3D device is not shareable, staying on the cpu path`。
   用例 `DesktopSurfaceContextTest`（`:platform:jvmTest`）锁两件事：帧真的进了共享纹理
   （`present` 失败的帧会被丢掉，`DesktopSurfaceContext.frame` 就不走），以及共享纹理真的被画进
   Compose 画布（`isHardwareVideoDrawn`）。
4. **Android**：`video decode: h264_mediacodec` 表示硬解选上了，帧按 NV12 拷回内存，
   `postFrameVideo` 与软解走同一条 sws。直出 Surface 已回退（§2.5），出现 `(surface)` 就是又走回去了。
5. **步进 / 退帧**：时间轴动、画面不动就是「上屏时机」错了（§2.5、§5.2）。
   两个方向各按几次，画面必须每按一次换一帧，且帧号与时间轴对得上：
   桌面看 `frameState().serial`（= 被定为显示帧的次数）每步 +1、`index` 换格；
   Android 截图对比播放区像素。**注意别在片尾测** —— 那里本来就无帧可解。
6. 两端都要确认 `--disable-encoders` 不影响解码侧硬解（只关编码）；`--disable-avfilter` 与
   `postFrameVideo` 的 sws 无关（sws 在 libswscale）。

---

## 七、未决

- Android ByteBuffer 模式在目标机型上的实际 `color-format` 未知，需真机探针。
- `COLOR_FormatYUV420Flexible` 不在映射表 ⇒ 只声明 flexible 输出的机型会 init 失败，
  日志里是 `Output color format 0x7f420888 ... is not supported`，之后回落软解。
- 10bit / HDR 无方案：两条 Android 路线都拿不到 P010（桌面零拷贝的 VP 输出是 RGBA，能走）。
- 桌面零拷贝只有 Windows：macOS / Linux 仍是逐帧拷贝（mediamp 在那边走 Metal / GLX，本仓库没做）。
