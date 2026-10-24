# 把 open_url 里的协议检查包进 #if CONFIG_NETWORK，并同步挪走只在该段用到的
# proto_name 声明（否则关掉 network 的构建会报 unused variable）。
#
# 锚点全部限定在 open_url 的签名行到下一个顶格 } 之间：文件里 `int is_http = 0;`
# 出现两次、`HLSContext *c = s->priv_data;` 出现九次，单行匹配会多插。
#
# 用法：sed -i -f hls.sed libavformat/hls.patched.c
# sed 匹配不到不会报错，调用方必须校验 #if 恰好出现一次。

# 1) 删掉 open_url 里的声明（全文件唯一）
/^    const char \*proto_name = NULL;$/d

# 2) open_url 范围内：在 int is_http = 0; 之后插入 #if 与声明
/^static int open_url(AVFormatContext \*s, AVIOContext \*\*pb, const char \*url,$/,/^}$/{
    /^    int is_http = 0;$/a\
#if CONFIG_NETWORK\
    const char *proto_name = NULL;
}

# 3) open_url 范围内：在 file 判定分支的 return 之后插入 #endif
#    （`return AVERROR_INVALIDDATA;` 全文件四处在，必须先落到 file 判定那一行）
/^static int open_url(AVFormatContext \*s, AVIOContext \*\*pb, const char \*url,$/,/^}$/{
    /^    else if (strcmp(proto_name, "file") || !strncmp(url, "file,", 5))$/{
        n
        /^        return AVERROR_INVALIDDATA;$/a\
#endif
    }
}

# 4) 把包时间戳平移到「声明轴」（EXTINF 累加），并在换片处探一次插播广告。
#
# 分片的 PTS 由切片前的编码器写死，广告段会重新起算；而 hls.c 的选片、seek 阈值、duration
# 都按 EXTINF 的声明时间算。这里按分片实测 `drift = 该片声明起点 − 该片首包时间`，把整片
# （含音频）平移过去，A/V 相对关系不变。判据见 docs/development/playback-invariants.md 1.7，
# 广告探测见 docs/design/hls-ad-skip.md。
#
# 换片的判据：读包位置 cur_seq_no 会先于包推进，上一片读剩的几帧会带着新分片号出现。用
# 「按旧 drift 映射后是否仍落在旧分片的声明区间内」区分，区间误差只有 EXTINF 的毫秒级。

# 4.1) struct playlist 加 drift 与广告探测的状态（seek_timestamp 全文件唯一）
/^    int64_t seek_timestamp;$/a\
    /* 媒体 → 声明的平移量, 按分片实测; 见 hls_read_packet 里 #4 那段。 */\
    int64_t ff_drift;\
    int64_t ff_decl_start;\
    int64_t ff_decl_dur;\
    int64_t ff_drift_seq;\
    /* 各条流上一个包的媒体时间, 广告探测的基准。按流分开: 字幕/独立音轨的 PTS\
       能和视频差几秒, 混在一起会让判定反复误触发。 */\
    int64_t ff_last_media_us[8];\
    /* 这一片已经放弃过广告探测, 别再扫一遍。 */\
    int64_t ff_giveup_seq;

# 4.1b) HLSContext 加「关掉广告跳过」的开关（默认 0 = 跳过）。见第 5 条。
/^    int64_t cur_timestamp;$/a\
    /* 1 = 不跳插播广告（`acg_hls_set_skip_ad` 设）。 */\
    int ff_no_skip_ad;

# 4.1c) struct segment 记住自己前面有没有 #EXT-X-DISCONTINUITY
/^    struct segment \*init_section;$/a\
    /* 这一片前面紧挨着 #EXT-X-DISCONTINUITY。 */\
    int ff_discontinuous;

# 4.1d) parse_playlist 认这个 tag（它现在落到「Skip ('...')」那条）, 并落到下一个分片上。
#       `#EXT-X-DISCONTINUITY-SEQUENCE:` 也以它开头, 所以必须整行比较。
/^    int ret = 0, is_segment = 0, is_variant = 0;$/a\
    int cur_discontinuous = 0;

/^        } else if (av_strstart(line, "#", NULL)) {$/i\
        } else if (!strcmp(line, "#EXT-X-DISCONTINUITY")) {\
            cur_discontinuous = 1;

/^                seg->duration = duration;$/a\
                seg->ff_discontinuous = cur_discontinuous;\
                cur_discontinuous = 0;

# 4.2) new_playlist 里初始化（只在 new_playlist 范围内匹配）
/^static struct playlist \*new_playlist/,/^}$/{
    /^    pls->seek_timestamp = AV_NOPTS_VALUE;$/a\
    pls->ff_drift_seq = -1;\
    for (int ff_i = 0; ff_i < 8; ff_i++)\
        pls->ff_last_media_us[ff_i] = AV_NOPTS_VALUE;\
    pls->ff_giveup_seq = -1;
}

# 4.3) hls_read_seek 里作废：seek 之后从新的分片头重新实测
/^        pls->last_seq_no = pls->cur_seq_no;$/a\
        pls->ff_drift_seq = -1;

# 4.4) hls_read_packet：取到包之后、first_timestamp 记账之前平移
#      （记账要用平移后的值，否则声明轴还带着 1.4s 的初始偏移）
/^                    if (c->first_timestamp == AV_NOPTS_VALUE &&$/i\
                    if (pls->type != PLS_TYPE_EVENT) {\
                        AVRational ff_tb = get_timebase(pls);\
                        int64_t ff_ts = pls->pkt->dts == AV_NOPTS_VALUE ?\
                                            pls->pkt->pts : pls->pkt->dts;\
                        if (ff_ts != AV_NOPTS_VALUE) {\
                            int64_t ff_us = av_rescale_q(ff_ts, ff_tb, AV_TIME_BASE_Q);\
                            int64_t ff_idx = pls->cur_seq_no - pls->start_seq_no;\
                            if (pls->ff_drift_seq != pls->cur_seq_no &&\
                                ff_idx >= 0 && ff_idx < pls->n_segments) {\
                                int64_t ff_out = ff_us + pls->ff_drift, ff_start = 0, ff_k;\
                                if (pls->ff_drift_seq < 0 ||\
                                    ff_out < pls->ff_decl_start ||\
                                    ff_out >= pls->ff_decl_start + pls->ff_decl_dur) {\
                                    /* 疑似插播广告: 标记片 + 媒体回跳 > 5s。往后找到接得上的\
                                       标记片才跳，找不到退回原处。 */\
                                    if (ff_hls_try_skip_ad(c, pls, ff_idx, ff_start, ff_us,\
                                                           pls->pkt->stream_index) == FF_AD_RESTART)\
                                        continue;\
                                    /* 按当前片 + 当前包重算: 跳过时 cur_seq_no 与 pkt 已经指向\
                                       目标片, 没跳时几个值不变、结果相同。 */\
                                    ff_idx = pls->cur_seq_no - pls->start_seq_no;\
                                    if (ff_idx < 0 || ff_idx >= pls->n_segments)\
                                        continue;\
                                    ff_start = 0;\
                                    for (ff_k = 0; ff_k < ff_idx; ff_k++)\
                                        ff_start += pls->segments[ff_k]->duration;\
                                    ff_us = ff_hls_pkt_media_us(pls);\
                                    if (ff_us == AV_NOPTS_VALUE)\
                                        continue;\
                                    pls->ff_drift = ff_start - ff_us;\
                                    pls->ff_decl_start = ff_start;\
                                    pls->ff_decl_dur = pls->segments[ff_idx]->duration;\
                                    pls->ff_drift_seq = pls->cur_seq_no;\
                                }\
                            }\
                            if (pls->ff_drift_seq >= 0) {\
                                int64_t ff_shift = av_rescale_q(pls->ff_drift, AV_TIME_BASE_Q, ff_tb);\
                                int ff_si = pls->pkt->stream_index;\
                                if (pls->pkt->dts != AV_NOPTS_VALUE)\
                                    pls->pkt->dts += ff_shift;\
                                if (pls->pkt->pts != AV_NOPTS_VALUE)\
                                    pls->pkt->pts += ff_shift;\
                                if (ff_si >= 0 && ff_si < 8)\
                                    pls->ff_last_media_us[ff_si] = ff_us;\
                            }\
                        }\
                    }

# 4.5) 广告探测的声明：实现在文件末尾（第 5 条），调用点在 4.4 里。
#      结局的宏必须在这里（先于 hls_read_packet），放末尾会晚于使用点。
/^static int hls_read_packet(AVFormatContext \*s, AVPacket \*pkt)$/i\
/* 广告探测的两种结局 */\
#define FF_AD_PROCEED  0   /* 用当前 pls->pkt 继续: 跳过的目标片首包也已经在里面 */\
#define FF_AD_RESTART  1   /* 位置已挪, 包还没读, 调用方重读 */\
static int64_t ff_hls_pkt_media_us(struct playlist *pls);\
static int ff_hls_try_skip_ad(HLSContext *c, struct playlist *pls, int64_t seg_idx,\
                             int64_t decl_start, int64_t media_us, int stream_index);\
\

# 5) 广告探测与跳段，以及「关掉跳过」的开关。
#    实现追加在文件末尾 —— C 允许函数定义在最后，比在 hls.c 里找函数锚点稳。
$a\
\
/* 本地补丁：插播广告跳段。判据与取舍见 docs/design/hls-ad-skip.md。\
 * 广告是独立转码的，媒体时间从 0 附近重新起算，所以「标记片 + 媒体时间回跳」就是它的指纹。 */\
#define FF_AD_JUMP_US   (5 * AV_TIME_BASE)    /* 媒体回跳超过这个值才怀疑 */\
#define FF_AD_STEP_US   (60 * AV_TIME_BASE)   /* 相邻两个标记片之间的声明距离上限 */\
#define FF_AD_RESUME_US 500000                /* 「接上了」的容差: 必须远小于一个分片 */\
\
/* 这一包自己的媒体时间（平移前）。 */\
static int64_t ff_hls_pkt_media_us(struct playlist *pls) {\
    int64_t ts = pls->pkt->dts == AV_NOPTS_VALUE ? pls->pkt->pts : pls->pkt->dts;\
    if (ts == AV_NOPTS_VALUE || pls->pkt->stream_index < 0 ||\
        pls->pkt->stream_index >= (int)pls->ctx->nb_streams)\
        return AV_NOPTS_VALUE;\
    return av_rescale_q(ts, get_timebase(pls), AV_TIME_BASE_Q);\
}\
\
/* 把播放位置挪到 seq：关掉当前片的 IO 与内层解复用器的缓冲，让下一次读从新片头开始。 */\
static void ff_hls_jump_to(HLSContext *c, struct playlist *pls, int64_t seq) {\
    AVIOContext *pb = &pls->pb.pub;\
    int i;\
    ff_format_io_close(pls->parent, &pls->input);\
    pls->input_read_done = 0;\
    ff_format_io_close(pls->parent, &pls->input_next);\
    pls->input_next_requested = 0;\
    av_packet_unref(pls->pkt);\
    pb->eof_reached = 0;\
    pb->buf_end = pb->buf_ptr = pb->buffer;\
    pb->pos = 0;\
    if (pls->ctx) {\
        ff_read_frame_flush(pls->ctx);\
        for (i = 0; i < (int)pls->ctx->nb_streams; i++)\
            ffstream(pls->ctx->streams[i])->pts_wrap_reference = AV_NOPTS_VALUE;\
    }\
    pls->cur_init_section = NULL;\
    pls->cur_seq_no = seq;\
    pls->last_seq_no = seq;\
    pls->ff_drift_seq = -1;\
    c->cur_seq_no = seq;\
}\
\
/* 返回 FF_AD_PROCEED 表示 pls->pkt 就是要用的包（跳过时它是目标片首包）;\
 * FF_AD_RESTART 表示位置已挪、包还没读，调用方重读。判据见 docs/design/hls-ad-skip.md。 */\
int ff_hls_try_skip_ad(HLSContext *c, struct playlist *pls, int64_t seg_idx,\
                       int64_t decl_start, int64_t media_us, int stream_index) {\
    int64_t base = (stream_index >= 0 && stream_index < 8) ?\
                       pls->ff_last_media_us[stream_index] : AV_NOPTS_VALUE;\
    int64_t decl, k, resume = -1, prev = media_us, prev_decl = decl_start;\
\
    if (c->ff_no_skip_ad || base == AV_NOPTS_VALUE) return FF_AD_PROCEED;\
    if (seg_idx < 0 || seg_idx >= pls->n_segments) return FF_AD_PROCEED;\
    if (!pls->segments[seg_idx]->ff_discontinuous) return FF_AD_PROCEED;\
    if (base - media_us <= FF_AD_JUMP_US) return FF_AD_PROCEED;\
    if (pls->ff_giveup_seq == pls->cur_seq_no) { /* 扫过一次没找到，别再扫 */\
        pls->ff_giveup_seq = -1;\
        return FF_AD_PROCEED;\
    }\
\
    decl = decl_start;\
    for (k = seg_idx + 1; k < pls->n_segments; k++) {\
        int64_t m;\
        decl += pls->segments[k - 1]->duration;\
        /* 只看这一跳: 相邻两个标记片隔得太远, 中间就不是同一段插播 */\
        if (decl - prev_decl > FF_AD_STEP_US) break;\
        if (!pls->segments[k]->ff_discontinuous) continue;\
        prev_decl = decl;\
        ff_hls_jump_to(c, pls, pls->start_seq_no + k);\
        if (av_read_frame(pls->ctx, pls->pkt) < 0) break;\
        m = ff_hls_pkt_media_us(pls);\
        if (m == AV_NOPTS_VALUE) break;\
        if (FFABS(m - base) <= FF_AD_RESUME_US) {\
            resume = k;\
            break;\
        }\
        if (m > base + FF_AD_RESUME_US) break; /* 跑过基准: 不是接续 */\
        /* 同一段广告里媒体时间单调往前; 回退说明这里已经换了一段内容 */\
        if (m <= prev) break;\
        prev = m;\
    }\
\
    /* 接上了: 这个包已经在 pls->pkt 里, 交给调用方直接用。不能 unref 再重读——\
     * 读位置已经越过它, 重读会从第二个包开始, 首帧 IDR 就丢了。 */\
    if (resume >= 0) return FF_AD_PROCEED;\
\
    /* 没找到接续点: 退回广告起点正常播 */\
    ff_hls_jump_to(c, pls, pls->start_seq_no + seg_idx);\
    pls->ff_giveup_seq = pls->cur_seq_no;\
    return FF_AD_RESTART;\
}\
\
/* 关闭/开启插播广告的自动跳过（默认开）。返回 0 成功，非 HLS 返回 AVERROR(EINVAL)。 */\
int acg_hls_set_skip_ad(AVFormatContext *s, int on);\
int acg_hls_set_skip_ad(AVFormatContext *s, int on) {\
    if (!s || !s->priv_data || !s->iformat || strcmp(s->iformat->name, "hls"))\
        return AVERROR(EINVAL);\
    ((HLSContext *)s->priv_data)->ff_no_skip_ad = !on;\
    return 0;\
}
