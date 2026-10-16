package soko.ekibun.browser

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import soko.ekibun.hostApplicationContext

// Android 端的外部浏览器：Chrome Custom Tabs。
//
// 用 Custom Tabs 而不是自定义 scheme：自定义 scheme 要另一个 Activity 接、还得把结果
// 送回本进程；Custom Tabs 停在浏览器里，回调直接打到本进程的回环服务器上。
//
// 没装支持 Custom Tabs 的浏览器时不需要兜底 —— `CustomTabsIntent.Builder()` 建的就是
// 一条 `ACTION_VIEW` Intent，系统自己会派给默认浏览器。
//
// 起 Activity 要的 Context 用平台共用的 [hostApplicationContext]。

actual suspend fun launchExternalBrowser(url: String) {
  val customTabs = CustomTabsIntent.Builder().build()
  // 从进程级 Context（不是 Activity）起 Activity 必须带 NEW_TASK，否则系统当场抛。
  customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  customTabs.launchUrl(hostApplicationContext, Uri.parse(url))
}
