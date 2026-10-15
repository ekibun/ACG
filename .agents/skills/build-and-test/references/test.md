# 测试

> **DO：** 验收默认只跑**定点 `jvmTest`**（只跑改动相关的类/方法）。全量只在用户确认提交时跑一次。

```bash
./gradlew :platform:jvmTest --tests "soko.ekibun.web.WebViewHostTest" --console=plain
# 平台层（jni / quickjs / ffmpeg / web 宿主）的用例在 :platform:jvmTest，同样要 dll：
./gradlew :platform:jvmTest --tests "soko.ekibun.quickjs.QuickJSTest.objectWithVariousTagsRoundTrips" --console=plain
```

```bash
./gradlew :shared:jvmTest :platform:jvmTest --console=plain --rerun
#   结果读 XML，不看控制台：
#   {shared,platform}/build/test-results/jvmTest/TEST-*.xml  ->  tests= / skipped= / failures= / errors=
```

> **DO：** 重复跑（偶发问题、改过 native 之后复查）必须加 `--rerun`。不加的话第 2 轮起任务是 `UP-TO-DATE`，XML 还是上一轮残留，会误判"跑了 N 轮"。写验收循环把"任务真的执行了"也做成判据（`grep 'Task :shared:jvmTest UP-TO-DATE'`）。

- `jvmTestProcessResources`（`:shared` 与 `:platform` 各一份）把 `cxx/build/bin` 里的 dll 拷进测试资源，且都已挂上 `buildJni` —— 改了 `cxx/` 只跑测试也会先重编（全链条见 [`cxx/AGENTS.md`](../../../../cxx/AGENTS.md) 的「原生构建」）。
- 偶发失败的处理见 [`debugging.md`](../../../../docs/development/debugging.md) 与 [`playback-invariants.md`](../../../../docs/development/playback-invariants.md)：不要靠重跑掩盖，也不要改产品代码去迁就。
- 测试的报错与日志**全用英文（ASCII）**，避免 Windows 控制台代码页把中文变成乱码。

> **DON'T：** 默认跑全量测试。jvmTest 里有 11 个 `@Test(timeout = …)` 兜底（上限 60 s ~ 180 s）加一批 `withTimeout` 护栏，全量一轮慢；失败时分不清是超时兜底到点还是真回归，容易把结论带偏。真回归的判据是定点测试——失败必然与自己有关。
