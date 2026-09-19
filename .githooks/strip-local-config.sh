#!/bin/sh
# `git add` 时挂在 `.githooks/pre-commit` 上的 **clean filter**：把里面「本机依赖配置」区的
# **内容行**剥掉，只留两行标记 + 一行占位 —— 于是每个人填在本机的绝对路径都不会进仓库。
#
# 挂法（`.gitattributes`，已入库）：
#     .githooks/pre-commit filter=localconfig
# 而 filter 的**定义**在 `.git/config`（本机私有、不入库），每个 clone 跑一次：
#     git config filter.localconfig.clean 'sh .githooks/strip-local-config.sh'
# 没配的后果：clean 不跑 → 你填的路径会被提交出去（pre-commit 会黄字警告一次）。
# （不设 smudge：仓库里存的就是「空区」版本，checkout 出来本来就该是空的。）
#
# 用法：
#   sh .githooks/strip-local-config.sh                    # filter 模式：stdin 进、stdout 出
#   sh .githooks/strip-local-config.sh --filled <文件>     # 区里填过东西 → exit 0；否则 exit 1
# 手工预览：sh .githooks/strip-local-config.sh < .githooks/pre-commit
#
# ⚠️ 只用 **shell 内建**（printf / read / case / [ ]），一条外部命令都不调 —— 与 pre-commit
#    同一条规矩：本机上 git 起子进程时给的 PATH 是裁过的，`sed` / `awk` / `grep` 都可能不在
#    上面，而它们「找不到」的表现是**输出为空** → filter 会把整个文件清空 → 静默毁掉一次
#    提交。这里没有这个失败模式。

BEGIN='# === LOCAL-CONFIG-BEGIN ==='
END='# === LOCAL-CONFIG-END ==='
PLACEHOLDER='# （本机依赖行填在这里；`git add` 时会被本脚本自动剥离）'

# —— 附带模式：判断区里有没有填过东西（pre-commit 用它确认 filter 已配）——
if [ "${1:-}" = "--filled" ]; then
  f=${2:-}
  [ -n "$f" ] && [ -r "$f" ] || exit 1
  in_region=0
  while IFS= read -r line || [ -n "$line" ]; do
    case $line in
      "$BEGIN") in_region=1; continue ;;
      "$END") in_region=0; continue ;;
    esac
    if [ "$in_region" -eq 1 ]; then
      case $line in
        '' | \#*) ;;
        *) exit 0 ;;
      esac
    fi
  done < "$f"
  exit 1
fi

# —— filter 模式 ——
in_region=0
while IFS= read -r line || [ -n "$line" ]; do
  if [ "$in_region" -eq 0 ]; then
    printf '%s\n' "$line"
    case $line in
      "$BEGIN")
        in_region=1
        printf '%s\n' "$PLACEHOLDER"
        ;;
    esac
  else
    case $line in
      "$END")
        in_region=0
        printf '%s\n' "$line"
        ;;
    esac
  fi
done
