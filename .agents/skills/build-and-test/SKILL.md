---
name: build-and-test
description: >-
  Use when 要跑构建 / 打包 / 测试、判断"这次改动算不算做完"，或改了 cxx/ 下的 native 需要重编时。
---

# 构建与验收

## 核心规则

> **DO：** 构建依赖 `JAVA_HOME` 启动 `gradlew`；`gradlew` 据当前环境变量（如 `MSYS2_BIN`）自动完成编译与测试。若所需环境变量缺失导致无法构建，**直接停下报告，不要自行寻找路径或绕过**。

> **DO：** 验收纪律（定点 `jvmTest`、提交前才跑四条编译闸门 / 全量测试 / lint）以根 [`AGENTS.md`](../../../AGENTS.md) §2.5 为正本；本技能只给具体命令。

## 按场景查

| 要做什么 | 读哪份 |
|---|---|
| 构建桌面 / Android 包、产物与 ABI 核对 | [`references/build.md`](./references/build.md) |
| 调试播放（dev 媒体服务器、在线测试流、本地生成） | [`docs/development/debug-playback.md`](../../../docs/development/debug-playback.md) |
| 四条编译闸门的任务名与覆盖面 | [`references/gates.md`](./references/gates.md) |
| ktlint / clang-format 与 pre-commit hook | [`references/lint.md`](./references/lint.md) |
| 定点 / 全量 `jvmTest`、`--rerun`、结果读 XML | [`references/test.md`](./references/test.md) |

**按需要的那份读，不要默认全读。**
