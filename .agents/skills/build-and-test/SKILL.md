---
name: build-and-test
description: >-
  Use when 要跑构建 / 打包 / 测试、判断"这次改动算不算做完"，或改了 cxx/ 下的 native 需要重编时。
---

# 构建与验收

> **DO：** `gradlew` 是唯一的调用单位，**前后都不许接东西**（不接 `$env:X=...;`、`cd`、管道、`2>&1`、`;` 串接）。要留证据让 Gradle 自己落盘，跑完再单独读文件。
> 原因：`gradlew` 在 `dsh-sandbox-allowlist` 里是 `opaque` 能力类，多一段未知程序就降级为 `delegate`，审批弹窗回来。

> **DO：** `gradlew` 一律带沙箱提权（`sandbox_permissions: danger-full-access`）。Gradle 要写工作区外的 `~/.gradle`，不提权必在 `.zip.lck` 上 `AccessDeniedException`；规则只批准提权，不会自己发起。

> **DO：** `JAVA_HOME` / `MSYS2_BIN` 由 `gradlew` 自己带入；环境缺失就**停手报告**，不要自己找路径或绕过。

> **DO：** 验收纪律以根 [`AGENTS.md`](../../../AGENTS.md) §2.5 为正本，各文件只给命令。Windows 上 `./gradlew` 即 `.\gradlew.bat`。

## 按场景查

| 要做什么 | 读哪份 |
|---|---|
| 编译门禁（六条）、native（JNI 层 / `cxx/`）、打桌面与 Android 包 | [`references/build.md`](./references/build.md) |
| ktlint / clang-format 与 pre-commit hook | [`references/lint.md`](./references/lint.md) |
| 定点 / 全量 `jvmTest`、`--rerun`、结果读 XML | [`references/test.md`](./references/test.md) |

**按需要的那份读，不要默认全读。**
