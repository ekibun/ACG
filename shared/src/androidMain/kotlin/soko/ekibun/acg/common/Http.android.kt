package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(OkHttp) {
    this.block()
    install(HttpCookies) {
      storage = AcceptAllCookiesStorage()
    }
  }
