package soko.ekibun.acg.engine

import org.junit.AssumptionViolatedException

/**
 * 宿主不可用时让用例记成 **skipped**，而不是「跑过了、什么也没验」。
 *
 * 只是 `println` + `return` 的话 Gradle 在 XML 里记的是 passed（`skipped="0"`），
 * 于是 CI 上的「全绿」与「一条都没验」长得一模一样。`AssumptionViolatedException` 是 JUnit4
 * 的「前提不成立」，Gradle 把它记成 skipped 并保留 [reason]。
 *
 * [System.getProperty] 那个开关是给维护者用的：宿主坏掉时想看**原始失败**（而不是一句转述的
 * 跳过原因），加 `-Dacg.test.skipUnavailableHost=false` 即可。
 */
internal fun skipUnlessHostIsAvailable(reason: String?) {
  if (reason == null) return
  if (System.getProperty("acg.test.skipUnavailableHost", "true").toBoolean()) {
    throw AssumptionViolatedException(reason)
  }
  println("[host] skip disabled on request, running anyway: $reason")
}
