package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createHttpClient(followRedirects: Boolean): HttpClient =
  HttpClient(Java) {
    install(HttpCookies) {
      storage = AcceptAllCookiesStorage()
    }
    // 与 Android 那侧同款：显式把引擎层「闲置超时」设成无限，判据**唯一**交给
    // [Http.Response.read] 内那支 `select onTimeout`（见 [SOCKET_TIMEOUT_MS]）。写在这里只为与
    // Android 侧**写法对称** —— Java engine 本就不认 `socketTimeoutMillis`，装了会被静默忽略。
    install(HttpTimeout) {
      socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
  }
