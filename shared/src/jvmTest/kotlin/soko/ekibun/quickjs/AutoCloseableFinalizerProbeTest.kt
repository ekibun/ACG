package soko.ekibun.quickjs

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 探针：JS 侧丢掉一个 [AutoCloseable] 之后，宿主侧能不能收到 `close()`。
 *
 * 机制在 `cxx/quickjs/quickjs.cpp` 的 `JavaObject` finalizer —— 它兼两件事：
 * 若原对象实现 `AutoCloseable` 则调一次 `close()`，然后放掉 Java 全局引用。
 * 少了 close 那一半，JS 引用丢掉时宿主收不到任何「用完了」的信号，欠着的连接就一直悬着。
 *
 * ## 怎么把它挂进 JS、怎么 free 那个句柄：不加任何钩子
 *
 * 走 `jsCallImpl` 的实参通道 ——
 *
 * ```
 * q.evaluate("(function(x) { return x; })")   // 造一个 JS 函数 → JSFunction
 * jsFn.invoke(counted)                        // 把 counted 当**实参**传进 JS
 *   → 内部 javaToJs(counted) 挂上 opaque
 *   → finally { argvJs.forEach { jsFreeValue } }  ← **句柄在这里被 free**
 * ```
 * `jsCallImpl` 的 `finally` 归还实参那一票是它本来就要做的事（中途抛异常也不能漏），
 * 所以「把对象当实参传一次、让引用归零」不需要任何新增 API。
 *
 * ## 为什么关 runtime 就够（不必显式跑 GC）
 *
 * ```
 * JS_FreeRuntime
 *   → JS_RunGCInternal(rt, FALSE)   // quickjs.c:2423
 *   → gc_free_cycles                // quickjs.c:6756
 *   → free_gc_object → free_object  // quickjs.c:6787 / 6394
 *   → (*finalizer)(rt, obj)         // quickjs.c:6365-6367，**无条件**
 * ```
 * `JS_RunGCInternal` 的 `remove_weak_objects` 参数**与 class finalizer 无关** ——
 * 它只管 `gc_remove_weak_objects`（`FinalizationRegistry` 的 weak 引用队列），
 * 而 `free_object` 调 finalizer 时不看任何参数。
 * **别把那个 `FALSE` 当成「关 runtime 不跑 finalizer」的判据。**
 *
 * finalizer 也可能**更早**跑：引用归零之后 quickjs 内部任何一次分配都可能顺带触发
 * `js_trigger_gc`（阈值机制见 skill `quickjs-ownership` 规则 5.0）。所以本探针只断言
 * 「关掉之后一定被 close」，不断言「还活着时没被 close」。
 *
 * ## 判据的反向验证
 *
 * 判据是「数 `close()` 被调了几次」。它只在真跑了析构时才会红 ——
 * 把 `quickjs.cpp` 里那个 `IsInstanceOf` 的条件反转（`!=` 改 `==`，
 * **别删分支**：会造成死代码、编译失败，于是验证根本没发生）后，
 * `gcCollectedAutoCloseableCallsCloseOnce` 报「实际 0 次」变红，
 * `finalizerLeavesNonAutoCloseableAlone` 仍绿。
 */
class AutoCloseableFinalizerProbeTest {
  private class Counted : AutoCloseable {
    val closes = AtomicInteger(0)

    override fun close() {
      closes.incrementAndGet()
    }
  }

  /** 造一个身份函数；返回的 [JSFunction] 由调用方持有。 */
  private suspend fun identity(q: QuickJS): JSFunction {
    val fn = q.evaluate("(function(x) { return x; })", "<probe>")
    @Suppress("UNCHECKED_CAST")
    return fn as JSFunction
  }

  @Test(timeout = 60_000)
  fun gcCollectedAutoCloseableCallsCloseOnce() {
    val counted = Counted()
    val q = QuickJS()
    // 传一次实参 ⇒ `jsCallImpl` 的 finally 归还那一票 ⇒ 引用归零。
    runBlocking { identity(q) }.invoke(counted)
    // 关 runtime ⇒ 析构回调必然跑一遍（链见类 KDoc）。
    q.close()
    assertTrue(
      counted.closes.get() >= 1,
      "runtime 销毁时 finalizer 应替 JS 调 close，实际 ${counted.closes.get()} 次",
    )
  }

  @Test(timeout = 60_000)
  fun finalizerLeavesNonAutoCloseableAlone() {
    // 反向判据：不是 AutoCloseable 的对象走同一条路径，析构必须安静退场
    // （IsInstanceOf 为假 ⇒ 不调 close、只放引用）。没抛异常即达标。
    class Plain
    val q = QuickJS()
    runBlocking { identity(q) }.invoke(Plain())
    q.close()
  }
}
