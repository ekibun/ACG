package soko.ekibun.quickjs

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 探针：JS 侧丢掉一个 [AutoCloseable] 后，runtime 销毁时宿主收到一次 `close()`；非 [AutoCloseable] 则安静退场。
 */
class AutoCloseableFinalizerProbeTest {
  private class Counted : AutoCloseable {
    val closes = AtomicInteger(0)

    override fun close() {
      closes.incrementAndGet()
    }
  }

  /** 造一个身份函数；返回的 [JSFunction] 由调用方持有并负责归还。 */
  private suspend fun identity(q: QuickJS): JSFunction {
    val fn = q.evaluate("(function(x) { return x; })", "<probe>")
    @Suppress("UNCHECKED_CAST")
    return fn as JSFunction
  }

  @Test(timeout = 60_000)
  fun gcCollectedAutoCloseableCallsCloseOnce() {
    val counted = Counted()
    val q = QuickJS()
    runBlocking {
      // 只当实参传一次：jsCallImpl 的 finally 归还这张票
      // identity 返回的 JSFunction 是另一张票，用完自己还
      identity(q).use { fn -> fn.invoke(counted) }
      // 关 runtime 后 finalizer 跑一遍
      q.close()
    }
    assertEquals(
      1,
      counted.closes.get(),
      "the finalizer should call close once for JS when the runtime is destroyed, got ${counted.closes.get()} call(s)",
    )
  }

  @Test(timeout = 60_000)
  fun finalizerLeavesNonAutoCloseableAlone() {
    // 反向判据：非 AutoCloseable 的对象不调 close
    class Plain
    val q = QuickJS()
    runBlocking {
      identity(q).use { fn -> fn.invoke(Plain()) }
      q.close()
    }
  }
}
