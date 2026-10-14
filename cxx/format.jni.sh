#!/bin/bash
#
# format.jni.sh —— 用 clang-format 统一 cxx/ 三个自研顶层 C++ 文件的格式。
# 经 `cxx/exec.cmd`（MSYS2 MINGW64 login shell）被 Gradle 的 clangFormat 任务调用，
# 也可手动 `./format.jni.sh [check|fix]` 跑。
#
# 用法：
#   ./format.jni.sh check   默认；`--dry-run --Werror`，不齐则非零退出（提交闸门 / Gradle 校验）
#   ./format.jni.sh fix     就地 `-i` 重写（手动修复）
#
# clang-format 只从 PATH 找（MSYS2 MINGW64 login shell 会把 /mingw64/bin 推进 PATH，
# 本仓固定基线二进制 clang 22.1.8 自然命中——不写死绝对路径、也不读 ACG_CLANG_FORMAT）。
# 版本必须对齐根目录 `.clang-format` 的基线（mingw64 clang 22.1.8），否则会出“假失败”或真改动。

set -euo pipefail

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
MODE="${1:-check}"

# 1. 定位 clang-format：只走 PATH。
CF="$(command -v clang-format 2>/dev/null || true)"
if [ -z "$CF" ] || [ ! -x "$CF" ]; then
  echo "clang-format not found. Put it on PATH (repo baseline = mingw64 clang 22.1.8)." >&2
  exit 1
fi
echo "clang-format: $CF"
"$CF" --version

# 2. 三个自研顶层文件（与 pre-commit hook 范围一致：cxx/{webview,quickjs,ffmpeg} 顶层 .c/.cc/.cpp/.cxx/.h/.hpp）
FILES=(
  "$DIR/webview/webview.cpp"
  "$DIR/quickjs/quickjs.cpp"
  "$DIR/ffmpeg/ffmpeg.cpp"
)

rc=0
for f in "${FILES[@]}"; do
  if [ ! -f "$f" ]; then
    echo "skipped (missing): $f"
    continue
  fi
  if [ "$MODE" = "fix" ]; then
    "$CF" -i --style=file "$f"
    echo "formatted: $f"
  else
    if "$CF" --dry-run --Werror --style=file "$f"; then
      echo "ok: $f"
    else
      echo "not formatted (fix: ./format.jni.sh fix): $f" >&2
      rc=1
    fi
  fi
done

exit $rc
