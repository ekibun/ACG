package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createHttpClient(followRedirects: Boolean): HttpClient =
  HttpClient(OkHttp) {
    install(HttpCookies) {
      storage = AcceptAllCookiesStorage()
    }
    // 显式把引擎层的「闲置超时」设成无限：这条判据**唯一**交给 [Http.Response.read] 内那支
    // `select onTimeout`（见 [SOCKET_TIMEOUT_MS]），两平台行为才一致。不装的话 OkHttp 自带的
    // 10 s read timeout 会照旧生效，而那走的不是我们这支（成因见 http-streaming.md 第一节）。
    install(HttpTimeout) {
      socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
  }
