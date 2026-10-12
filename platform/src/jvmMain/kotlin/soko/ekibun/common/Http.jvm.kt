package soko.ekibun.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.cookies.HttpCookies
import kotlinx.coroutines.runBlocking
import soko.ekibun.web.WebViewCookieStorage

private val storageLazy = lazy { WebViewCookieStorage() }
private val webViewCookieStorage by storageLazy

fun closeWebViewCookieStorage() {
  // 必须**等归还落地**再返回：close() 是发完不管，而调用方紧接着往往就是
  // releaseEnvironment() —— STA 线程一拆，~WebviewContext 里「投回 STA 关
  // 控制器 / 销毁宿主窗口」的活儿就没了落点（只能走 threadAlive() 的兜底
  // 分支，窗口与控制器又漏回进程退出）。
  if (storageLazy.isInitialized()) {
    runBlocking { storageLazy.value.closeDeferred().join() }
  }
}

actual fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(Java) {
    this.block()
    // 用 WebView2 的 cookie 库作存储：HTTP 请求与 WebView 页面共享同一套登录态。
    install(HttpCookies) {
      storage = webViewCookieStorage
    }
  }
