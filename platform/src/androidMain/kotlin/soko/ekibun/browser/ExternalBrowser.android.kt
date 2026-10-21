package soko.ekibun.browser

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import soko.ekibun.hostApplicationContext

// Android 端的外部浏览器：Chrome Custom Tabs。

actual suspend fun launchExternalBrowser(url: String) {
  val customTabs = CustomTabsIntent.Builder().build()
  // 从进程级 Context 起 Activity 需要 NEW_TASK
  customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  customTabs.launchUrl(hostApplicationContext, Uri.parse(url))
}
