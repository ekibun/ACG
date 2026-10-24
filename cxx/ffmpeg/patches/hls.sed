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

# 4) 把包时间戳平移到「声明轴」（EXTINF 累加）。
#
# 为什么要这一层：分片里的 PTS 由切片前的编码器写死，广告/拼接段会重新起算；而 hls.c 的
# 选片、seek 阈值、duration 全按 EXTINF 的声明时间算。两条轴错开时，落点会偏整段（同一份
# 流里既会早一个 GOP、也会晚 0.674s），进度条也会在广告处跳回 0。这里按分片实测一次
# `drift = 该片声明起点 − 该片首包时间`，把整片（含音频）平移到声明轴，A/V 相对关系不变。
# 见 docs/development/playback-invariants.md 1.7。
#
# 判据：读包位置 cur_seq_no 会先于包推进 —— 上一片读剩的几帧会带着新分片号出现。用
# 「按旧 drift 映射后是否仍落在旧分片的声明区间内」区分：在区间内就还是上一片的包。
# 区间两侧的误差只有 EXTINF 的毫秒级，而错判的代价也只在这个量级，所以不必再设容差。

# 4.1) struct playlist 加 drift 状态（seek_timestamp 全文件唯一）
/^    int64_t seek_timestamp;$/a\
    /* 本地补丁：媒体 → 声明 的平移，按分片实测。见 hls_read_packet 里 #4 那段。 */\
    int64_t ff_drift;\
    int64_t ff_decl_start;\
    int64_t ff_decl_dur;\
    int64_t ff_drift_seq;

# 4.2) new_playlist 里初始化（只在 new_playlist 范围内匹配）
/^static struct playlist \*new_playlist/,/^}$/{
    /^    pls->seek_timestamp = AV_NOPTS_VALUE;$/a\
    pls->ff_drift_seq = -1;
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
                                for (ff_k = 0; ff_k < ff_idx; ff_k++)\
                                    ff_start += pls->segments[ff_k]->duration;\
                                if (pls->ff_drift_seq < 0 ||\
                                    ff_out < pls->ff_decl_start ||\
                                    ff_out >= pls->ff_decl_start + pls->ff_decl_dur) {\
                                    pls->ff_drift = ff_start - ff_us;\
                                    pls->ff_decl_start = ff_start;\
                                    pls->ff_decl_dur = pls->segments[ff_idx]->duration;\
                                    pls->ff_drift_seq = pls->cur_seq_no;\
                                }\
                            }\
                            if (pls->ff_drift_seq >= 0) {\
                                int64_t ff_shift = av_rescale_q(pls->ff_drift, AV_TIME_BASE_Q, ff_tb);\
                                if (pls->pkt->dts != AV_NOPTS_VALUE)\
                                    pls->pkt->dts += ff_shift;\
                                if (pls->pkt->pts != AV_NOPTS_VALUE)\
                                    pls->pkt->pts += ff_shift;\
                            }\
                        }\
                    }
