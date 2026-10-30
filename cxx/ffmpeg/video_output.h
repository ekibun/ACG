#pragma once

#include <jni.h>

#include <cstdint>

struct AVFrame;
struct AVStream;
struct AVCodec;
struct AVCodecContext;

// 视频输出端：把解码出来的画面送去哪里。
//
// 桌面是共享纹理环（D3D11 视频处理器 + NT 共享纹理），Android 是 Surface。
//
// 两端差异全在 video_output.windows.cpp 与 video_output.android.cpp。
//
// handle 是 jobject：桌面装着 VideoOutput* 的 Long，Android 是 Surface。
//
// 它只是 JNI 调用期有效的本地引用，不得存下来。
struct VideoOutput;

// 从 Java 交上来的上屏目标取输出端；没有共享纹理输出端（Android）时返回 null。
VideoOutput* videoOutputFrom(JNIEnv* env, jobject handle);

// 解码线程里按 codec 取输出端；没有时返回 null。
VideoOutput* videoOutputOfCodec(AVCodecContext* ctx);

// 开硬件解码器：拿不到返回 null，调用方回落软解。
AVCodecContext* videoOpenHardwareDecoder(JNIEnv* env, AVStream* stream,
                                         const AVCodec* software,
                                         jobject output);

// 把这一帧显示出去：桌面把它的硬解纹理 blit 进共享纹理环并顶到前台，Android
// 无事可做。
//
// 直出帧自带 buffer，上屏在 presentVideoFrame 里。
//
// 帧由调用方持有（收帧时没有 unref），这里只读；返回 true 表示像素已经进了环。
bool videoOutputShowFrame(VideoOutput* output, AVFrame* frame);

// 释放挂在解码器上的输出端资源，必须在 avcodec_free_context 之前调。
void videoReleaseCodecOutput(AVCodecContext* ctx);
