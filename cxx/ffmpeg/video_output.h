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

// 在途的硬解帧：解码器已经交出去、Java 还没还回来的张数。上限是 `ctx->extra_hw_frames`
// —— 池 = 解码器自身所需 + extra（avcodec.h 的原文是 extra 只加在内部需要之上），压在
// extra 以内时解码器那一份一分没动，送包永远拿得到 surface。
//
// 窗口满时收帧返空数组、帧留在解码器里，送包随即撞输入单槽 EAGAIN，压力回到解复用。
// 没有输出端（Android、软解回落）时不受限：room 恒为 INT_MAX，hold / release 空转。
//
// 计数挂在 `ctx->opaque` 指向的输出端上；`Hold` 顺手把解码器挂到 `frame->opaque`，
// `Release` 从那取回来减账。契约是「帧先于解码器归还」（`FFPlayer.closeAsync` 在 pause
// 之后先关帧、再关 codec），否则 Release 摸的是已经释放的上下文。
int videoOutputFrameRoom(AVCodecContext* ctx);
void videoOutputHoldFrame(AVCodecContext* ctx, AVFrame* frame);
void videoOutputReleaseFrame(AVFrame* frame);

// 释放挂在解码器上的输出端资源，必须在 avcodec_free_context 之前调。
void videoReleaseCodecOutput(AVCodecContext* ctx);
