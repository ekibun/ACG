package soko.ekibun.web

import org.junit.AssumptionViolatedException

/** 宿主不可用时把用例记成 skipped（`AssumptionViolatedException`），而不是 passed。 */
internal fun skipUnlessHostIsAvailable(reason: String?) {
  if (reason == null) return
  if (System.getProperty("acg.test.skipUnavailableHost", "true").toBoolean()) {
    throw AssumptionViolatedException(reason)
  }
  println("[host] skip disabled on request, running anyway: $reason")
}
