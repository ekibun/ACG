# 不报错、只失效

> 条目原样移自根 [`AGENTS.md`](../../../../AGENTS.md) §2（原题「会静默失败的几件事」），
> **未逐条对照代码重新核定**。§2 现在只留索引，正本是本文件。

本项目最危险的一类问题**不报错、只失效**，排查时最容易误判成"代码没写对"。动到下面任一条之前先读它：

- **改过 native 却用着旧 dll** → 不报错，只是参数错位。改 Kotlin 侧签名后，第一件事是确认 dll
  已刷新到运行位置（落位见 skill `build-and-test` 的
  [`dll-sync.md`](../../build-and-test/references/dll-sync.md)）。
- **漏拷 dll** → 症状是"改动没生效、连日志都没有"。dll 要落到三个位置，见同一份 `dll-sync.md`。
- **改 `soko.ekibun.acg.engine` 下的类名** → `JsEngine` 按名字反射实例化它们、JS 侧按名字调用，
  改名/移动**不会报错**，只会让脚本静默失效。
- **在 `init.js` 里用 `import()` / `require()`** → 本桥的模块加载路径会把进程 `abort()`。
  能力必须内联（脚本位置见根 [`AGENTS.md`](../../../../AGENTS.md) §3）。
- **在 JS 线程之外碰 `JSRuntime`** → 偶发崩、**重跑就变绿**，看着像环境问题，其实是真实竞态
  （引用计数是裸 `int`、GC 链表与 Shape 哈希链都无锁）。桥接层已经把 `jsCall` / `releaseValue`
  收敛到 dispatcher 上了 —— **新增任何直接调 native 的路径都要先过 `runOnDispatcher` /
  `onJsThreadQuietly`**，别在调用方线程上调
  （规则 7 见 skill [`quickjs-ownership`](../../quickjs-ownership/references/quickjs-reference-ownership.md)）。
- **动了 submodule** → 改动不报错，但会污染上游源码树、下次同步即丢。`cxx/ffmpeg/ffmpeg/`、
  `cxx/quickjs/quickjs/` 是上游源码树，**不要动**（需要参考实现就直接读本地文件）。
