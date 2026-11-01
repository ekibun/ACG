// Android 的视频输出端：MediaCodec 直出 Surface。
//
// 平台差异只在 video_output.windows.cpp 与 video_output.android.cpp。
//
// 调用方（ffmpeg.cpp）只认 video_output.h。
#include "video_output.h"

#include <climits>
#include <cstdio>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/mediacodec.h"
#include "libavformat/avformat.h"
}

#include "ffmpeg_internal.h"

VideoOutput* videoOutputFrom(JNIEnv*, jobject) {
  // Android 的 Surface 不是共享纹理输出端。
  return nullptr;
}

VideoOutput* videoOutputOfCodec(AVCodecContext*) {
  // Android 的 opaque 存着 AVMediaCodecContext，直出帧自带 buffer。
  return nullptr;
}

// 直出帧自带 buffer、上屏是 release_buffer（在 presentVideoFrame
// 里），这里无事可做。
bool videoOutputShowFrame(VideoOutput*, AVFrame*) { return false; }

// Android 的帧占的是 codec 的输出 buffer（`hw_buffer_count`），没有静态池；
// 取空时 receive_frame 给 EAGAIN 而不是塞满后的 ENOMEM，没有窗口要数。
int videoOutputFrameRoom(AVCodecContext*) { return INT_MAX; }

void videoOutputHoldFrame(AVCodecContext*, AVFrame*) {}

void videoOutputReleaseFrame(AVFrame*) {}

AVCodecContext* videoOpenHardwareDecoder(JNIEnv*, AVStream* stream,
                                         const AVCodec* software,
                                         jobject surface) {
  // 给了 Surface 就让它直接渲染进去，像素不落 CPU；
  // 没给时它自己按 NV12 拷回内存。
  char name[64];
  snprintf(name, sizeof(name), "%s_mediacodec", software->name);
  const AVCodec* mediaCodec = avcodec_find_decoder_by_name(name);
  AVCodecContext* ctx = allocCodecContext(stream, mediaCodec);
  if (!ctx) return nullptr;
  // 它报的呈现时间是微秒，把画面拷出输出 buffer 时按这个时基换算
  // （mediacodecdec_common.c:471）；不给的话 pts 会被当成刻度值。
  ctx->pkt_timebase = stream->time_base;
  // mediacodec 只提供 MEDIACODEC 一种输出。默认的 get_format 会拿容器带来的
  // pix_fmt（yuv420p）去匹配，匹配不上就退回 ByteBuffer 模式，surface 白挂。
  ctx->pix_fmt = AV_PIX_FMT_NONE;
  ctx->get_format = [](AVCodecContext*, const enum AVPixelFormat* formats) {
    return formats[0];
  };
  // 必须在 open 之前挂上：mediacodec 的 init 从 hwaccel_context 取 surface。
  AVMediaCodecContext* mediaCodecSurface = nullptr;
  if (surface != nullptr) {
    mediaCodecSurface =
        (AVMediaCodecContext*)av_mallocz(sizeof(AVMediaCodecContext));
    if (mediaCodecSurface == nullptr ||
        av_mediacodec_default_init(ctx, mediaCodecSurface, surface) < 0) {
      av_free(mediaCodecSurface);
      mediaCodecSurface = nullptr;
      av_log(ctx, AV_LOG_WARNING,
             "video decode: no surface, frames stay in memory\n");
    }
  }
  if (avcodec_open2(ctx, mediaCodec, nullptr) >= 0) {
    // 直出用的 surface 归解码器自己：opaque 是 ffmpeg 留给调用方的字段，
    // 挂在 ctx 上就不必再维护一张「ctx → surface」的全局表。
    ctx->opaque = mediaCodecSurface;
    av_log(ctx, AV_LOG_INFO, "video decode: %s%s\n", name,
           mediaCodecSurface != nullptr ? " (surface)" : "");
    return ctx;
  }
  av_free(mediaCodecSurface);
  avcodec_free_context(&ctx);
  return nullptr;
}

void videoReleaseCodecOutput(AVCodecContext* ctx) {
  if (ctx->opaque == nullptr) return;
  auto* surface = (AVMediaCodecContext*)ctx->opaque;
  ctx->opaque = nullptr;
  // default_free 自己会 av_freep(hwaccel_context)，不能再 av_free：
  // 二次释放会被 Scudo 判成 invalid chunk（连点 load 的闪退）。
  av_mediacodec_default_free(ctx);
  // 它取不到 JNIEnv 时会提前返回、什么都不放，这种残留我们自己收掉。
  if (ctx->hwaccel_context == surface) av_freep(&ctx->hwaccel_context);
}
