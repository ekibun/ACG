package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createHttpClient(followRedirects: Boolean): HttpClient = HttpClient(OkHttp) {
    install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
    }
}