package soko.ekibun.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 宿主可用性体检：**直构**一个 [Webview2Context]，真导航一次 `about:blank`。
 *
 * 与 `:shared` 那侧 `WebViewHostTest.checkHost` 是同一套理由（那边有完整说明）：这台机器上
 * 实测到过一种坏法 —— 环境建得成、控制器建得成、`Navigate` 也返回 S_OK，但**引擎完全不动**，
 * 连 `about:blank` 都到不了完成事件。这种坏法下用例会卡满超时、再报一堆看不懂的断言失败，
 * 纯属噪声；体检一次、打印清楚原因、几个用例一起跳过，才对得起看构建的人。
 *
 * 之所以不走驱动（`loadBackgroundWebView`）：`:platform` 的测试**不该依赖 `:shared`**，
 * 依赖方向反了。这里就用平台自己那套 —— 建上下文 → 给尺寸 → 挂 `onEvent` → 导航 → 等
 * `PageFinished` → `evaluate("1 + 1")` → 归还。
 *
 * @return null 表示健康；否则是给人看的跳过原因。
 */
internal fun webviewHostHealth(): String? =
  runBlocking {
    val ctx =
      runCatching { Webview2Context() }.getOrElse { return@runBlocking "宿主起不来：${it.message ?: it}" }
    try {
      val loaded = CompletableDeferred<Unit>()
      val json =
        withTimeoutOrNull(HOST_HEALTH_TIMEOUT_MS) {
          ctx.onEvent = { event -> if (event is WebViewEvent.PageFinished) loaded.complete(Unit) }
          // 没 attach 的上下文没有「父窗口客户区」可用，尺寸得自己给；否则 controller
          // 一直是 0×0，页面未必会真的排版。
          ctx.setBounds(800, 600)
          ctx.navigate("about:blank")
          loaded.await()
          ctx.evaluate("1 + 1")
        }
      when {
        json == null ->
          "宿主编译得起来但引擎没有响应：about:blank 在 ${HOST_HEALTH_TIMEOUT_MS}ms 内" +
            "没有走到 PageFinished。多半是这台机器上的 WebView2 运行时坏了" +
            "（重装 Evergreen Runtime 通常能好）。"
        json == "2" -> null
        else -> "宿主异常：about:blank 健康检查返回 $json"
      }
    } finally {
      // 超时那条路进来时协程已被 withTimeoutOrNull 取消，挂起的 dispose 会立刻抛 ——
      // 不包 NonCancellable 就等于把整个上下文漏掉。
      withContext(NonCancellable) { runCatching { ctx.dispose() } }
    }
  }

/** 体检的护栏：比后台任务的 30s 略宽一点。 */
private const val HOST_HEALTH_TIMEOUT_MS = 40_000L
