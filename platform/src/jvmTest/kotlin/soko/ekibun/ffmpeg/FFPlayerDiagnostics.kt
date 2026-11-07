package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking

/** 在守护线程上跑一次挂起调用，[timeoutMs] 内不返回就失败（消息带线程快照）。返回耗时供断言上界。 */
internal fun probeBounded(
  label: String,
  timeoutMs: Long,
  block: suspend () -> Unit,
): Long {
  val started = System.currentTimeMillis()
  var failure: Throwable? = null
  val thread =
    Thread {
      runCatching { runBlocking { block() } }.onFailure { failure = it }
    }.apply {
      isDaemon = true
      name = "probe-$label"
      start()
    }
  thread.join(timeoutMs)
  failure?.let { throw AssertionError("$label threw ${it.javaClass.simpleName}: ${it.message}", it) }
  if (thread.isAlive) {
    throw AssertionError(
      "$label did not return within ${timeoutMs}ms -- the call is stuck. Thread snapshot:/n" + threadSnapshot(),
    )
  }
  return System.currentTimeMillis() - started
}

/** 挂住时打出来的线程快照：每根线程一行的摘要 + 归属线程的完整栈。 */
internal fun threadSnapshot(): String {
  val traces = Thread.getAllStackTraces().filterKeys { !it.name.startsWith("DestroyJavaVM") }
  val out = StringBuilder()

  out.append("### summary (one line per thread)\n")
  traces.forEach { (t, stack) ->
    val top =
      stack.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
    out
      .append("  ")
      .append(t.name)
      .append(" [")
      .append(t.state)
      .append(", ")
      .append(stack.size)
      .append(" frames] ")
      .append(top)
      .append('\n')
  }

  out.append("\n### full stack of owner threads\n")
  traces.forEach { (t, stack) ->
    val named = t.name == "avformat" || t.name == "avcodec"
    val idlePoolWorker =
      stack.any {
        it.className.contains("LinkedBlockingQueue") || it.className.contains("ThreadPoolExecutor")
      }
    if (!named && !idlePoolWorker) return@forEach
    out
      .append("--- ")
      .append(t.name)
      .append(" (")
      .append(t.state)
      .append(") ---\n")
    stack.take(20).forEach { out.append("    ").append(it).append('\n') }
    if (stack.size > 20) out.append("    ... (").append(stack.size - 20).append(" more frames)\n")
  }
  return out.toString()
}
