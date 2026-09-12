package soko.ekibun.acg.engine

import androidx.annotation.Keep
import acg.shared.generated.resources.Res
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import io.ktor.util.toMap
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.common.Http
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSObject
import soko.ekibun.quickjs.QuickJS
import java.nio.charset.Charset

class JsEngine {
  companion object {
    val instance by lazy { JsEngine() }
  }

  private var quickjsDelegate: QuickJS.Context? = null
  private val quickjs: QuickJS.Context
    get() = quickjsDelegate ?: run {
      val moduleHandler = { module: String ->
        val modulePath = if (module == "@init") "files/js/init.js" else
          "files/js/module/" + module.replaceFirst(".js$".toRegex(), "") + ".js"
        runBlocking {
          try {
            Res.readBytes(modulePath).decodeToString()
          } catch (_: Exception) {
            null
          }
        }
      }
      val ctx1 = QuickJS.Context(
        moduleHandler = moduleHandler
      )
      quickjsDelegate = ctx1
      val init = ctx1.evaluate(moduleHandler("@init")!!, "<init>") as JSInvokable
      init(object : JSInvokable {
        override fun invoke(vararg argv: Any?, thisVal: Any?): Any? {
          val obj = argv[0]
          return if (obj is String) {
            val cls = javaClass.classLoader?.loadClass("soko.ekibun.nekomp.engine.$obj")
            cls!!.constructors[0].newInstance(*argv.sliceArray(1 until argv.size))
          } else {
            val methodName = argv[1] as String
            val objWrap = (obj ?: this@JsEngine)
            val method = objWrap.javaClass.declaredMethods.first { it.name == methodName }
            method.isAccessible = true
            method.invoke(objWrap, *argv.sliceArray(2 until argv.size))
          }
        }
      })
      ctx1
    }

  fun evaluate(cmd: String, name: String = "<eval>"): Any? {
    return quickjs.evaluate(cmd, name)
  }

  fun reset() {
    quickjsDelegate = null
    System.gc()
  }

  @Keep
  private fun console(type: String, data: Array<Any?>) {
    println("$type\n${data.toList()}")
  }

  @Keep
  private fun encode(input: String, to: String?): ByteArray {
    return input.toByteArray(Charset.forName(to?:"utf-8"))
  }

  @Keep
  private fun decode(input: ByteArray, from: String?): String {
    return String(input, Charset.forName(from?:"utf-8"))
  }

  @Keep
  private fun fetchAsync(options: JSObject): Deferred<Any?> {
    return CoroutineScope(Dispatchers.IO).async {
      val response = Http.request(options)
      assert(response.isActive)
      return@async mapOf(
        "url" to response.request.url.toString(),
        "headers" to response.headers.toMap(),
        "ok" to response.status.isSuccess(),
        "redirected" to (response.status.value in 300..399),
        "status" to response.status.value,
        "body" to response.bodyAsChannel().toByteArray(),
      )
    }
  }
}