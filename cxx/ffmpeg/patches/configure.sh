#!/usr/bin/env bash
# ffmpeg 的 configure 步骤：跑 configure，然后把 hls.c 的副本丢进构建目录、用 hls.sed 改它，
# 并让构建目录的 Makefile 把 libavformat/hls.o 指向副本。
# 由 ffmpeg.cmake 的 CONFIGURE_COMMAND 调用（ExternalProject 已 cd 到构建目录），参数就是
# 完整的 configure 命令行（含 --prefix）：
#   configure.sh <configure> <参数...>
#
# 为什么不改源码树：submodule 必须保持上游原样（见 cxx/AGENTS.md 的 ffmpeg 一节）。
# 副本按构建目录隔离（桌面 / Android 各 ABI 天然各一份）。每次 configure 都整体重跑。
set -euo pipefail

# 本脚本在 cxx/ffmpeg/patches/，源码树是同级的 cxx/ffmpeg/ffmpeg/。
patch_dir=$(cd "$(dirname "$0")" && pwd)
src_path=$(cd "$patch_dir/../ffmpeg" && pwd)

"$@"

cp "$src_path/libavformat/hls.c" libavformat/hls.patched.c
sed -i -f "$patch_dir/hls.sed" libavformat/hls.patched.c

# sed 匹配不到不会报错，改动会静默消失 —— 自己验一遍：上游一改锚点就在这里失败。
guard_count=$(grep -c '^#if CONFIG_NETWORK$' libavformat/hls.patched.c || true)
[ "$guard_count" = 1 ] ||
    { echo "error: hls.sed did not apply (upstream hls.c changed?), guards=$guard_count" >&2; exit 1; }

# 第 4 条（包时间戳平移到声明轴）同样要验收：字段与改写主体各出现一次。
drift_field=$(grep -c 'int64_t ff_drift_seq;' libavformat/hls.patched.c || true)
drift_body=$(grep -c 'pls->ff_drift = ff_start - ff_us;' libavformat/hls.patched.c || true)
[ "$drift_field" = 1 ] && [ "$drift_body" = 1 ] ||
    { echo "error: hls.sed drift patch incomplete (field=$drift_field body=$drift_body)" >&2; exit 1; }

# 广告探测（hls.sed 第 4.1c/4.1d/4.4/5 条）：tag 解析、判定函数、调用点各就位才算打上。
ad_hook=$(grep -c 'ff_hls_try_skip_ad' libavformat/hls.patched.c || true)
ad_flag=$(grep -c 'ff_discontinuous' libavformat/hls.patched.c || true)
ad_switch=$(grep -c 'ff_no_skip_ad' libavformat/hls.patched.c || true)
ad_keep=$(grep -c 'if (resume >= 0) return FF_AD_PROCEED;' libavformat/hls.patched.c || true)
[ "$ad_hook" = 3 ] && [ "$ad_flag" = 4 ] && [ "$ad_switch" = 3 ] && [ "$ad_keep" = 1 ] ||
    { echo "error: hls.sed ad-skip patch incomplete" \
           "(try_skip_ad=$ad_hook discontinuous=$ad_flag switch=$ad_switch keep=$ad_keep)" >&2; exit 1; }

# 显式规则优先于 ffbuild/common.mak 的 `%.o: %.c`。副本不在源码树里，
# `#include "avformat.h"` 这类源码相对的包含要显式补 -I。
# 编译决策交给 make：副本每次 configure 都重写，靠 mtime 就会重编 hls.o。
cat > Makefile <<EOF
include $src_path/Makefile

libavformat/hls.o: libavformat/hls.patched.c
	\$(COMPILE_C)
libavformat/hls.o: CCFLAGS += -I$src_path/libavformat -I$src_path
EOF

echo "hls.o -> libavformat/hls.patched.c"
