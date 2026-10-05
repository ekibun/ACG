package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(Java) {
    this.block()
    install(HttpCookies) {
      storage = AcceptAllCookiesStorage()
    }
  }
