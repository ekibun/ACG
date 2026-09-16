# 构建、运行与验收闸门

本文件是**构建与验收的正本**；根 [`AGENTS.md`](../../../../AGENTS.md) 只留"改完要过闸门"这一句与指针。

## JDK：三件互不相同的事，别混

| 用途 | 谁说了算 | 你要做什么 |
|---|---|---|
| **启动 `gradlew`** | 需要一个 `JAVA_HOME`，或 PATH 上的 `java` | 命令行下**必须自己给** —— `gradlew` 不会自己去找 JDK |
| **跑构建 / 测试**（Gradle daemon） | `gradle/gradle-daemon-jvm.properties` | **什么都不用做**，Gradle 自己备好那份，本地没有就下载 |
| **编 native 要的 `jni.h`** | 根 `CMakeLists.txt` 在 configure 期读 `$ENV{JAVA_HOME}` | 走 `:desktopApp:buildJni` 时 Gradle 自动传 toolchain JDK（带 `include/`），你不用给 |

## 跑

主路径是**在 Android Studio 里直接运行**（不受上表第一行影响）；命令行等价物：

```bash
JAVA_HOME=<任意一份 JDK> ./gradlew :desktopApp:run                          # 桌面（触发 :desktopApp:buildJni）
JAVA_HOME=<任意一份 JDK> ./gradlew :desktopApp:run -x :desktopApp:buildJni   # 跳过 native 重编、只跑 Java 侧
JAVA_HOME=<任意一份 JDK> ./gradlew :desktopApp:buildJni                      # 只编 native、不跑
JAVA_HOME=<任意一份 JDK> ./gradlew :androidApp:assembleDebug                 # Android debug 包
```

另外两件本机前提：**MSYS2**（只有编 native 用得到，见 [`dll-sync.md`](./dll-sync.md) 和
[`cxx/AGENTS.md`](../../../../cxx/AGENTS.md)）、**Android SDK**（`local.properties` 里的 `sdk.dir`，
本机私有、已忽略）。

**结果落盘再读**，别靠终端实时输出下判断：Windows 上 JVM 按控制台代码页编解码，与 UTF-8 的日志
对不上就是乱码。加 `--console=plain` 让输出变成可线性读的纯文本。

## 闸门：改完代码至少过这三条编译

**任务名别写错**：

```bash
./gradlew :shared:compileAndroidMain :shared:compileKotlinJvm :desktopApp:compileKotlin
#         注意不是 compileDebugKotlinAndroid
```

`:androidApp` 自身的编译暂未纳入这道闸门 —— 改了 `androidApp/` 下的代码不会被这三条拦到。

## 测试

```bash
./gradlew :shared:jvmTest --console=plain
#   结果读 XML，不看控制台：
#   shared/build/test-results/jvmTest/TEST-*.xml  ->  tests= / skipped= / failures= / errors=

# 单个类 / 单个方法（路径就是真实包名）—— 只改了某一处、想快点验证时用
./gradlew :shared:jvmTest --tests "soko.ekibun.acg.web.NativeWebViewHostTest"
./gradlew :shared:jvmTest --tests "soko.ekibun.quickjs.QuickJSTest.objectWithVariousTagsRoundTrips"
```

- `jvmTestProcessResources` 会把 `cxx/build/bin` 里的 dll 拷进测试资源 →
  **跑 jvmTest 前 native 必须是编好的**（见 [`dll-sync.md`](./dll-sync.md)）。
- 偶发失败的处理见 skill `project-traps` 的
  [`silent-failures.md`](../../project-traps/references/silent-failures.md)：
  不要靠重跑掩盖，也不要改产品代码去迁就。
- 测试的报错与日志**全用英文（ASCII）**，避免 Windows 控制台代码页把中文变成乱码。
