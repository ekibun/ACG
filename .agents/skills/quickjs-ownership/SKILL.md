---
name: quickjs-ownership
description: >-
  本项目 QuickJS 桥（soko.ekibun.quickjs / cxx/quickjs）的引用计数与所有权规则：JSRef 何时被消费、
  何时要 dup、何时要 free，jsToJava 为什么是唯一的分发点，JS_DefinePropertyValue 的隐式消费，
  tag = -1 为什么是正常对象而不是异常，以及泄漏怎么转成异常。
  Use when 要改 JS_FreeValue / JS_DupValue / JS_DefinePropertyValue、新增 JS 与 Kotlin 之间的类型映射，
  或排查「数组元素全是 null」「promise 没有 then」这类桥接层症状时。
agent_created: true
---

# QuickJS 引用计数与所有权

完整规则在 [`references/quickjs-reference-ownership.md`](./references/quickjs-reference-ownership.md)。
**动 `JS_FreeValue` / `JS_DupValue` 之前先读它**，按符号查，不要整篇读。

四条最容易踩死、漏了就一定改错的（细节见手册）：

- `JS_DefinePropertyValue` 内部**无条件**消费 `val` 的引用，调用方**不要**再减一次。
- `jsToJava` 是**唯一**的对象/标量分发点，递归点必须走它。历史上把对象分支移出去过一次，
  症状是「数组元素全是 null」「promise 没有 then」。
- `tag = -1` 是**正常的对象**（`JS_TAG_OBJECT = -1`），不是异常。
- 所有 native 访问都必须落在 **JS 线程**上（过 `runOnJsThread` / `onJsThreadQuietly`）。
  QuickJS 单线程，两个线程同时进同一个 `JSRuntime` 的症状是**偶发崩、重跑就变绿** ——
  看着像环境问题，其实是真实竞态。手册里的规则 7。

另外：`import()` / `require()` 一律不许用 —— 本桥的模块加载路径会把进程 `abort()`，
能力全部内联进 `init.js`（位置见根 [`AGENTS.md`](../../../AGENTS.md) §3）。
