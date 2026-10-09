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

// io_open 建出来的 pb 得自己记账：CUSTOM_IO 下 ffmpeg 不替我们关（avformat.h 的
// AVFMT_FLAG_CUSTOM_IO），而 pb 的释放点只有 io_close2 一条 —— 顶层 pb 被
// avformat_close_input 置 NULL 跳过（demux.c:388-396），嵌套 pb 由 demuxer 自己
// ff_format_io_close。**账本是集合、且要能被 io_close2 销账**：HLS 一次 open
// 里会连续开 master / variant / segment 多个
// pb，单槽记账会让失败路径二次关闭一个已经释放的 pb（崩因链与 hs_err 证据见
// cxx/AGENTS.md 的 ffmpeg 一节）。
struct FormatOpaque {
  jobject thiz;                   // AvFormat 实例的 GlobalRef
  std::vector<AVIOContext*> pbs;  // io_open 建出来、还没关的 pb
};

// 还一个已经不在账上的 pb：Java 侧 close() → 还 ioCtx 的 GlobalRef → 释放缓冲与
// 上下文本体。三个关闭点（io_close2 / destroyNative / 打开失败）共用这一份。
static void closeIoContext(JNIEnv* env, AVIOContext* pb) {
  if (!pb) return;
  auto ioCtx = (jobject)pb->opaque;
  if (ioCtx) {
    if (jmethodID close =
            env->GetMethodID(env->GetObjectClass(ioCtx), "close", "()V")) {
      env->CallVoidMethod(ioCtx, close);
      if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
      }
    } else if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    env->DeleteGlobalRef(ioCtx);
  }
  // ⚠️ **不能用 avio_close**：那是给 avio_open 的 URLContext 系 pb 用的 ——
  // 它把 s->opaque 当 URLContext* 送去 ffurl_close（avio.c），对我们这个在
  // opaque 里放 GlobalRef(jobject) 的自定义 pb 是类型混淆，直接访问违例
  // （2026-10-02 实测：hs_err 落在 avio_close 内 `mov 0x80(%rdx)`）。
  // 自定义 pb 的正确关法：buffer 由调用方 av_free（avio.h 的契约
  // 「AVIOContext.buffer … must be later freed with av_free()」），
  // 上下文本体交 avio_context_free（它只还 struct，不管 buffer）。
  av_freep(&pb->buffer);
  avio_context_free(&pb);
}

// 把 [pb] 从账上摘掉（在就摘，不在就什么都不做）。
static void forgetPb(FormatOpaque* self, AVIOContext* pb) {
  for (auto it = self->pbs.begin(); it != self->pbs.end(); ++it) {
    if (*it == pb) {
      self->pbs.erase(it);
      return;
    }
  }
}

// 收干净账上剩下的全部 pb。**必须在 ffmpeg 那一侧不再持有它们之后调**：
// io_close2 跑过就从账上摘掉了，剩下的要么是顶层 pb（avformat_close_input 对
// CUSTOM_IO 置 NULL 跳过），要么是 demuxer 漏关的嵌套 pb。
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
    long bufferSize = env->CallLongMethod(
        ioCtx,
        env->GetMethodID(env->GetObjectClass(ioCtx), "getBufferSize", "()J"));
    *pb = avio_alloc_context(
        (unsigned char*)av_malloc(bufferSize), bufferSize, 0,
        env->NewGlobalRef(ioCtx),
        [](void* opaque, uint8_t* buf, int buf_size) -> int {
          JNIEnv* env;
          javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
          auto ioCtx = (jobject)opaque;
          jmethodID method =
              env->GetMethodID(env->GetObjectClass(ioCtx), "read", "([B)I");
          jbyteArray arr = env->NewByteArray(buf_size);
          int ret = env->CallIntMethod(ioCtx, method, arr);
          if (ret > 0) env->GetByteArrayRegion(arr, 0, ret, (jbyte*)buf);
          env->DeleteLocalRef(arr);
          // AvIO 的契约是 **AVERROR_EOF 表示
          // EOF、其余负数表示出错**（FileIO.read / HttpIO.read 都把各自的 EOF
          // 翻成了 AVERROR_EOF）。错误在这一层就得出 声：aviobuf 的 fill_buffer
          // 对任何负返回值都置 eof_reached 并把错误码 记进
          // s->error（aviobuf.c:551-558），此后 av_read_frame 一律以 EOF 形态
          // 上浮 —— 不在这里打日志，IO 故障在外面看起来就是"播完了"。
          if (ret < 0 && ret != AVERROR_EOF)
            av_log(nullptr, AV_LOG_ERROR, "AvIO read error ret=%d\n", ret);
          return ret;
        },
        nullptr,
        [](void* opaque, int64_t offset, int whence) -> int64_t {
          JNIEnv* env;
          javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
          auto ioCtx = (jobject)opaque;
          jmethodID method =
              env->GetMethodID(env->GetObjectClass(ioCtx), "seek", "(II)I");
          return env->CallIntMethod(ioCtx, method, offset, whence);
        });
    // 记进账本，交给 io_close2 销账或关闭路径兜底（理由见 FormatOpaque
    // 的注释）。
    if (*pb) self->pbs.push_back(*pb);
    return 0;
  };
  ctx->io_close2 = [](AVFormatContext* s, AVIOContext* pb) {
    JNIEnv* env;
    javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
    // 先销账再关：销账之后失败路径与 destroyNative 就不会再碰这个 pb 了。
    forgetPb((FormatOpaque*)s->opaque, pb);
    closeIoContext(env, pb);
    return 0;
  };
  ctx->flags |= AVFMT_FLAG_CUSTOM_IO;
  const char* urlChars = env->GetStringUTFChars(url, nullptr);
  const int ret = avformat_open_input(&ctx, urlChars, nullptr, nullptr);
  env->ReleaseStringUTFChars(url, urlChars);
  if (ret < 0) {
    // ctx 已经被 ffmpeg free 掉了（失败路径见 FormatOpaque
    // 的注释），destroyNative 永远不会再被调 —— 账上剩下的 pb、thiz 的
    // GlobalRef 都要在这里收干净，一个不留。**只收账上剩下的**：read_header
    // 中途失败的 demuxer 可能已经自己关过几个嵌套 pb（io_close2
    // 已销账），再关一遍就是 use-after-free。
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
  // 时基一并递上去：`stream->time_base` 是这条流时间戳的**最小刻度**。
  // 容器级 seek 把微秒折回刻度时，会吃掉半格以内的偏移
  // （libavformat/seek.c 的 seek_frame_internal，mov 没实现 read_seek2
  // 就走这条路）—— 上层要算「比半格大」的步长，非知道它不可。
  jmethodID constructor =
      env->GetMethodID(streamClass, "<init>", "(JIIIIIIIIJLjava/util/Map;)V");
  jobjectArray streams =
      env->NewObjectArray(ctx->nb_streams, streamClass, nullptr);
  for (int i = 0; i < ctx->nb_streams; ++i) {
    AVStream* stream = ctx->streams[i];
    auto metadata = av_dict_to_map(env, stream->metadata);
    // 流上没有时长（HLS 只把总时长记在格式层）时 NOPTS 乘出来是垃圾值 —— 记 0
    // 表示未知，总时长走 getDurationNative。
    const jlong durationUs =
        stream->duration == AV_NOPTS_VALUE
            ? 0
            : (jlong)(stream->duration * av_q2d(stream->time_base) *
                      AV_TIME_BASE);
    jobject streamObj = env->NewObject(
        streamClass, constructor, (jlong)stream, i,
        (jint)stream->codecpar->codec_type, (jint)stream->codecpar->sample_rate,
        (jint)stream->codecpar->ch_layout.nb_channels,
        (jint)stream->codecpar->width, (jint)stream->codecpar->height,
        (jint)stream->time_base.num, (jint)stream->time_base.den, durationUs,
        metadata);
    env->DeleteLocalRef(metadata);
    env->SetObjectArrayElement(streams, i, streamObj);
    env->DeleteLocalRef(streamObj);
  }
  return streams;
}

/**
 * 容器总时长（AV_TIME_BASE 微秒）；容器没给（直播流等）返回 0。
 *
 * 总时长必须走格式层取：HLS 只把分片 EXTINF 求和写进 `AVFormatContext.duration`
 * （libavformat/hls.c），每条流上恒为 NOPTS —— 流上的时长（AvStream.duration）
 * 只对 MP4 那类把时长记在流上的容器有值。
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
  // **先 avformat_close_input、再收账**：read_close（如
  // hls_close）会把它自己认得的 pb 经 ff_format_io_close 交回
  // io_close2，那一步会销账并释放；先收账的话这些 pb 已经被我们还掉，ffmpeg
  // 随后拿着悬垂指针再调一次 io_close2 —— 又回到二次关闭那条路。走完
  // close_input，账上剩的才是真没人认领的：顶层 pb（avformat_close_input 对
  // CUSTOM_IO 先置 NULL，跳过 ff_format_io_close，demux.c:388-396）与 demuxer
  // 漏关的嵌套 pb。连同 io_open 里的 GlobalRef、av_malloc 缓冲、Java
  // 侧连接一起在这里收干净。
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
  // 非 EOF 的错误在这里出声：Kotlin 侧（AvFormat.getPacket）会把一切负返回值
  // 折叠成 EOF 收场，不打日志的话 demux 层的故障看起来就是"播完了"。IO 层
  // 的错误另有 read 回调里那一条（那层才是 AvIO 契约的边界）。
  if (ret < 0 && ret != AVERROR_EOF)
    av_log(nullptr, AV_LOG_ERROR,
           "av_read_frame error ret=%d（调用方按 EOF 折叠收场）\n", ret);
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
// 解码产出必须按 FFmpeg 的收/发分离协议收集：
//   - avcodec_send_packet() 返回 AVERROR(EAGAIN) 表示"输入队列满"，
//     此时必须先收帧；
//   - avcodec_receive_frame() 必须循环调用，直到返回 AVERROR(EAGAIN)
//     （需要更多输入）或 AVERROR_EOF（已 drain 完）。
// 一个 packet 在 B 帧/参考帧较多的编码下可能产出 0 帧（帧被解码器内部缓存）
// 或多帧，所以这里返回的是 AvFrame 数组而不是单帧。
//
// 注意：这里没有在 AVCodecContext 上挂帧缓存（`opaque` 是上游留给用户的
// 字段，本工程没用到它）；每次 receive_frame 前都用一个临时 AVFrame，拿到帧
// 就把所有权转交给 Java 侧（由 AvFrame.closeNative -> av_frame_free 释放）。
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
  // 把解码器里已经解出的帧全部收出来（顺带给 send_packet 腾出输入队列）
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
      // AVERROR(EAGAIN)：需要继续喂包；AVERROR_EOF：drain 完成
      break;
    }
  };

  // drain 阶段：packet == 0 表示冲刷解码器内部缓存的尾帧
  int ret = avcodec_send_packet(ctx, packet ? (AVPacket*)packet : nullptr);
  // EAGAIN = 输入队列满：**这个包没被收进去**，必须先收帧腾出位置再重发。
  // 包一丢，码流就少了这一帧的数据，之后整个 GOP 都拿不到正确的参考帧 ——
  // 画面成块马赛克，要到下一个关键帧才恢复。（2026-09-22：这正是「持续成块、
  // 跳转才恢复」那条症状的形状，所以这一路绝不能静默。）
  //
  // FFmpeg 的契约保证「输出的帧全部读完之后再送，不会再返回 EAGAIN」，所以正常
  // 情况下第二发就成；给足次数是为了真的卡住时还能吵出来，而不是悄悄把包扔掉。
  const int kMaxSendRetry = 16;
  for (int i = 0; ret == AVERROR(EAGAIN) && packet != 0 && i < kMaxSendRetry;
       ++i) {
    receiveAll();
    ret = avcodec_send_packet(ctx, (AVPacket*)packet);
  }
  if (ret == AVERROR(EAGAIN)) {
    // 收帧重发都收不进去：**如实表错**。这一包会被调用方 close 掉，
    // 也就是参考链从此刻断——没有日志的话，外面只会看到"画面花了"。
    av_log(ctx, AV_LOG_ERROR,
           "avcodec_send_packet 连续 %d 次 EAGAIN，包被丢弃：stream=%d size=%d "
           "（此后到下一个关键帧的画面都会是错的）\n",
           kMaxSendRetry, pstream->index,
           packet ? ((AVPacket*)packet)->size : 0);
  } else if (ret < 0 && ret != AVERROR_EOF) {
    av_log(ctx, AV_LOG_ERROR,
           "avcodec_send_packet 失败 ret=%d（stream=%d，本批已解出 %d "
           "帧，照样交出）\n",
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
  // seek 用：丢弃解码器内部缓冲，之后需要重新喂关键帧。
  // 与 drain（send_packet(NULL) 取出残余帧）是两回事。
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
  // 转码目标格式固定为 AV_PIX_FMT_RGBA（见
  // postFrameVideo）：平台侧渲染（SurfaceContext.jvm.kt 的
  // Skia、SurfaceContext.android.kt 的
  // Bitmap）一直就是按它消费帧的，没有别的选项，所以不把它做成构造参数。
  uint8_t* videoBuffer = nullptr;
  int64_t videoBufferSize = 0;
  // opaque：以下都是内部状态
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
        // 与上面给 ctx->_videoData 定尺寸、填数据时用的是同一个格式常量，
        // 否则 sws_scale 写进来的布局与当初分配的缓冲区对不上。
        AV_PIX_FMT_RGBA, SWS_POINT, nullptr, nullptr, nullptr);
    // ⚠️ 这个缓存键必须回写：上面那个 if
    // 拿它判断「源像素格式变没变」，不回写就永远停在初值
    // AV_PIX_FMT_NONE，于是条件**每一帧都成立**，sws 上下文被逐帧释放重建 ——
    // 每帧一次 sws_getContext 的分配与初始化，视频越往下放越白烧。swscale 里的
    // "No accelerated colorspace conversion" 警告正是随每次 sws_getContext
    // 打印的，所以日志会被它刷满。音频路径一直是这么写的，见 postFrameAudio
    // 里的 _srcAudioFormat。
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
// 把 native 缓冲**拷进** Kotlin 的 byte[]：长度对得上就覆写传入那块（Kotlin
// 侧按流类型 留着复用），对不上才新建 —— 视频 3.67 MB/帧、约 60
// 帧/s，每帧新建会把 Java 堆顶在 -Xmx 上。走 byte[] 而不是
// DirectByteBuffer，是不让 commonMain 出现 java.nio.ByteBuffer （根 AGENTS.md
// §4：commonMain 不许有平台符号）。
//
// 返回 nullptr = 本轮没有缓冲，调用方据此早退。
// ⚠️ 调用方必须同步消费这块内存、不能留存引用：
// 下一帧 sws_scale / swr_convert 会就地覆写。
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
  // 2. 检查传入的 buf 是否可以完美复用
  if (buf && buflen == (jsize)size) {
    ret = buf;
  } else {
    // 长度不匹配或传入了 NULL，创建新数组
    ret = env->NewByteArray((jsize)size);
    if (!ret) {
      // OOM 保护：创建失败直接返回 NULL
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
  // 纯 memcpy：桌面端把 native 那块 RGBA 直接写进
  // 复用的位图，避免每帧新建 skiko 对象。
  // 目标由调用方保证够大（同宽高 allocPixels 出来的）。
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
  // 两个输出缓冲（av_fast_malloc）必须还，否则每个 AvSurfaceContext
  // 实例漏一份（视频那支最大）；音频有**两块**在 audioBuffer 与 _audioBuffer1
  // 之间来回换（见 postFrameAudio 末尾），都得还。
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
  // 先把目标几何对齐成 RGBA_8888 + 与 native 输出一致的宽高：Surface
  // 默认不一定就是这套 （尺寸 / 像素格式可能不符），先设好再取 buffer。
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
  // 逐行拷贝：ANativeWindow 的 stride（像素）常大于 width，扁平 memcpy
  // 会把每行错位累加 成斜向花屏——这是「不报错、只失效」的典型，按 stride
  // 走才对。
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
