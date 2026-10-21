package soko.ekibun.acg.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.acg.TestPaths
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 能力桥的真派发：走真 [JsEngine]、不注入桩，逐个分支锁 `args` 的形状与返回值。判据见 `docs/design/quickjs-ability-bridge.md`。
 */
class JsEngineDispatchTest {
  private lateinit var pageServer: TestPageServer

  /** `/page.html` 收到的请求头，用来证明 header 真的按 `args[1]` 送到了。 */
  private val pageRequests = Collections.synchronizedList(mutableListOf<Map<String, List<String>>>())

  @BeforeTest
  fun startServer() {
    TestPaths.ensureInitialized()
    pageRequests.clear()
    pageServer =
      TestPageServer(
        withJsonEndpoint = true,
        onMainFrame = { exchange ->
          pageRequests += exchange.requestHeaders.mapValues { (_, v) -> v.toList() }
        },
      )
  }

  @AfterTest
  fun stopServer() {
    pageServer.close()
    // 复位 JsEngine 自己持有的 runtime，避免常驻噪声进泄漏报告
    runBlocking { JsEngine.instance.reset() }
  }

  private fun url(path: String): String = pageServer.url(path)

  /** 求值一段 async 脚本并等它的 Promise。 */
  private fun evalAsync(
    body: String,
    timeoutMs: Long = 15_000,
  ): Any? =
    runBlocking {
      withTimeout(timeoutMs) {
        assertIs<Deferred<Any?>>(JsEngine.instance.evaluate("(async () => { $body })()", "<dispatch>"))
          .await()
      }
    }

  /** `encode` 分支：`args[0]` 待编码串、`args[1]` charset 名。 */
  @Test
  fun encodeBranchIsReachable() {
    assertEquals(
      "%0A",
      evalAsync("return encodeURIComponent('\\n');"),
      "the bytes from encode must feed encodeURI_hex's zero-padding logic (LF = 0x0A)",
    )
    assertEquals(
      "%E4%B8%AD",
      evalAsync("""return encodeURIComponent("\u4e2d");"""),
      "multi-byte characters must use two hex digits per byte",
    )
  }

  /** `decode` 分支：`args[0]` 字节、`args[1]` charset 名。 */
  @Test
  fun decodeBranchIsReachable() {
    assertEquals(
      "\u4e2d\u6587abc",
      evalAsync(
        """return new TextDecoder('utf-8').decode(new TextEncoder().encode('\u4e2d\u6587abc'));""",
      ),
    )
  }

  /** `console` 分支：`args[0]` level、`args[1]` 实参数组整体。 */
  @Test
  fun consoleBranchIsReachable() {
    assertEquals(1L, evalAsync("""console.log('dispatch', 'ok'); return 1;"""))
  }

  /** `fetch` 分支：`args[0]` 是 `Map`；响应的 status / ok / url / body 都送得过来。 */
  @Test
  fun fetchBranchIsReachable() {
    val target = url("/data.json")
    assertEquals(
      "200|true|$target|1",
      evalAsync(
        """
        const r = await fetch('$target');
        return r.status + "|" + r.ok + "|" + r.url + "|" + (await r.json()).a;
        """.trimIndent(),
      ),
    )
  }

  /** `Response.arrayBuffer` 独立桥：能读到 body；`bodyUsed` 初始 false，读过之后再读抛 `TypeError`。 */
  @Test
  fun responseArrayBufferReadsBodyOnceAndFlagsBodyUsed() {
    val target = url("/data.json")
    assertEquals(
      "1|false-ok|true",
      evalAsync(
        """
        const r = await fetch('$target');
        const before = r.bodyUsed;          // fetch must not have consumed the body yet
        const a = await r.json();
        let threw = false;
        try { await r.arrayBuffer(); } catch (e) { threw = e instanceof TypeError; }
        return a.a + "|" + before + "-" + (threw ? "ok" : "no-throw") + "|" + r.bodyUsed;
        """.trimIndent(),
      ),
    )
  }

  /** `webview` 分支：`args[0..3]` 依次是 url / header / script / onInterceptRequest。 */
  @Test
  fun webviewBranchIsReachable() {
    skipUnlessHostIsAvailable(WebViewHostTest.hostHealth)

    // 只在子资源上命中：主框架放行，页面才有机会请求 <script>
    val out =
      evalAsync(
        """
        const req = await webview('${url("/page.html")}', { "User-Agent": "dispatch-test" },
          "JSON.stringify(window.__subLoaded === true)",
          (request) => request.url.endsWith("/sub.js")
            ? { url: request.url, headers: { "Range": "bytes=0-" } }
            : null);
        const hs = Object.keys(req.headers || {});
        return req.url + "|" + (hs.length > 0 ? "headers-ok" : "no-headers") +
          "|" + (hs.indexOf("Range") < 0 ? "no-range" : "has-range");
        """.trimIndent(),
        timeoutMs = 60_000,
      )

    assertEquals(
      "${url("/sub.js")}|headers-ok|has-range",
      out,
      "the hit should be the subresource; the returned object is adopted wholesale (its own Range included)",
    )

    // args[1]（header）真的送到了
    val ua = pageRequests.firstOrNull()?.get("User-agent")?.firstOrNull()
    assertEquals("dispatch-test", ua, "webview headers must reach the page via args[1] (pageRequests=$pageRequests)")

    assertTrue(
      pageRequests.size >= 1,
      "a main-frame request should have reached the local server (proves WebView2 really navigated)",
    )
  }

  /** 不传 script 时 `webview` 回 `undefined`。 */
  @Test
  fun scriptlessWebviewYieldsUndefined() {
    skipUnlessHostIsAvailable(WebViewHostTest.hostHealth)
    val out =
      evalAsync(
        """
        const r = await webview('${url("/page.html")}', {}, null, null);
        return r === undefined ? "undefined" : "unexpected:" + String(r);
        """.trimIndent(),
        timeoutMs = 60_000,
      )
    assertEquals("undefined", out, "webview should yield undefined when no script is passed")
    assertTrue(
      pageRequests.isNotEmpty(),
      "a main-frame request should have reached the local server (proves WebView2 really navigated)",
    )
  }

  /** 导航失败时 `webview` reject，不回空值。 */
  @Test
  fun failedWebviewRejects() {
    skipUnlessHostIsAvailable(WebViewHostTest.hostHealth)
    val out =
      evalAsync(
        """
        try { await webview('http://127.0.0.1:1/', {}, null, null); return "no throw"; }
        catch (e) { return "caught"; }
        """.trimIndent(),
        timeoutMs = 60_000,
      )
    assertEquals("caught", out, "a failing webview must reject, not return an empty value")
  }

  /** `openBrowser`：`args[0]` 原样到 Kotlin 侧；缺 `{{callback}}` 占位符即 reject。 */
  @Test
  fun openBrowserWithoutPlaceholderRejects() {
    val out =
      evalAsync(
        """
        try { await openBrowser('https://example.com/auth?x=1'); return "no throw"; }
        catch (e) { return String(e.message).includes("{{callback}}") ? "placeholder-mentioned" : String(e.message); }
        """.trimIndent(),
      )
    assertEquals(
      "placeholder-mentioned",
      out,
      "a template without the placeholder must reject, and args[0] must arrive as-is",
    )
  }

  /** `FormData` 的形状：`__js_proto__` / `__items__` 与每项的 name / value / type / filename。 */
  @Test
  fun formDataShapeIsReachable() {
    val out =
      evalAsync(
        """
        const fd = new FormData();
        fd.append('a', '1');
        fd.append('a', '2');
        fd.set('b', '4');
        const bytes = new TextEncoder().encode('BIN');
        fd.append('file', bytes, 'x.bin');
        // 非字符串 value 折成 'bytes:<长度>'：ArrayBuffer 展开成 Map 是宿主侧的事，JS 侧折更省事。
        // `missing()` 必须区分 null / undefined —— 回宿主都是 Kotlin null，而 `init.js` 两种都产出。
        const show = (v) => typeof v === 'string' ? v : 'bytes:' + v.byteLength;
        const missing = (v) => v === undefined ? '<absent>' : v === null ? '<null>' : String(v);
        return {
          proto: fd.__js_proto__,
          items: fd.__items__.map((i) => ({
            name: i.name,
            value: show(i.value),
            filename: missing(i.filename),
            type: missing(i.type)
          })),
          getAllA: fd.getAll('a'),
          getB: fd.get('b'),
          hasB: fd.has('b'),
          hasZ: fd.has('z'),
          keys: [...fd.keys()],
          values: [...fd.values()].map(show),
          // entries() 是 forEach 的底座，也是插件遍历 FormData 的常规入口 ——
          // 三元组 [name, value, filename] 的形状得一起锁住。value 槽用 show()
          // （折成 bytes:<长度>），filename 槽用 missing()（区分 null/undefined）。
          entries: [...fd.entries()].map((e) => e.map((v, i) => i === 1 ? show(v) : missing(v)).join('|'))
        };
        """.trimIndent(),
      )
    val m = assertIs<Map<*, *>>(out, "the FormData assertion script should return an object, got $out")
    assertEquals("FormData", m["proto"], "`__js_proto__` is the entry point Http.kt uses to route multipart")
    val items = assertIs<List<*>>(m["items"], "__items__ must be an array, got ${m["items"]}")

    // 前三项：普通字段，没有 filename 也没有 type
    for (i in 0..2) {
      val it = assertIs<Map<*, *>>(items[i], "item ${i + 1} should be a plain field")
      assertEquals("<absent>", it["filename"], "a plain field must not have a filename key at all")
      assertEquals("<null>", it["type"], "only file parts carry a Content-Type, so a plain field's type should be null")
    }
    val a1 = assertIs<Map<*, *>>(items[0])
    assertEquals("a", a1["name"])
    assertEquals("1", a1["value"])

    // 带 filename 的那项：type 与 filename 同时出现
    val file = assertIs<Map<*, *>>(items[3], "item 4 should be append('file', bytes, 'x.bin')")
    assertEquals("file", file["name"])
    assertEquals("bytes:3", file["value"], "a file item's value should be a binary buffer ('BIN' is 3 bytes)")
    assertEquals("x.bin", file["filename"], "filename must be delivered verbatim (it is the key Http.kt reads)")
    assertEquals(
      "application/octet-stream",
      file["type"],
      "an item with a filename must also carry a type, or Http's file branch is never taken",
    )

    assertEquals(listOf("1", "2"), assertIs<List<*>>(m["getAllA"]), "appending the same name should accumulate")
    assertEquals("4", m["getB"], "get returns the value, not the item object")
    assertEquals(true, m["hasB"])
    assertEquals(false, m["hasZ"])
    assertEquals(
      listOf("a", "a", "b", "file"),
      assertIs<List<*>>(m["keys"]),
      "keys() must list every item (including duplicate names)",
    )
    assertEquals(
      listOf("1", "2", "4", "bytes:3"),
      assertIs<List<*>>(m["values"]),
      "values() must yield the value sequence",
    )
    assertEquals(
      listOf("a|1|<absent>", "a|2|<absent>", "b|4|<absent>", "file|bytes:3|x.bin"),
      assertIs<List<*>>(m["entries"]),
      "entries() must yield [name, value, filename] triples (forEach walks this)",
    )
  }

  /** 宿主体检结果，`null` 表示健康。 */
  private fun hostHealth(): String? = WebViewHostTest.hostHealth
}
