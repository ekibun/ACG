package soko.ekibun.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.cookies.HttpCookies
import soko.ekibun.web.WebViewCookieStorage

private val storageLazy = lazy { WebViewCookieStorage }
private val webViewCookieStorage by storageLazy

actual fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(Java) {
    this.block()
    // 用 WebView2 的 cookie 库作存储：HTTP 请求与 WebView 页面共享同一套登录态。
    install(HttpCookies) {
      storage = webViewCookieStorage
    }
  }
