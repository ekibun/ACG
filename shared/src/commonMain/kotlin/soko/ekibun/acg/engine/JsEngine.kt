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
import soko.ekibun.quickjs.JSError
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSObject
import soko.ekibun.quickjs.QuickJS
import java.nio.charset.Charset

class JsEngine {
  companion object {
    val instance by lazy { JsEngine() }

    /** 引擎插件类的包名。 */
    private const val ENGINE_PACKAGE = "soko.ekibun.acg.engine"

    /** 判断一个实参能否赋给指定的形参类型（含装箱与数值放宽）。 */
    private fun acceptsArg(paramType: Class<*>, arg: Any?): Boolean {
      if (arg == null) return !paramType.isPrimitive
      // 基本类型按名字映射到装箱类名，避免在 Kotlin 里引用 java.lang.Integer.TYPE 等
      val boxedName = if (paramType.isPrimitive) {
        when (paramType.name) {
          "boolean" -> "java.lang.Boolean"
          "char" -> "java.lang.Character"
          "byte" -> "java.lang.Byte"
          "short" -> "java.lang.Short"
          "int" -> "java.lang.Integer"
          "long" -> "java.lang.Long"
          "float" -> "java.lang.Float"
          "double" -> "java.lang.Double"
          else -> paramType.name
        }
      } else paramType.name
      if (!paramType.isPrimitive && paramType.isInstance(arg)) return true
      if (arg::class.java.name == boxedName) return true
      // 数值放宽：JS 侧拿到的整数都是 Double，需要能落到 Int/Long/Float 形参上
      if (arg !is Number) return false
      return when (boxedName) {
        "java.lang.Integer", "java.lang.Long", "java.lang.Short",
        "java.lang.Byte", "java.lang.Float", "java.lang.Double" -> true
        else -> false
      }
    }
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
            // 按名称实例化引擎插件类。包名必须跟随本工程的实际包名，
            // 而不是从别处拷来的 `soko.ekibun.nekomp.*`。
            val className = "$ENGINE_PACKAGE.$obj"
            val cls = javaClass.classLoader?.loadClass(className)
              ?: throw JSError("cannot load class '$className'")
            val ctorArgs = argv.sliceArray(1 until argv.size)
            // 按实参个数选构造函数，不要盲取 constructors[0]（顺序无保证，
            // 且多个构造函数时会选错）。
            val ctor = cls.constructors.firstOrNull { it.parameterCount == ctorArgs.size }
              ?: throw JSError(
                "no constructor of '$className' accepts ${ctorArgs.size} argument(s)"
              )
            ctor.isAccessible = true
            ctor.newInstance(*ctorArgs)
          } else {
            val methodName = argv[1] as String
            val objWrap = (obj ?: this@JsEngine)
            val callArgs = argv.sliceArray(2 until argv.size)
            // 重载时 declaredMethods 里会有多个同名方法，`first{}` 可能选错。
            // 用"名字 + 参数个数"匹配，仍不唯一时再按参数类型宽容匹配。
            val candidates = objWrap.javaClass.methods
              .filter { it.name == methodName && it.parameterCount == callArgs.size }
            val method = candidates.firstOrNull { m ->
              m.parameterTypes.withIndex().all { (i, t) -> acceptsArg(t, callArgs[i]) }
            } ?: candidates.firstOrNull()
            ?: throw JSError(
              "no method '$methodName' with ${callArgs.size} argument(s) " +
                "on ${objWrap.javaClass.name}"
            )
            method.isAccessible = true
            method.invoke(objWrap, *callArgs)
          }
        }
      })
      ctx1
    }

  fun evaluate(cmd: String, name: String = "<eval>"): Any? {
    return quickjs.evaluate(cmd, name)
  }

  fun reset() {
    // 主动销毁 runtime，而不是只把引用置空等 GC：JS 侧的 Java 对象持有
    // global ref，只有 destroyContext 触发的析构才会把它们还回去。
    quickjsDelegate?.close()
    quickjsDelegate = null
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