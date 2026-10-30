#pragma once

struct AVStream;
struct AVCodec;
struct AVCodecContext;

// 按流的参数建一个待 open 的解码器上下文。
// decoder 为 null、或参数拷不过去时返回 null。
AVCodecContext* allocCodecContext(AVStream* stream, const AVCodec* decoder);
