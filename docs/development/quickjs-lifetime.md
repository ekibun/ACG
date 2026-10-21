# QuickJS 运行时：引用计数、转换与关闭

`platform/src/commonMain/kotlin/soko/ekibun/quickjs/` 的承重约束。能力桥怎么派发见
[design/quickjs-ability-bridge.md](../design/quickjs-ability-bridge.md)；本篇只讲「一个 JS 值从哪来、谁负责还、
什么时候必须还完」。

## 一、票（引用计数）

- 一个 JS 值在 Kotlin 侧的「票」由 native `JS_DupValue` 产生；`JSRef.refCount` 就是持有票数，
  `free()` 还一票，归零即释放包装。
- `jsFreeValue`（C 侧一次过界）同时做两件事：放掉一票 JS 引用、销毁堆上的包装。粒度更细的 helper
  只在 C 内部用（`definePropertyValue` 的 k / v 各只要一半），**不经 JNI 暴露**。
- 两条释放路径不能混：`jsReleaseValue` 只放引用，`jsDestroyHandle` 只销毁包装。合成一个会**悄悄偷走
  别人的票** —— 释放包装顺带把引用计数减一。拆开还让同一个句柄能在多次 JS 操作间复用（每次配一次
  `jsDupValue`），而包装只销毁一次。
- 还 `JSRef` 时还的是 `ref.ptr`，**不是** runtime 的 ctx 句柄 —— 混用就是拿 runtime 指针去
  `jsFreeValue`，当场踩坏 native 堆。

## 二、关闭与泄漏清算

- 本工程**不给 native 资源设 GC 兜底**（`Cleaner` 是 API 33，`minSdk = 24`；且清理动作捕获 `this`
  会让对象永不回收），显式 `close()` 是唯一销毁路径。
- 关闭前必须清算：`collectLeaks()` **先记录、再归还**。记录是为了暴露泄漏（归还之后就无迹可寻），
  归还是为了让 `JS_FreeRuntime` 的 `gc_obj_list` 断言不命中 —— 那断言一触发就 `abort()`，整个进程死，
  测试连报告都发不出来。
- 泄漏清单打到标准错误流，测试捕获它做断言（`QuickJSTest.unclosedValuesAreSweptOnClose`）。
- 登记表按**对象身份**强引用（`IdentityMap`）：指针会被复用、条目也可能被 GC 清掉，只有身份强引用
  才能保证「还没 close 的对象一定在册」。键只有 `JSRef`（未覆写 `equals` / `hashCode`）。

## 三、转换层

### `jsToJava` —— JS → Java 的唯一入口

- 类型分派、递归、防环、释放都在这一层，native 只留透传原语。绕开它自己转，顺着手碰到的对象会
  **悄悄变成 null**。
- `obj` 那一票在收尾由 `jsFreeValue` 放掉，调用方之后不得再碰；必须与调用同块落在归属线程上。
- `cache` 整棵对象图共用一张表，键是 JS 对象指针；**容器在填之前登记**（自引用与共享子对象因此天然
  成立），**函数不登记**（每次出现各造一个独占包装，谁拿到谁还）。
- 逐个 tag 的落法：`NULL` / `UNDEFINED` / `EXCEPTION` / symbol / bigint → `null`；`ArrayBuffer` 最先
  探针（一次过界拿字节）；Java 来路的对象还原成原实例（没有票、不进 cache）；可调用对象不展开
  （dup 出的新票归拿到它的人）；`JSError` 是纯数据，与 JS 侧脱钩；promise 不展开，转成 `Deferred`；
  普通对象整图展开成 `LinkedHashMap`（键的插入序即属性序）。

### `javaToJs` —— Java → JS

- `cache` 按**对象身份**记忆，用来切断循环引用。键必须用 `IdentityMap`：普通 `HashMap` 会对自引用的
  Map 无限递归算哈希（`StackOverflowError`），还会把「内容相等但不同一」的两个对象折叠成一个。
- `definePropertyValue` 完整接管 k / v 两个句柄，且句柄一次性、不能复用。
- `AutoCloseable` 不另开分支：`jsWrapObject` 挂的 `JavaObject` 就是析构回调回收它的抓手。

## 四、线程与关闭窗口

- 所有 native 调用投递到归属线程串行执行。默认共享一条（名为 `quickjs`）：一个 runtime 卡在长任务
  上会拖住别的 runtime；要独占就显式传 `ThreadDispatcher("quickjs-xxx")`。共享意味着没有哪一方有权
  关它，活到进程结束。
- 构造本身不切线程，所以在 JS 回调里再建 runtime 不死锁；但在回调里等它的结果就是自己等自己。
- `initPtr` 推迟到**首次读句柄**：`JS_NewRuntime` / `JS_SetMaxStackSize` 要把调用线程的帧地址记成
  `stack_top`。挪回构造期则字段全为 `0` 且编译器不报错，`timeout = 0` 等于关掉死循环中断
  （`PointerTest.initPtrSeesConstructionState` 钉着）。
- `stackSize` 必须**显著小于宿主线程栈**（JVM 默认约 1MB），否则深递归撞穿宿主栈、整个进程崩溃；
  默认 256KB 是留给 JNI / Java 帧的余量。
- 归还 `JSRef` 跑在「已标记、未销毁」窗口里：**不走** `withPtrSync`（`isClosed` 门会把它挡掉），也
  **不要改成** `submit`（会排在销毁之后，use-after-free）。线程保证来自调用链 —— 基类投递、落地后
  同步直调。
- `evaluate` 关闭后当场拒绝：晚于关闭消息投进来的求值会排在销毁之后。

## 五、`IdentityMap`

common 里没有按 `===` 比对的表：哈希一律给常数 0，查找退化成线性扫，但比的是 `===`，不会把内容
相等的两个对象合并。常数哈希是刻意的 —— `identityHashCode` 在 common 里不存在，而规模只有一次遍历
内的几十个容器。只实现 `get` / `set`，不实现 `MutableMap`。
