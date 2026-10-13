package soko.ekibun.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlin.getValue

expect fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient
