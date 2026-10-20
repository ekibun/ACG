# 测试

```bash
./gradlew :platform:jvmTest --tests "soko.ekibun.web.Webview2Test" --console=plain
./gradlew :shared:jvmTest --tests "soko.ekibun.acg.engine.BrowserLoginTest" --console=plain
# 平台层（jni / quickjs / ffmpeg / web）在 :platform:jvmTest，其余在 :shared:jvmTest；--tests 认全限定名
```

```bash
./gradlew :shared:jvmTest --rerun :platform:jvmTest --rerun --console=plain
# --rerun 只作用于紧跟它的那一个任务，所以每个任务各带一个；同一条命令重跑也必须带
```

> **DO：** 结果只认 XML（`{shared,platform}/build/test-results/jvmTest/TEST-*.xml`，每类一份）：
> 读 `tests=` / `skipped=` / `failures=` / `errors=`，连同 `mtime` 一起核。控制台里 `UP-TO-DATE` 与
> 「任务真跑了」长得一样；跳过也不在控制台逐条列 ⇒ 只有 XML 能给出这一轮的真实结论。

> **DO：** 偶发失败按 [`debugging.md`](../../../../docs/development/debugging.md) 与 [`playback-invariants.md`](../../../../docs/development/playback-invariants.md) 甄别，**不要靠重跑掩盖**，也不要改产品代码去迁就。
