package soko.ekibun

import android.content.Context

// 平台上需要 Context 的进程级原语（WebView、外部浏览器）共用的槽。

/**
 * 进程级的 application `Context`，由宿主在 `setContent` 之前调 [setHostApplicationContext] 注入一次。
 */
lateinit var hostApplicationContext: Context
  private set

/** 注入 [hostApplicationContext]，只收 `applicationContext`。 */
fun setHostApplicationContext(context: Context) {
  hostApplicationContext = context.applicationContext
}
