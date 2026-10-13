package soko.ekibun.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.java.Java

actual fun createHttpClientImpl(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(Java) {
    this.block()
  }
