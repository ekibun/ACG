package soko.ekibun

import android.content.Context

// 平台上要 `Context` 的进程级原语（WebView、外部浏览器）共用的槽。两者都用同一个
// application Context，没有各自失败面可言，所以只留一个。

/**
 * 进程级的 application `Context`，由宿主（androidApp 的 `MainActivity.onCreate`）在
 * `setContent` **之前**调 [setHostApplicationContext] 注入一次。
 *
 * 用它的都是命令式调用点（`createWebviewContext`、`launchExternalBrowser`），跑在
 * JsEngine 的协程上；可见页由 common 的壳建 —— 两边都没有组合上下文，拿不到
 * `LocalContext`，只能靠宿主注入。
 *
 * 只留 `applicationContext`（不是 Activity）：进程活着它就在，不存在泄漏；代价是
 * 从它起 Activity 必须带 `FLAG_ACTIVITY_NEW_TASK`。漏注入是宿主编程错误 ——
 * `lateinit` 会在第一次用到时自己炸，平台层不做兜底。
 */
lateinit var hostApplicationContext: Context
  private set

/** 注入 [hostApplicationContext]；只收 `applicationContext`。 */
fun setHostApplicationContext(context: Context) {
  hostApplicationContext = context.applicationContext
}
