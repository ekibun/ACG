// 桌面（Windows）的视频输出端：共享纹理环。
//
// 平台差异只在 video_output.windows.cpp 与 video_output.android.cpp。
//
// 调用方（ffmpeg.cpp）只认 video_output.h。
#include "video_output.h"

#include <cstdio>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
}

#include "ffmpeg_internal.h"

// GetAdapterLuid 在 MinGW 下按值返回结构体，需匹配 MSVC 的 COM ABI。
#define WIDL_EXPLICIT_AGGREGATE_RETURNS
#include <d3d10.h>
#include <d3d11.h>
#include <d3d11_1.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <jni.h>
#include <windows.h>

#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <utility>

extern "C" {
#include "libavutil/frame.h"
#include "libavutil/hwcontext.h"
#include "libavutil/hwcontext_d3d11va.h"
#include "libavutil/log.h"
#include "libavutil/pixfmt.h"
}

namespace {

/**
 * 环里的纹理数。帧带着解码器的 surface 上来，这里只是 Skia 能画的那块 RGBA
 * 中转。
 *
 * blit（D3D11）与 Skia 的拷贝（D3D12）碰同一块显存、跨 API 没有
 * fence，至少两张轮流用。
 *
 * 实测画布最多落后 blit 1 帧（640x360/12s），3 张留两帧余量。见
 * hardware-decoding.md §5.2
 */
constexpr int kBufferCount = 3;
/** 帧状态里表示「还没有可用帧」的索引。 */
constexpr int kNoIndex = 0xF;

// 位域要装得下 8K 与「换过多少次配置」；shown 只有 24 位，但按 60fps 也能撑 77
// 小时。
constexpr uint64_t packState(uint64_t generation, uint64_t index,
                             uint64_t width, uint64_t height, uint64_t shown) {
  return ((generation & 0xFF) << 56) | ((index & 0xF) << 52) |
         ((width & 0x3FFF) << 38) | ((height & 0x3FFF) << 24) |
         (shown & 0xFFFFFF);
}

/**
 * Skiko 的 DirectXDevice 是它 native 侧的私有结构，64 位下前两个槽是
 * adapter 与 ID3D12Device（0.9 与 0.153 两代布局相同）。只读这段前缀，并用
 * LUID 反查确认两者确实是一对，不碰后面的字段。
 */
bool openSkiaDevice(int64_t devicePtr, ID3D12Device** outDevice,
                    IDXGIAdapter1** outAdapter) {
  if (devicePtr == 0) return false;
  if (devicePtr & static_cast<int64_t>(sizeof(void*) - 1)) return false;
  const void* address =
      reinterpret_cast<const void*>(static_cast<uintptr_t>(devicePtr));
  void* prefix[3] = {};
  if (IsBadReadPtr(address, sizeof(prefix))) return false;
  std::memcpy(prefix, address, sizeof(prefix));

  auto* deviceUnknown = static_cast<IUnknown*>(prefix[2]);
  auto* adapterUnknown = static_cast<IUnknown*>(prefix[1]);
  if (!deviceUnknown || !adapterUnknown) return false;

  ID3D12Device* device = nullptr;
  if (FAILED(deviceUnknown->QueryInterface(
          __uuidof(ID3D12Device), reinterpret_cast<void**>(&device))) ||
      !device)
    return false;
  IDXGIAdapter1* adapter = nullptr;
  if (FAILED(adapterUnknown->QueryInterface(
          __uuidof(IDXGIAdapter1), reinterpret_cast<void**>(&adapter))) ||
      !adapter) {
    device->Release();
    return false;
  }

  DXGI_ADAPTER_DESC1 desc{};
  LUID deviceLuid{};
  device->GetAdapterLuid(&deviceLuid);
  if (FAILED(adapter->GetDesc1(&desc)) ||
      desc.AdapterLuid.LowPart != deviceLuid.LowPart ||
      desc.AdapterLuid.HighPart != deviceLuid.HighPart) {
    adapter->Release();
    device->Release();
    return false;
  }
  *outDevice = device;
  *outAdapter = adapter;
  return true;
}

}  // namespace

struct SharedTexture {
  ID3D11Texture2D* texture = nullptr;
  HANDLE handle = nullptr;
  /** 在 Skia 的 D3D12 设备上打开的资源，交给 Skia 包装成渲染目标。 */
  ID3D12Resource* resource = nullptr;
  ID3D11VideoProcessorOutputView* outputView = nullptr;
};

struct VideoOutput {
  std::mutex lock;

  ID3D12Device* skiaDevice = nullptr;
  IDXGIAdapter1* adapter = nullptr;
  ID3D11Device* device = nullptr;
  ID3D11DeviceContext* context = nullptr;
  ID3D11VideoDevice* videoDevice = nullptr;
  ID3D11VideoContext* videoContext = nullptr;
  ID3D11VideoProcessorEnumerator* enumerator = nullptr;
  ID3D11VideoProcessor* processor = nullptr;

  SharedTexture buffers[kBufferCount];
  /** 解码纹理 array 的每个 slice 一份输入视图：每帧新建一次是纯开销。 */
  std::map<std::pair<void*, int>, ID3D11VideoProcessorInputView*> inputViews;
  /** 输出（= 视频显示）尺寸；输入纹理可能因对齐更大，由 VP 缩放。 */
  int width = 0;
  int height = 0;
  unsigned generation = 0;
  /** 当前该显示哪张纹理；present 不碰它。 */
  int latest = kNoIndex;
  /** present 过的帧数，决定写哪张纹理。 */
  uint64_t serial = 0;
  /** 选为显示帧的次数，画布据此判断要不要重画。 */
  uint64_t shown = 0;
  /** blit 提交完成的通知物：等 GPU 写完这一格再去画。 */
  ID3D11Query* blitDone = nullptr;
};

struct VideoOutput;

// 共享纹理输出端的实现（定义在本文件后半段）：只有桌面有，外部只通过
// videoOutput* 这几个函数用它。
static VideoOutput* createOutput(int64_t skikoDevicePtr);
static void destroyOutput(VideoOutput* output);
static bool showFrame(VideoOutput* output, AVFrame* frame);
static int64_t bufferTexture(VideoOutput* output, int index);
static uint64_t frameState(VideoOutput* output);
static ID3D11Device* d3d11Device(VideoOutput* output);

namespace {

void releaseBuffers(VideoOutput* sink) {
  for (auto& [key, view] : sink->inputViews) view->Release();
  sink->inputViews.clear();
  for (auto& buffer : sink->buffers) {
    if (buffer.outputView) buffer.outputView->Release();
    if (buffer.resource) buffer.resource->Release();
    if (buffer.handle) CloseHandle(buffer.handle);
    if (buffer.texture) buffer.texture->Release();
    buffer = SharedTexture{};
  }
  if (sink->processor) {
    sink->processor->Release();
    sink->processor = nullptr;
  }
  if (sink->enumerator) {
    sink->enumerator->Release();
    sink->enumerator = nullptr;
  }
  sink->latest = kNoIndex;
}

/** 按输入纹理尺寸建视频处理器，按输出尺寸建共享纹理环。调用方持有 lock。 */
bool configureLocked(VideoOutput* sink, int inputWidth, int inputHeight,
                     int outputWidth, int outputHeight) {
  releaseBuffers(sink);
  sink->width = outputWidth;
  sink->height = outputHeight;
  sink->generation++;

  D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{};
  content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
  content.InputWidth = (UINT)inputWidth;
  content.InputHeight = (UINT)inputHeight;
  content.OutputWidth = (UINT)outputWidth;
  content.OutputHeight = (UINT)outputHeight;
  content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
  if (FAILED(sink->videoDevice->CreateVideoProcessorEnumerator(
          &content, &sink->enumerator)) ||
      FAILED(sink->videoDevice->CreateVideoProcessor(sink->enumerator, 0,
                                                     &sink->processor))) {
    av_log(nullptr, AV_LOG_ERROR,
           "hw video: video processor creation failed\n");
    releaseBuffers(sink);
    return false;
  }

  // Skia 的 D3D 后端只接受可渲染的 RGBA_8888 渲染目标，所以这里也只要 RGBA
  // 输出。
  UINT formatFlags = 0;
  if (FAILED(sink->enumerator->CheckVideoProcessorFormat(
          DXGI_FORMAT_R8G8B8A8_UNORM, &formatFlags)) ||
      !(formatFlags & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_OUTPUT)) {
    av_log(nullptr, AV_LOG_ERROR,
           "hw video: the video processor has no RGBA output\n");
    releaseBuffers(sink);
    return false;
  }

  D3D11_TEXTURE2D_DESC desc{};
  desc.Width = (UINT)outputWidth;
  desc.Height = (UINT)outputHeight;
  desc.MipLevels = 1;
  desc.ArraySize = 1;
  desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
  desc.SampleDesc.Count = 1;
  desc.Usage = D3D11_USAGE_DEFAULT;
  desc.BindFlags = D3D11_BIND_RENDER_TARGET;
  // NT 句柄：Skia 的 D3D12 设备用它打开同一块显存，跨 API 零拷贝。
  desc.MiscFlags =
      D3D11_RESOURCE_MISC_SHARED | D3D11_RESOURCE_MISC_SHARED_NTHANDLE;

  for (auto& buffer : sink->buffers) {
    if (FAILED(
            sink->device->CreateTexture2D(&desc, nullptr, &buffer.texture))) {
      av_log(nullptr, AV_LOG_ERROR,
             "hw video: shared texture creation failed\n");
      releaseBuffers(sink);
      return false;
    }
    IDXGIResource1* dxgiResource = nullptr;
    if (FAILED(buffer.texture->QueryInterface(
            __uuidof(IDXGIResource1),
            reinterpret_cast<void**>(&dxgiResource)))) {
      releaseBuffers(sink);
      return false;
    }
    const HRESULT hr = dxgiResource->CreateSharedHandle(
        nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
        nullptr, &buffer.handle);
    dxgiResource->Release();
    if (FAILED(hr)) {
      av_log(nullptr, AV_LOG_ERROR, "hw video: CreateSharedHandle failed\n");
      releaseBuffers(sink);
      return false;
    }
    if (FAILED(sink->skiaDevice->OpenSharedHandle(
            buffer.handle, __uuidof(ID3D12Resource),
            reinterpret_cast<void**>(&buffer.resource)))) {
      av_log(nullptr, AV_LOG_ERROR,
             "hw video: Skia's device cannot open the shared texture\n");
      releaseBuffers(sink);
      return false;
    }

    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC viewDesc{};
    viewDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    viewDesc.Texture2D.MipSlice = 0;
    if (FAILED(sink->videoDevice->CreateVideoProcessorOutputView(
            buffer.texture, sink->enumerator, &viewDesc, &buffer.outputView))) {
      releaseBuffers(sink);
      return false;
    }
  }
  av_log(nullptr, AV_LOG_INFO, "hw video: %dx%d shared ring ready\n",
         outputWidth, outputHeight);
  return true;
}

}  // namespace

static VideoOutput* createOutput(int64_t skikoDevicePtr) {
  ID3D12Device* skiaDevice = nullptr;
  IDXGIAdapter1* adapter = nullptr;
  if (!openSkiaDevice(skikoDevicePtr, &skiaDevice, &adapter)) {
    av_log(nullptr, AV_LOG_WARNING,
           "hw video: Skiko's Direct3D device is not shareable, staying on the "
           "cpu "
           "path\n");
    return nullptr;
  }

  auto* sink = new VideoOutput();
  sink->skiaDevice = skiaDevice;
  sink->adapter = adapter;

  // D3D11 必须建在 Skia 设备所在的 adapter 上：跨 adapter 的共享句柄打不开。
  const UINT flags = D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
  if (FAILED(D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr, flags,
                               nullptr, 0, D3D11_SDK_VERSION, &sink->device,
                               nullptr, &sink->context)) ||
      FAILED(sink->device->QueryInterface(
          __uuidof(ID3D11VideoDevice),
          reinterpret_cast<void**>(&sink->videoDevice))) ||
      FAILED(sink->context->QueryInterface(
          __uuidof(ID3D11VideoContext),
          reinterpret_cast<void**>(&sink->videoContext)))) {
    av_log(nullptr, AV_LOG_ERROR, "hw video: D3D11 device creation failed\n");
    destroyOutput(sink);
    return nullptr;
  }
  D3D11_QUERY_DESC queryDesc{};
  queryDesc.Query = D3D11_QUERY_EVENT;
  if (FAILED(sink->device->CreateQuery(&queryDesc, &sink->blitDone))) {
    // 拿不到通知物就退化成「提交完直接画」：偶尔可能和 Skia 的拷贝抢同一格，
    // 但比整条零拷贝路径废掉好。
    sink->blitDone = nullptr;
  }
  ID3D10Multithread* multithread = nullptr;
  if (SUCCEEDED(sink->device->QueryInterface(
          __uuidof(ID3D10Multithread),
          reinterpret_cast<void**>(&multithread)))) {
    multithread->SetMultithreadProtected(TRUE);
    multithread->Release();
  }
  return sink;
}

static void destroyOutput(VideoOutput* sink) {
  if (!sink) return;
  releaseBuffers(sink);
  if (sink->blitDone) sink->blitDone->Release();
  if (sink->processor) sink->processor->Release();
  if (sink->enumerator) sink->enumerator->Release();
  if (sink->videoContext) sink->videoContext->Release();
  if (sink->videoDevice) sink->videoDevice->Release();
  if (sink->context) sink->context->Release();
  if (sink->device) sink->device->Release();
  if (sink->adapter) sink->adapter->Release();
  if (sink->skiaDevice) sink->skiaDevice->Release();
  delete sink;
}

static ID3D11Device* d3d11Device(VideoOutput* sink) {
  if (!sink) return nullptr;
  // 交给 ffmpeg 持有：它的 device_uninit 会 Release。
  sink->device->AddRef();
  return sink->device;
}

/**
 * 把这一帧 blit 进环里空着的一格，并立刻定为当前显示帧。
 *
 * 帧是调用方持有的（surface 还挂在解码器池上），这里只读。返回前必须等 GPU
 * 写完这一格： Skia 的 D3D12 队列紧接着要读同一块显存，跨设备之间没有 fence。
 */
static bool showFrame(VideoOutput* sink, AVFrame* frame) {
  if (!sink || !frame || frame->format != AV_PIX_FMT_D3D11) return false;
  auto* texture = reinterpret_cast<ID3D11Texture2D*>(frame->data[0]);
  if (!texture) return false;
  const int slice = (int)(intptr_t)frame->data[1];
  D3D11_TEXTURE2D_DESC textureDesc{};
  texture->GetDesc(&textureDesc);

  std::unique_lock<std::mutex> guard(sink->lock);
  if (!sink->processor || sink->width != frame->width ||
      sink->height != frame->height || sink->buffers[0].texture == nullptr) {
    if (!configureLocked(sink, (int)textureDesc.Width, (int)textureDesc.Height,
                         frame->width, frame->height))
      return false;
  }

  // 轮着用格子。
  //
  // 正在显示的那一格要隔 kBufferCount 帧才回到手上，够 Skia 把上一帧拷走。
  const int index = (int)(++sink->serial % kBufferCount);
  D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC inputDesc{};
  inputDesc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
  inputDesc.Texture2D.MipSlice = 0;
  inputDesc.Texture2D.ArraySlice = (UINT)slice;
  auto& cached = sink->inputViews[{texture, slice}];
  if (cached == nullptr) {
    if (FAILED(sink->videoDevice->CreateVideoProcessorInputView(
            texture, sink->enumerator, &inputDesc, &cached)))
      return false;
  }

  D3D11_VIDEO_PROCESSOR_STREAM stream{};
  stream.Enable = TRUE;
  stream.pInputSurface = cached;
  stream.OutputIndex = 0;
  const HRESULT hr = sink->videoContext->VideoProcessorBlt(
      sink->processor, sink->buffers[index].outputView, 0, 1, &stream);
  if (FAILED(hr)) {
    av_log(nullptr, AV_LOG_ERROR, "hw video: VideoProcessorBlt failed 0x%lx\n",
           (unsigned long)hr);
    return false;
  }
  sink->context->Flush();
  if (sink->blitDone) {
    // 等 GPU 把这一格写完再交出去。blit 很小，通常几十微秒就回来。
    sink->context->End(sink->blitDone);
    for (int i = 0; i < 4000; ++i) {
      const HRESULT r = sink->context->GetData(sink->blitDone, nullptr, 0, 0);
      if (r != S_FALSE) break;
      Sleep(0);
    }
  }
  sink->latest = index;
  sink->shown++;
  return true;
}

static int64_t bufferTexture(VideoOutput* sink, int index) {
  if (!sink || index < 0 || index >= kBufferCount) return 0;
  std::lock_guard<std::mutex> guard(sink->lock);
  auto* resource = sink->buffers[index].resource;
  return (int64_t)(intptr_t)resource;
}

static uint64_t frameState(VideoOutput* sink) {
  if (!sink) return packState(0, kNoIndex, 0, 0, 0);
  std::lock_guard<std::mutex> guard(sink->lock);
  return packState(sink->generation, (uint64_t)sink->latest,
                   (uint64_t)sink->width, (uint64_t)sink->height, sink->shown);
}

extern "C" {

JNIEXPORT jlong JNICALL Java_soko_ekibun_ffmpeg_HwVideoSink_createNative(
    JNIEnv*, jobject, jlong skikoDevicePtr) {
  return (jlong)(intptr_t)createOutput((int64_t)skikoDevicePtr);
}

JNIEXPORT void JNICALL Java_soko_ekibun_ffmpeg_HwVideoSink_closeNative(
    JNIEnv*, jobject, jlong handle) {
  destroyOutput((VideoOutput*)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL Java_soko_ekibun_ffmpeg_HwVideoSink_bufferTextureNative(
    JNIEnv*, jobject, jlong handle, jint index) {
  return (jlong)bufferTexture((VideoOutput*)(intptr_t)handle, index);
}

JNIEXPORT jlong JNICALL Java_soko_ekibun_ffmpeg_HwVideoSink_frameStateNative(
    JNIEnv*, jobject, jlong handle) {
  return (jlong)frameState((VideoOutput*)(intptr_t)handle);
}

}  // extern "C"

// 建了设备后解码器才把 GPU 格式列进候选，这里挑 D3D11。
// 没有设备时列表里只有软件格式，取第一个即软解。
static enum AVPixelFormat selectHwFormat(AVCodecContext* ctx,
                                         const enum AVPixelFormat* formats) {
  if (ctx->hw_device_ctx != nullptr)
    for (const enum AVPixelFormat* p = formats; *p != AV_PIX_FMT_NONE; ++p)
      if (*p == AV_PIX_FMT_D3D11) return *p;
  return formats[0];
}

// 用播放器给的 D3D11 设备开硬解：解码纹理与共享纹理要同在一块 GPU 上。
// 失败返回 null，调用方回落软解。
static AVCodecContext* openSharedDeviceCodec(AVStream* stream,
                                             const AVCodec* decoder,
                                             VideoOutput* sink) {
  AVCodecContext* ctx = allocCodecContext(stream, decoder);
  if (!ctx) return nullptr;
  AVBufferRef* deviceRef = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_D3D11VA);
  if (deviceRef) {
    auto* deviceCtx = (AVHWDeviceContext*)deviceRef->data;
    auto* hwctx = (AVD3D11VADeviceContext*)deviceCtx->hwctx;
    // 设备归 ffmpeg 的 device_uninit 放，这里取的是 AddRef 过的副本。
    hwctx->device = d3d11Device(sink);
    if (hwctx->device && av_hwdevice_ctx_init(deviceRef) >= 0) {
      ctx->hw_device_ctx = deviceRef;
      ctx->get_format = selectHwFormat;
      // 帧带着 surface 上去（收帧不 unref），池子要装得下「参考帧 +
      // 上层压住的帧」。
      //
      // 池是静态的：装不下时 ffmpeg 报 Static surface pool size exceeded
      // 并丢帧，不是 EAGAIN。
      ctx->extra_hw_frames = 12;
      if (avcodec_open2(ctx, decoder, nullptr) >= 0) return ctx;
      avcodec_free_context(&ctx);
      return nullptr;
    }
    av_buffer_unref(&deviceRef);
  }
  avcodec_free_context(&ctx);
  return nullptr;
}

VideoOutput* videoOutputFrom(JNIEnv* env, jobject handle) {
  if (handle == nullptr) return nullptr;
  jclass cls = env->FindClass("java/lang/Long");
  if (cls == nullptr) return nullptr;
  if (!env->IsInstanceOf(handle, cls)) {
    env->DeleteLocalRef(cls);
    av_log(nullptr, AV_LOG_WARNING, "hw video: output is not a Long handle\n");
    return nullptr;
  }
  jmethodID longValue = env->GetMethodID(cls, "longValue", "()J");
  env->DeleteLocalRef(cls);
  if (longValue == nullptr) return nullptr;
  return (VideoOutput*)(intptr_t)env->CallLongMethod(handle, longValue);
}

VideoOutput* videoOutputOfCodec(AVCodecContext* ctx) {
  return (VideoOutput*)ctx->opaque;
}

AVCodecContext* videoOpenHardwareDecoder(JNIEnv* env, AVStream* stream,
                                         const AVCodec* software,
                                         jobject output) {
  VideoOutput* sink = videoOutputFrom(env, output);
  if (sink != nullptr) {
    AVCodecContext* shared = openSharedDeviceCodec(stream, software, sink);
    if (shared) {
      // 输出端记在 codec 上：open 时就按它定死了帧的形态。
      shared->opaque = sink;
      av_log(shared, AV_LOG_INFO, "video decode: d3d11va (shared device)\n");
      return shared;
    }
    av_log(nullptr, AV_LOG_WARNING,
           "hw video: shared-device decode failed, falling back\n");
  }
  // 设备必须由 FFmpeg 自建：多线程保护只在 create 里设。
  // 外部传进来的 device 带不上它，而解码与 postFrame 分处两个线程。
  AVCodecContext* ctx = allocCodecContext(stream, software);
  if (ctx) {
    AVBufferRef* device = nullptr;
    if (av_hwdevice_ctx_create(&device, AV_HWDEVICE_TYPE_D3D11VA, nullptr,
                               nullptr, 0) == 0) {
      // 设备归上下文所有，由 avcodec_free_context 放，这里不重复 unref。
      ctx->hw_device_ctx = device;
      ctx->get_format = selectHwFormat;
      if (avcodec_open2(ctx, software, nullptr) >= 0) {
        // 只在硬件这条路上记：软解帧没有纹理可写。
        ctx->opaque = sink;
        av_log(ctx, AV_LOG_INFO, "video decode: d3d11va\n");
        return ctx;
      }
    }
    avcodec_free_context(&ctx);
  }
  return nullptr;
}

void videoReleaseCodecOutput(AVCodecContext* ctx) {
  // 记的是共享纹理输出端，不归解码器持有，松掉引用即可。
  ctx->opaque = nullptr;
}

bool videoOutputShowFrame(VideoOutput* output, AVFrame* frame) {
  return showFrame(output, frame);
}
