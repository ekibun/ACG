---
name: build-and-test
description: >-
  构建与验收的入口：Gradle 与 JDK 的关系（启动 gradlew 的 JAVA_HOME、daemon 的 toolchain、native 的 jni.h
  是三件互不相同的事）、跑桌面端与 Android 包的命令、三条编译闸门、jvmTest 怎么定点跑
  （`:shared` 与 `:platform` 两个模块）、
  ktlint / clang-format 怎么跑 —— 这三样**验收时都不跑**，只在提交前或用户明确要求时跑。
  也包括 native 重编与 dll 落位怎么全自动发生（改了 `cxx/` 不用手动同步）。
  Use when 要跑构建 / 打包 / 测试、判断"这次改动算不算做完"，或改了 cxx/ 下的 native 需要重编时。
agent_created: true
---

# 构建与验收

| 要做什么 | 读哪份 |
|---|---|
| 跑起来 / 打包 / 弄清"这些 JDK 到底谁管什么" | [`references/build-and-test.md`](./references/build-and-test.md) |
| 改了 native：重编与 dll 落位（全自动，但要认得坑） | [`cxx/AGENTS.md`](../../../cxx/AGENTS.md) 的「原生构建」 |

**按需要的那份读，不要默认全读。**

两条最容易白跑一轮的（细节在上面两份里）：

- 命令行下 **`gradlew` 不会自己去找 JDK**，`JAVA_HOME` 得自己给；在 IDE 里跑不受影响。
- **改完 native 没重编 = 改动静默失效**（连日志都没有）；重编与落位已全自动（见 `cxx/AGENTS.md`），
  剩下的坑是 Hot Reload 换不了 native 库。同一类陷阱见 skill `project-traps`。

一条最容易白干一轮的：**验收只过定点 `jvmTest`**，三条编译闸门、全量 `:shared:jvmTest` /
`:platform:jvmTest` 与 lint
（`ktlintCheck` / `clang-format`）默认都不跑 —— 见根 [`AGENTS.md`](../../AGENTS.md) §5。
