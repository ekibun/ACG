# native：重编与 dll 同步

本文件是 **dll 落位的正本**；根 [`AGENTS.md`](../../../../AGENTS.md) 与
[`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 都只留指针，不要另抄一份清单。

## 重编

入口只有 `./gradlew :desktopApp:buildJni`（在 Android Studio 里点桌面端运行也会触发它）。
Gradle 这条路**开箱可用**：它自己进 MSYS2 的 login shell，并把 toolchain 那份带 `include/` 的 JDK
传给 `build.jni.sh`，所以**不用你准备任何 JDK**。唯一还要的本机私有值是环境变量 **`MSYS2_BIN`**
（`cxx/exec.cmd` 靠它进 MSYS2 的 bash；没设的话 exec.cmd 直接退出 1）。

命令与 JDK 的关系见 [`build-and-test.md`](./build-and-test.md)；MSYS2 login shell 为什么工具链自足、
jni.h 怎么从 toolchain JDK 拿到、Git Bash 为什么不能用来编 native —— 见
[`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的「原生构建」。

## dll 要落到三处

源都是 `cxx/build/bin/<name>.dll`。标准是**全靠 Gradle 自动拷、不要额外手动操作**：

```text
1. shared/build/processedResources/jvm/test/      ← jvmTestProcessResources 自动
2. desktopApp/build/resources/main/               ← :desktopApp:processResources 自动
3. desktopApp/build/run/main/classpath/classes/   ← 目前无任务写，改完 native 后需手动拷
```

**第 3 处是目前唯一的缺口**，漏掉的症状是"改动没生效、连日志都没有" —— 最容易被误判成代码问题
（同类陷阱见 skill `project-traps` 的
[`silent-failures.md`](../../project-traps/references/silent-failures.md)）。
