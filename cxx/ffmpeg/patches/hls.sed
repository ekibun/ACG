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
