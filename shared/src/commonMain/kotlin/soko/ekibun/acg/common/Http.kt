package soko.ekibun.acg.common

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlin.getValue

expect fun createHttpClient(followRedirects: Boolean): HttpClient

object Http {
  private val clientWithRedirect by lazy {
    createHttpClient(followRedirects = true)
  }
  private val clientWithoutRedirect by lazy {
    createHttpClient(followRedirects = false)
  }

  suspend fun request(options: Map<Any, Any?>): HttpResponse {
    val url = options["url"] as String
    val client = if (options["redirect"] == "follow") clientWithRedirect else clientWithoutRedirect

    return client.request(url) {
      if (options["credentials"] == "omit") {
        headers.remove(HttpHeaders.Cookie) // 移除自动带上的 Cookie
      }

      (options["headers"] as? Map<*, *>)?.forEach { (key, value) ->
        val k = key.toString()
        if (value is Iterable<*>) {
          value.forEach {
            headers.append(k, it.toString())
          }
        } else {
          value?.let {
            headers.append(k, value.toString())
          }
        }
      }
      val body = options["body"]
      if (body != null) {
        if (body is Map<*, *> && body["__js_proto__"] == "FormData") {
          // 构建 Multipart/FormData
          setBody(
            MultiPartFormDataContent(
              formData {
                (body["__items__"] as? Iterable<*>)?.forEach { item ->
                  val formItem = item as? Map<*, *> ?: return@forEach
                  val name = formItem["name"] as? String ?: return@forEach
                  val value = formItem["value"]
                  val type = formItem["type"]

                  if (type is String && value is ByteArray) {
                    val fileName = formItem["fileName"] as? String
                    append(
                      key = name,
                      value = value,
                      headers =
                        Headers.build {
                          append(HttpHeaders.ContentType, type)
                          fileName?.let {
                            append(
                              HttpHeaders.ContentDisposition,
                              "filename=\"$it\"",
                            )
                          }
                        },
                    )
                  } else {
                    append(name, value.toString())
                  }
                }
              },
            ),
          )
        } else if (body is ByteArray) {
          setBody(body)
        } else {
          setBody(body.toString())
        }
      }
    }
  }
}
