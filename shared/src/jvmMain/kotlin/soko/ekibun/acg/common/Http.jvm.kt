package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createHttpClient(followRedirects: Boolean): HttpClient = HttpClient(Java) {
    install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
    }
}