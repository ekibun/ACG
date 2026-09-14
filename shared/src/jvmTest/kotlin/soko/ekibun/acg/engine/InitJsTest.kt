package soko.ekibun.acg.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import soko.ekibun.quickjs.JSFunction
import soko.ekibun.quickjs.JSInvokable
import soko.ekibun.quickjs.JSObject
import soko.ekibun.quickjs.QuickJS
import java.io.File
import java.nio.charset.Charset

/**
 * `files/js/init.js` 的回归测试。
 *
 * 覆盖三处已修的缺陷：
 * 1. `FormData.append` 误用 `__items__.append`（数组没有 append）；
 * 2. `entries()`/`keys()`/`values()`/`getAll()` 漏 `this.` 且用 `for...in`
 *    遍历数组（拿到的是索引字符串而不是元素）；
 * 3. `encodeURI_hex` 用 `toString(16)` 不补零，`\n` 会被编成 `%A`。
 *
 * 做法是把 init.js 原样喂给真正的 QuickJS，并注入一个 Java 侧桩顶替 `_java`
 * （TextEncoder 的 encode/decode 就靠它），这样验的是真实运行行为，
 * 而不是对源码做字符串断言。
 */
class InitJsTest {

  /** 从源码目录读，保证验的就是正在编辑的那份文件。 */
  private fun initSource(): String {
    val candidates = listOf(
      File("src/commonMain/composeResources/files/js/init.js"),
      File("shared/src/commonMain/composeResources/files/js/init.js"),
    )
    return candidates.firstOrNull { it.exists() }?.readText()
      ?: error("init.js not found, cwd=${File(".").absolutePath}")
  }

  /**
   * 按 [JsEngine] 的方式加载 init.js。
   *
   * init.js 整体是一个 `async (_java) => {...}` 表达式，求值直接得到该函数。
   * 注意它是**箭头函数**，函数体里的 `this` 是词法作用域里的那个 —— 也就是
   * `evaluate` 时的全局对象，而不是调用时传的 thisVal。所以
   * `Object.defineProperties(this, ...)` 注册出来的东西可以直接用
   * `ctx.evaluate` 取到。
   */
  private fun loadInit(ctx: QuickJS.Context, javaStub: JSInvokable) {
    val factory = assertIs<JSFunction>(ctx.evaluate(initSource(), name = "<init.js>"))
    try {
      runBlocking {
        val ret = factory.invoke(javaStub)
        (ret as? Deferred<*>)?.await()
      }
    } finally {
      factory.close()
    }
  }

  /**
   * `_java` 桩：真按 UTF-8 编解码，走 Kotlin 的 Charset。
   *
   * 调用形态是 `_java(obj, name, ...args)`，对应到 JSInvokable 就是
   * `argv[0] = obj`（这里是 null）、`argv[1] = name`、`argv[2..] = args`。
   */
  private fun stubInvokable() = object : JSInvokable {
    override fun invoke(vararg argv: Any?, thisVal: Any?): Any? {
      val method = argv.getOrNull(1) as? String ?: return null
      return when (method) {
        "encode" -> {
          val data = argv.getOrNull(2) as? String ?: ""
          val charset = (argv.getOrNull(3) as? String)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
          data.toByteArray(charset)
        }

        "decode" -> {
          val data = argv.getOrNull(2) as? ByteArray ?: ByteArray(0)
          val charset = (argv.getOrNull(3) as? String)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
          String(data, charset)
        }

        else -> null
      }
    }
  }

  @Test
  fun formDataAppendAndGetAll() {
    val ctx = QuickJS.Context()
    try {
      loadInit(ctx, stubInvokable())
      // 每个方法都在 _java 之外的纯 JS 里，直接整段验证
      val out = ctx.evaluate(
        """
        (function(){
          const fd = new FormData();
          fd.append('a', '1');
          fd.append('a', '2');
          fd.append('b', '3');
          fd.set('b', '4');
          fd.append('c', '5');
          fd.delete('c');
          return [fd.getAll('a'), fd.has('b'), fd.has('z'), fd.get('b'),
                  [...fd.keys()], [...fd.values()]];
        })()
        """.trimIndent(),
        name = "<formData>"
      ) as Array<*>
      // getAll 必须返回全部同名值（原实现 ret.append / return value 都是错的）
      assertEquals(listOf("1", "2"), (out[0] as Array<*>).toList())
      assertEquals(true, out[1])
      assertEquals(false, out[2])
      // get 返回 value，而不是整个 item 对象
      assertEquals("4", out[3], "get 应返回 value；set 应就地替换")
      assertEquals(listOf("a", "a", "b"), (out[4] as Array<*>).toList())
      assertEquals(listOf("1", "2", "4"), (out[5] as Array<*>).toList())
    } finally {
      ctx.close()
    }
  }

  @Test
  fun encodeUriPadsHexBytes() {
    val ctx = QuickJS.Context()
    try {
      loadInit(ctx, stubInvokable())
      // \n (0x0a) 必须编成 %0A；不补零的话会得到 %A
      assertEquals("%0A", ctx.evaluate("encodeURIComponent('\\n')"))
      // 空格 0x20 -> %20（本来就有两位，用于对照）
      assertEquals("%20", ctx.evaluate("encodeURIComponent(' ')"))
      // 多字节：中文 UTF-8 每个字节都必须是两位
      assertEquals("%E4%B8%AD", ctx.evaluate("encodeURIComponent('中')"))
      // 控制字符 0x00..0x0f 全都需要补零
      assertEquals("%01", ctx.evaluate("encodeURIComponent('\\u0001')"))
    } finally {
      ctx.close()
    }
  }

  @Test
  fun textEncoderRoundTrip() {
    val ctx = QuickJS.Context()
    try {
      loadInit(ctx, stubInvokable())
      val out = ctx.evaluate(
        "(function(){ const e = new TextEncoder(); const d = new TextDecoder();" +
          " return d.decode(e.encode('中文abc')); })()",
        name = "<roundtrip>"
      )
      assertEquals("中文abc", out)
    } finally {
      ctx.close()
    }
  }

  @Test
  fun responseJsonWorks() {
    val ctx = QuickJS.Context()
    try {
      loadInit(ctx, stubInvokable())
      // Response.text() 走 _java decode，json() 再 JSON.parse —— 串起整条链
      val out = ctx.evaluate(
        """
        (async function(){
          const body = new TextEncoder().encode('{"a":1}');
          const r = new Response({ status: 200, ok: true, body });
          return (await r.json()).a;
        })()
        """.trimIndent(),
        name = "<response>"
      )
      assertEquals(1L, runBlocking { assertIs<Deferred<Any?>>(out).await() })
    } finally {
      ctx.close()
    }
  }

  @Test
  fun noDartResidue() {
    val src = initSource()
    assertTrue(!src.contains("_dart"), "init.js 不应再引用 Dart 桥的 _dart")
    assertTrue(!src.contains("createClass"), "init.js 不应再有 createClass 残留")
    assertTrue(
      !Regex("for\\s*\\(\\s*var\\s+item\\s+in\\s+__items__").containsMatchIn(src),
      "不应再用 for...in 遍历 __items__ 数组"
    )
    assertTrue(
      !src.contains("__items__.append("),
      "FormData 不应再调用不存在的 __items__.append"
    )
  }
}
