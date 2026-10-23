#include <jni.h>

#include <cstring>
#include <map>
#include <vector>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/jni.h"
#include "libavformat/avformat.h"
#include "libavutil/imgutils.h"
#include "libswresample/swresample.h"
#include "libswscale/swscale.h"
}

jobject av_dict_to_map(JNIEnv* env, AVDictionary* d) {
  AVDictionaryEntry* t = nullptr;
  jclass class_hashmap = env->FindClass("java/util/HashMap");
  jmethodID hashmap_init = env->GetMethodID(class_hashmap, "<init>", "()V");
  jobject map = env->NewObject(class_hashmap, hashmap_init);
  jmethodID hashMap_put = env->GetMethodID(
      class_hashmap, "put",
      "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
  while ((t = av_dict_get(d, "", t, AV_DICT_IGNORE_SUFFIX))) {
    auto key = env->NewStringUTF(t->key);
    auto value = env->NewStringUTF(t->value);
    env->CallObjectMethod(map, hashMap_put, key, value);
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(value);
  }
  return map;
}

#ifdef ANDROID

#include <android/log.h>
#include <android/native_window_jni.h>

static void android_log_callback(void* ptr, int level, const char* fmt,
                                 va_list vl) {
  if (level > av_log_get_level()) return;

  va_list vl2;
  char line[1024];
  static int print_prefix = 1;

  va_copy(vl2, vl);
  av_log_format_line(ptr, level, fmt, vl2, line, sizeof(line), &print_prefix);
  va_end(vl2);

  __android_log_print(ANDROID_LOG_INFO, "FFMPEG", "%s", line);
}

#endif

JavaVM* javaVm;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* res) {
#ifdef ANDROID
  av_log_set_callback(android_log_callback);
  av_jni_set_java_vm(vm, nullptr);
#endif
  javaVm = vm;
  return JNI_VERSION_1_4;
}

// io_open 建出的 pb 记在账本里，由 io_close2 逐个销账；HLS 一次 open 会开多个
// pb。
struct FormatOpaque {
  jobject thiz;                   // AvFormat 实例的 GlobalRef
  std::vector<AVIOContext*> pbs;  // io_open 建出来、还没关的 pb
};

// 每个 pb 一份，`pb->opaque` 指向它：持有 AvIO 的 GlobalRef、复用读数组与缓存的
// methodID。
struct IoOpaque {
  jobject io;          // AvIO 实例的 GlobalRef
  jbyteArray arr;      // 复用数组的 GlobalRef，只增不减；首次读时才建
  jint cap;            // arr 的长度
  jmethodID readMid;   // read  "([BI)I"
  jmethodID seekMid;   // seek  "(II)I"
  jmethodID closeMid;  // close "()V"
};

// 关闭一个 pb：调 Java 侧 close()、还 GlobalRef、释放缓冲与上下文本体。
// io_close2 / destroyNative / 打开失败三处共用。
static void closeIoContext(JNIEnv* env, AVIOContext* pb) {
  if (!pb) return;
  auto io = (IoOpaque*)pb->opaque;
  if (io) {
    if (io->closeMid) {
      env->CallVoidMethod(io->io, io->closeMid);
      if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
      }
    }
    if (io->arr) env->DeleteGlobalRef(io->arr);
    if (io->io) env->DeleteGlobalRef(io->io);
    delete io;
  }
  // buffer 交 av_free，上下文本体交 avio_context_free。
  av_freep(&pb->buffer);
  avio_context_free(&pb);
}

// 把 [pb] 从账本中摘掉。
static void forgetPb(FormatOpaque* self, AVIOContext* pb) {
  for (auto it = self->pbs.begin(); it != self->pbs.end(); ++it) {
    if (*it == pb) {
      self->pbs.erase(it);
      return;
    }
  }
}

// 关闭账本上剩余的全部 pb，然后清空账本。
static void closeAllPbs(JNIEnv* env, FormatOpaque* self) {
  for (auto pb : self->pbs) closeIoContext(env, pb);
  self->pbs.clear();
}

extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_ffmpeg_AvFormat_initNative(
    JNIEnv* env, jobject thiz, jstring url) {
  auto ctx = avformat_alloc_context();
  if (!ctx) return 0;
  auto self = new FormatOpaque{env->NewGlobalRef(thiz), {}};
  ctx->opaque = self;
  ctx->io_open = [](AVFormatContext* s, AVIOContext** pb, const char* url,
                    int flags, AVDictionary** options) {
    JNIEnv* env;
    javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
    auto self = (FormatOpaque*)s->opaque;
    auto thiz = self->thiz;
    jclass cls = env->GetObjectClass(thiz);
    jobject io = env->GetObjectField(
        thiz, env->GetFieldID(cls, "io", "Lsoko/ekibun/ffmpeg/AvIO$Handler;"));
    jmethodID method =
        env->GetMethodID(env->GetObjectClass(io), "open",
                         "(Ljava/lang/String;)Lsoko/ekibun/ffmpeg/AvIO;");
    jstring strUrl = env->NewStringUTF(url);
    jobject ioCtx = env->CallObjectMethod(io, method, strUrl);
    env->DeleteLocalRef(strUrl);
    // 为这个 pb 建 IoOpaque，并缓存 read / seek / close 的 methodID。
    auto ioCtxCls = env->GetObjectClass(ioCtx);
    auto ctxOpaque = new IoOpaque{env->NewGlobalRef(ioCtx),
                                  nullptr,
                                  0,
                                  env->GetMethodID(ioCtxCls, "read", "([BI)I"),
                                  env->GetMethodID(ioCtxCls, "seek", "(II)I"),
                                  env->GetMethodID(ioCtxCls, "close", "()V")};
    env->DeleteLocalRef(ioCtxCls);
    env->DeleteLocalRef(ioCtx);
    // 初始缓冲 32768 字节，MP4 源之后按索引换掉。
    *pb = avio_alloc_context(
        (unsigned char*)av_malloc(32768), 32768, 0, ctxOpaque,
        [](void* opaque, uint8_t* buf, int buf_size) -> int {
          JNIEnv* env;
          javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
          auto io = (IoOpaque*)opaque;
          // 复用数组只增不减，容量不足时按请求长度重建。
          if (io->cap < buf_size) {
            if (io->arr) env->DeleteGlobalRef(io->arr);
            io->arr =
                (jbyteArray)env->NewGlobalRef(env->NewByteArray(buf_size));
            io->cap = buf_size;
            if (!io->arr) {
              if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
              }
              return AVERROR(ENOMEM);
            }
          }
          // 读取长度显式传入；返回值超过请求长度时截断到请求长度。
          int ret =
              env->CallIntMethod(io->io, io->readMid, io->arr, (jint)buf_size);
          if (ret > buf_size) {
            av_log(nullptr, AV_LOG_ERROR,
                   "AvIO read over-ran: ret=%d buf_size=%d\n", ret, buf_size);
            ret = buf_size;
          }
          if (ret > 0) env->GetByteArrayRegion(io->arr, 0, ret, (jbyte*)buf);
          // EOF 记为 AVERROR_EOF，其余负返回值打错误日志。
          if (ret < 0 && ret != AVERROR_EOF)
            av_log(nullptr, AV_LOG_ERROR, "AvIO read error ret=%d\n", ret);
          return ret;
        },
        nullptr,
        [](void* opaque, int64_t offset, int whence) -> int64_t {
          JNIEnv* env;
          javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
          auto io = (IoOpaque*)opaque;
          // 两个参数收窄为 jint，对应 AvIO.seek 的 (II)I。
          return env->CallIntMethod(io->io, io->seekMid, (jint)offset,
                                    (jint)whence);
        });
    // 记进账本，交给 io_close2 销账或关闭路径兜底。
    if (*pb) self->pbs.push_back(*pb);
    return 0;
  };
  ctx->io_close2 = [](AVFormatContext* s, AVIOContext* pb) {
    JNIEnv* env;
    javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
    // 先从账本摘掉，再关闭。
    forgetPb((FormatOpaque*)s->opaque, pb);
    closeIoContext(env, pb);
    return 0;
  };
  ctx->flags |= AVFMT_FLAG_CUSTOM_IO;
  const char* urlChars = env->GetStringUTFChars(url, nullptr);
  const int ret = avformat_open_input(&ctx, urlChars, nullptr, nullptr);
  env->ReleaseStringUTFChars(url, urlChars);
  if (ret < 0) {
    // 打开失败：关闭账本剩余的 pb，释放 thiz 的 GlobalRef 与 self。
    closeAllPbs(env, self);
    env->DeleteGlobalRef(self->thiz);
    delete self;
    return 0;
  }
  return (jlong)ctx;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_soko_ekibun_ffmpeg_AvFormat_getStreamsNative(JNIEnv* env, jobject thiz,
                                                  jlong pctx) {
  auto ctx = (AVFormatContext*)pctx;
  if (avformat_find_stream_info(ctx, nullptr) != 0) return nullptr;
  jclass streamClass = env->FindClass("soko/ekibun/ffmpeg/AvStream");
  // 时基一并递上去：`stream->time_base` 是这条流时间戳的最小刻度。
  jmethodID constructor =
      env->GetMethodID(streamClass, "<init>", "(JIIIIIIFJLjava/util/Map;)V");
  jobjectArray streams =
      env->NewObjectArray(ctx->nb_streams, streamClass, nullptr);
  for (int i = 0; i < ctx->nb_streams; ++i) {
    AVStream* stream = ctx->streams[i];
    auto metadata = av_dict_to_map(env, stream->metadata);
    // 流上没有时长时记为 0，总时长走 getDurationNative。
    const jlong durationUs =
        stream->duration == AV_NOPTS_VALUE
            ? 0
            : (jlong)(stream->duration * av_q2d(stream->time_base) *
                      AV_TIME_BASE);
    // 平均帧率优先，没有就退回 r_frame_rate：帧间隔的默认值由它换算。
    AVRational fr = stream->avg_frame_rate.num > 0 ? stream->avg_frame_rate
                                                   : stream->r_frame_rate;
    jobject streamObj = env->NewObject(
        streamClass, constructor, (jlong)stream, i,
        (jint)stream->codecpar->codec_type, (jint)stream->codecpar->sample_rate,
        (jint)stream->codecpar->ch_layout.nb_channels,
        (jint)stream->codecpar->width, (jint)stream->codecpar->height,
        (jfloat)av_q2d(fr), durationUs, metadata);
    env->DeleteLocalRef(metadata);
    env->SetObjectArrayElement(streams, i, streamObj);
    env->DeleteLocalRef(streamObj);
  }
  return streams;
}

/**
 * 容器总时长（AV_TIME_BASE 微秒）；容器未提供时返回 0。总时长从格式层取。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_ffmpeg_AvFormat_getDurationNative(JNIEnv*, jobject,
                                                   jlong pctx) {
  auto ctx = (AVFormatContext*)pctx;
  if (ctx->duration == AV_NOPTS_VALUE) return 0;
  return (jlong)ctx->duration;
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_ffmpeg_AvFormat_destroyNative(JNIEnv* env, jobject thiz,
                                               jlong pctx) {
  auto ctx = (AVFormatContext*)pctx;
  auto self = (FormatOpaque*)ctx->opaque;
  // 先关输入，再回收账本剩余的 pb、GlobalRef 与 self。
  avformat_close_input(&ctx);
  if (self) {
    closeAllPbs(env, self);
    env->DeleteGlobalRef(self->thiz);
    delete self;
  }
}

extern "C" JNIEXPORT jint JNICALL Java_soko_ekibun_ffmpeg_AvFormat_seekToNative(
    JNIEnv* env, jobject thiz, jlong pctx, jlong ts, jint stream_index,
    jlong min_ts, jlong max_ts, jint flags) {
  return avformat_seek_file((AVFormatContext*)pctx, stream_index, min_ts, ts,
                            max_ts, flags);
}

extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_ffmpeg_AvPacket_initNative(JNIEnv* env, jobject thiz) {
  return (jlong)av_packet_alloc();
}
extern "C" JNIEXPORT void JNICALL Java_soko_ekibun_ffmpeg_AvPacket_closeNative(
    JNIEnv* env, jobject thiz, jlong ptr) {
  return av_packet_free((AVPacket**)&ptr);
}

extern "C" JNIEXPORT jint JNICALL
Java_soko_ekibun_ffmpeg_AvFormat_getPacketNative(JNIEnv* env, jobject thiz,
                                                 jlong ctx, jlong packet) {
  int ret = av_read_frame((AVFormatContext*)ctx, (AVPacket*)packet);
  // 非 EOF 的负返回值打错误日志。
  if (ret < 0 && ret != AVERROR_EOF)
    av_log(nullptr, AV_LOG_ERROR,
           "av_read_frame error ret=%d (folded to EOF by caller)\n", ret);
  return ret ? fmin(ret, -1) : ((AVPacket*)packet)->stream_index;
}
extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_ffmpeg_AvCodec_initNative(
    JNIEnv* env, jobject thiz, jlong stream) {
  auto pstream = (AVStream*)stream;
  auto pCodec = avcodec_find_decoder(pstream->codecpar->codec_id);
  if (!pCodec) return 0;
  AVCodecContext* ctx = avcodec_alloc_context3(pCodec);
  int ret = avcodec_parameters_to_context(ctx, pstream->codecpar);
  if (ret == 0) {
    ret = avcodec_open2(ctx, pCodec, nullptr);
    if (ret == 0) return (jlong)ctx;
  }
  if (ctx) avcodec_free_context(&ctx);
  return 0;
}
// 解码产出按 FFmpeg 的收/发分离协议收集：send_packet 返回 EAGAIN 时先收帧，
// receive_frame 循环到 EAGAIN 或 EOF。一个 packet 可能产出 0 帧或多帧，所以返回
// AvFrame 数组。
static jobjectArray newAvFrameArray(JNIEnv* env, jint size) {
  jclass cls = env->FindClass("soko/ekibun/ffmpeg/AvFrame");
  if (!cls) return nullptr;
  return env->NewObjectArray(size, cls, nullptr);
}

static jobject newAvFrame(JNIEnv* env, AVFrame* frame, AVStream* stream) {
  jclass cls = env->FindClass("soko/ekibun/ffmpeg/AvFrame");
  jmethodID constructor = env->GetMethodID(cls, "<init>", "(JJII)V");
  return env->NewObject(cls, constructor, (jlong)frame,
                        (jlong)(frame->best_effort_timestamp *
                                av_q2d(stream->time_base) * AV_TIME_BASE),
                        (jint)frame->width, (jint)frame->height);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_soko_ekibun_ffmpeg_AvCodec_sendPacketAndGetFramesNative(
    JNIEnv* env, jobject thiz, jlong pctx, jlong stream, jlong packet) {
  auto ctx = (AVCodecContext*)pctx;
  auto pstream = (AVStream*)stream;

  auto empty = newAvFrameArray(env, 0);
  if (!empty) return nullptr;

  std::vector<AVFrame*> out;
  // 取出解码器已解出的全部帧。
  auto receiveAll = [&]() {
    while (true) {
      auto frame = av_frame_alloc();
      if (!frame) break;
      int r = avcodec_receive_frame(ctx, frame);
      if (r == 0) {
        out.push_back(frame);
        continue;
      }
      av_frame_free(&frame);
      // EAGAIN 表示需要继续喂包，EOF 表示 drain 完成。
      break;
    }
  };

  // packet == 0 表示冲刷解码器内部缓存的尾帧。
  int ret = avcodec_send_packet(ctx, packet ? (AVPacket*)packet : nullptr);
  // 输入队列满时先收帧再重发，最多重试 kMaxSendRetry 次。
  const int kMaxSendRetry = 16;
  for (int i = 0; ret == AVERROR(EAGAIN) && packet != 0 && i < kMaxSendRetry;
       ++i) {
    receiveAll();
    ret = avcodec_send_packet(ctx, (AVPacket*)packet);
  }
  if (ret == AVERROR(EAGAIN)) {
    // 重试后仍收不进去，记录错误日志。
    av_log(ctx, AV_LOG_ERROR,
           "avcodec_send_packet EAGAIN x%d, packet dropped: stream=%d size=%d "
           "(frames until the next keyframe will be wrong)\n",
           kMaxSendRetry, pstream->index,
           packet ? ((AVPacket*)packet)->size : 0);
  } else if (ret < 0 && ret != AVERROR_EOF) {
    av_log(
        ctx, AV_LOG_ERROR,
        "avcodec_send_packet failed ret=%d (stream=%d, %d frame(s) handed out "
        "anyway)\n",
        ret, pstream->index, (int)out.size());
  }

  receiveAll();

  auto arr = newAvFrameArray(env, (jint)out.size());
  if (!arr) {
    for (auto frame : out) av_frame_free(&frame);
    return nullptr;
  }
  for (size_t i = 0; i < out.size(); ++i) {
    jobject frameObj = newAvFrame(env, out[i], pstream);
    env->SetObjectArrayElement(arr, (jsize)i, frameObj);
    env->DeleteLocalRef(frameObj);
  }
  return arr;
}
extern "C" JNIEXPORT void JNICALL Java_soko_ekibun_ffmpeg_AvCodec_flushNative(
    JNIEnv* env, jobject thiz, jlong ctx) {
  // 丢弃解码器内部缓冲（seek 用）。
  avcodec_flush_buffers((AVCodecContext*)ctx);
}
extern "C" JNIEXPORT void JNICALL Java_soko_ekibun_ffmpeg_AvCodec_closeNative(
    JNIEnv* env, jobject thiz, jlong pctx) {
  auto ctx = (AVCodecContext*)pctx;
  avcodec_free_context(&ctx);
}

extern "C" JNIEXPORT void JNICALL Java_soko_ekibun_ffmpeg_AvFrame_closeNative(
    JNIEnv* env, jobject thiz, jlong ptr) {
  av_frame_free((AVFrame**)&ptr);
}

struct SWContext {
  double speedRatio = 1;
  // 音频
  int64_t sampleRate = 0;
  int64_t channels = 0;
  int64_t audioFormat = AV_SAMPLE_FMT_NONE;
  uint8_t* audioBuffer = nullptr;
  int64_t audioBufferSize = 0;
  // 视频
  int64_t width = 0;
  int64_t height = 0;
  // 转码目标格式固定为 AV_PIX_FMT_RGBA。
  uint8_t* videoBuffer = nullptr;
  int64_t videoBufferSize = 0;
  // 以下为内部状态
  SwrContext* _swrCtx = nullptr;
  AVChannelLayout _srcChannelLayout;
  AVSampleFormat _srcAudioFormat = AV_SAMPLE_FMT_NONE;
  int64_t _srcSampleRate = 0;
  uint8_t* _audioBuffer1 = nullptr;
  unsigned int _audioBufferLen = 0;
  unsigned int _audioBufferLen1 = 0;
  SwsContext* _swsCtx = nullptr;
  AVPixelFormat _srcVideoFormat = AV_PIX_FMT_NONE;
  unsigned int _videoBufferLen = 0;
  uint8_t* _videoData[4]{};
  int _linesize[4]{};
};

int64_t postFrameAudio(SWContext* ctx, AVFrame* frame) {
  if (ctx->audioFormat == AV_SAMPLE_FMT_NONE) return -1;
  int sampleRate = (int)round(frame->sample_rate * ctx->speedRatio);
  if (!ctx->_swrCtx ||
      av_channel_layout_compare(&ctx->_srcChannelLayout, &frame->ch_layout) !=
          0 ||
      ctx->_srcAudioFormat != frame->format ||
      ctx->_srcSampleRate != sampleRate) {
    swr_free(&ctx->_swrCtx);
    AVChannelLayout out_ch_layout;
    av_channel_layout_default(&out_ch_layout, ctx->channels);
    int ret = swr_alloc_set_opts2(
        &ctx->_swrCtx, &out_ch_layout, (AVSampleFormat)ctx->audioFormat,
        ctx->sampleRate, &frame->ch_layout, (AVSampleFormat)frame->format,
        sampleRate, 0, nullptr);
    av_channel_layout_uninit(&out_ch_layout);

    if (!ctx->_swrCtx || swr_init(ctx->_swrCtx) < 0) {
      swr_free(&ctx->_swrCtx);
      return -1;
    }
    if (av_channel_layout_copy(&ctx->_srcChannelLayout, &frame->ch_layout) < 0)
      return -1;
    ctx->_srcAudioFormat = (AVSampleFormat)frame->format;
    ctx->_srcSampleRate = sampleRate;
  }
  int inCount = frame->nb_samples;
  int outCount = (int)(inCount * ctx->sampleRate / sampleRate + 256);
  int outSize = av_samples_get_buffer_size(nullptr, ctx->channels, outCount,
                                           (AVSampleFormat)ctx->audioFormat, 0);
  if (outSize < 0) return -2;
  av_fast_malloc(&ctx->_audioBuffer1, &ctx->_audioBufferLen, outSize);
  if (!ctx->_audioBuffer1) return -3;
  int frameCount = swr_convert(ctx->_swrCtx, &ctx->_audioBuffer1, outCount,
                               (const uint8_t**)frame->extended_data, inCount);
  ctx->audioBufferSize =
      frameCount * av_get_bytes_per_sample((AVSampleFormat)ctx->audioFormat) *
      ctx->channels;
  uint8_t* buffer = ctx->_audioBuffer1;
  unsigned int bufferLen = ctx->_audioBufferLen1;
  ctx->_audioBuffer1 = ctx->audioBuffer;
  ctx->_audioBufferLen1 = ctx->_audioBufferLen;
  ctx->audioBuffer = buffer;
  ctx->_audioBufferLen = bufferLen;
  return 0;
}

int64_t postFrameVideo(SWContext* ctx, AVFrame* frame) {
  if (!ctx->_swsCtx || ctx->width != frame->width ||
      ctx->height != frame->height || ctx->_srcVideoFormat != frame->format) {
    if (ctx->_swsCtx) sws_freeContext(ctx->_swsCtx);
    ctx->_swsCtx = nullptr;
    ctx->videoBufferSize = av_image_get_buffer_size(
        AV_PIX_FMT_RGBA, frame->width, frame->height, 1);
    if (!ctx->videoBufferSize) return -1;
    ctx->width = frame->width;
    ctx->height = frame->height;
    av_fast_malloc(&ctx->videoBuffer, &ctx->_videoBufferLen,
                   ctx->videoBufferSize);
    if (!ctx->videoBuffer) return -1;
    av_image_fill_arrays(ctx->_videoData, ctx->_linesize, ctx->videoBuffer,
                         AV_PIX_FMT_RGBA, ctx->width, ctx->height, 1);
    ctx->_swsCtx = sws_getContext(
        frame->width, frame->height, (AVPixelFormat)frame->format, ctx->width,
        ctx->height,
        // 输出像素格式与 av_image_fill_arrays 分配视频缓冲时用的格式一致，
        // sws_scale 按这个格式写入。
        AV_PIX_FMT_RGBA, SWS_POINT, nullptr, nullptr, nullptr);
    // 回写源像素格式缓存键，供上面的条件判断使用。
    ctx->_srcVideoFormat = (AVPixelFormat)frame->format;
  }
  if (!ctx->_swsCtx) return -1;
  sws_scale(ctx->_swsCtx, frame->data, frame->linesize, 0, frame->height,
            ctx->_videoData, ctx->_linesize);
  return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_ffmpeg_AvSurfaceContext_initNative(JNIEnv* env, jobject thiz,
                                                    jint sample_rate,
                                                    jint channels,
                                                    jint audio_format) {
  auto ret = new SWContext();
  ret->speedRatio = 1;
  ret->sampleRate = sample_rate;
  ret->channels = channels;
  ret->audioFormat = audio_format;
  return (jlong)ret;
}
extern "C" JNIEXPORT jint JNICALL
Java_soko_ekibun_ffmpeg_AvSurfaceContext_postFrameNative(
    JNIEnv* env, jobject thiz, jlong ctx, jint codec_type, jlong frame) {
  switch (codec_type) {
    case AVMEDIA_TYPE_AUDIO:
      return postFrameAudio((SWContext*)ctx, (AVFrame*)frame);
    case AVMEDIA_TYPE_VIDEO:
      return postFrameVideo((SWContext*)ctx, (AVFrame*)frame);
    default:
      return -1;
  }
}
// 把 native 缓冲拷进 Kotlin 的 byte[]：长度一致时覆写传入的数组，不一致时新建。
//
// 返回 nullptr 表示本轮没有缓冲。
// 调用方需同步消费这块内存，不能留存引用。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_soko_ekibun_ffmpeg_AvSurfaceContext_getBufferNative(
    JNIEnv* env, jobject thiz, jlong pctx, jint codec_type, jbyteArray buf) {
  auto ctx = (SWContext*)pctx;
  uint8_t* addr = nullptr;
  jlong size = 0;
  switch (codec_type) {
    case AVMEDIA_TYPE_AUDIO:
      if (ctx->audioBufferSize > 0) {
        addr = ctx->audioBuffer;
        size = ctx->audioBufferSize;
      }
      break;
    case AVMEDIA_TYPE_VIDEO:
      if (ctx->videoBufferSize > 0) {
        addr = ctx->videoBuffer;
        size = ctx->videoBufferSize;
      }
      break;
    default:
      return nullptr;
  }
  if (size <= 0) return nullptr;
  // 1. 获取传入 buf 的长度
  jsize buflen = buf ? env->GetArrayLength(buf) : 0;
  jbyteArray ret = nullptr;
  // 2. 传入数组长度一致时复用它
  if (buf && buflen == (jsize)size) {
    ret = buf;
  } else {
    // 长度不匹配或传入了 NULL，创建新数组
    ret = env->NewByteArray((jsize)size);
    if (!ret) {
      // 创建失败返回 NULL
      return nullptr;
    }
  }
  // 3. 写入数据
  env->SetByteArrayRegion(ret, 0, (jsize)size, (jbyte*)addr);
  return ret;
}
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_ffmpeg_AvSurfaceContext_copyPixelsNative(JNIEnv* env,
                                                          jobject thiz,
                                                          jlong src, jlong dst,
                                                          jint bytes) {
  // 纯 memcpy 拷贝像素，调用方保证目标缓冲区足够大。
  if (src == 0 || dst == 0 || bytes <= 0) return;
  memcpy((void*)dst, (const void*)src, (size_t)bytes);
}

extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_ffmpeg_AvSurfaceContext_closeNative(JNIEnv* env, jobject thiz,
                                                     jlong pctx) {
  auto ctx = (SWContext*)pctx;
  if (!ctx) return;
  if (ctx->_swsCtx) sws_freeContext(ctx->_swsCtx);
  if (ctx->_swrCtx) swr_free(&ctx->_swrCtx);
  // 释放 av_fast_malloc 分配的音频与视频输出缓冲。
  av_free(ctx->audioBuffer);
  av_free(ctx->_audioBuffer1);
  av_free(ctx->videoBuffer);
  delete ctx;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_soko_ekibun_ffmpeg_AvSurfaceContext_speedRatioNative(JNIEnv* env,
                                                          jobject thiz,
                                                          jlong pctx,
                                                          jfloat new_value) {
  auto ctx = (SWContext*)pctx;
  if (!ctx) return 1;
  if (new_value > 0) ctx->speedRatio = new_value;
  return ctx->speedRatio;
}

#ifdef ANDROID

extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_ffmpeg_AndroidSurfaceContext_copyBufferToSurface(
    JNIEnv* env, jclass clazz, jlong pctx, jobject surface) {
  auto ctx = (SWContext*)pctx;
  if (ctx->videoBufferSize <= 0 || ctx->width <= 0 || ctx->height <= 0)
    return false;

  ANativeWindow* nativeWindow = ANativeWindow_fromSurface(env, surface);
  if (nativeWindow == nullptr) return false;
  // 把 Surface 的缓冲几何设为 RGBA_8888 与当前宽高。
  if (ANativeWindow_setBuffersGeometry(nativeWindow, (int32_t)ctx->width,
                                       (int32_t)ctx->height,
                                       WINDOW_FORMAT_RGBA_8888) != 0) {
    ANativeWindow_release(nativeWindow);
    return false;
  }
  ANativeWindow_Buffer native_outBuffer;
  if (ANativeWindow_lock(nativeWindow, &native_outBuffer, NULL) != 0) {
    ANativeWindow_release(nativeWindow);
    return false;
  }
  uint8_t* src = ctx->videoBuffer;
  uint8_t* dst = (uint8_t*)native_outBuffer.bits;
  if (src == nullptr || dst == nullptr) {
    ANativeWindow_unlockAndPost(nativeWindow);
    ANativeWindow_release(nativeWindow);
    return false;
  }
  // 按 stride 逐行拷贝。
  const int rowBytes = (int)(ctx->width * 4);
  const int dstStride = (int)(native_outBuffer.stride * 4);
  const int rows = (int)ctx->height < native_outBuffer.height
                       ? (int)ctx->height
                       : native_outBuffer.height;
  for (int y = 0; y < rows; y++) {
    memcpy(dst + (size_t)y * dstStride, src + (size_t)y * rowBytes,
           (size_t)rowBytes);
  }
  ANativeWindow_unlockAndPost(nativeWindow);
  ANativeWindow_release(nativeWindow);
  return true;
}

#endif
