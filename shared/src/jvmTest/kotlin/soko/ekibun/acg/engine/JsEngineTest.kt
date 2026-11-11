package soko.ekibun.acg.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.TestServer
import soko.ekibun.acg.TestEnv
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 拦截回调里记下一次请求的四个入参。 */
private data class SeenRequest(
  val url: String,
  val headers: Map<String, String>,
  val method: String,
  val isForMainFrame: Boolean,
)

/**
 * 能力桥的真派发（走真 `JsEngine`）：`args` 形状与返回值、`oauth` 的回调编排、
 * 以及 WebView2 宿主的端到端。判据见 `docs/design/quickjs-ability-bridge.md`。
 */
class JsEngineTest {
  private lateinit var pageServer: TestServer

  @BeforeTest
  fun startServer() {
    TestEnv.ensurePaths()
    pageServer =
      TestServer(
        pages = true,
        echoEndpoint = true,
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

  /** `encode` / `decode` / `console` 三条分支：`args` 的形状与返回值。 */
  @Test(timeout = 120000)
  fun textAndConsoleBranchesAreReachable() {
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
    assertEquals(
      "\u4e2d\u6587abc",
      evalAsync(
        """return new TextDecoder('utf-8').decode(new TextEncoder().encode('\u4e2d\u6587abc'));""",
      ),
      "decode must round-trip a multi-byte string",
    )
    assertEquals(1L, evalAsync("""console.log('dispatch', 'ok'); return 1;"""), "console.log must not break the script")
  }

  /** `fetch` 两面：请求的方法 / `Content-Type` / body 原样发出；响应送回 status 与 `bodyUsed` 语义。 */
  @Test(timeout = 120000)
  fun fetchCarriesBothRequestAndResponseShapes() {
    val target = url("/data.json")
    assertEquals(
      "200|true|$target|1|false-ok|true",
      evalAsync(
        """
        const r = await fetch('$target');
        const before = r.bodyUsed;          // fetch must not have consumed the body yet
        const a = await r.json();
        let threw = false;
        try { await r.arrayBuffer(); } catch (e) { threw = e instanceof TypeError; }
        return r.status + "|" + r.ok + "|" + r.url + "|" + a.a + "|" + before + "-" + (threw ? "ok" : "no-throw") + "|" + r.bodyUsed;
        """.trimIndent(),
      ),
      "fetch must expose status/ok/url, and its body may only be read once",
    )

    // 请求侧：打到 /echo，看服务端收到的是什么
    val echoStatus =
      evalAsync(
        """
        const rsp = await fetch('${url("/echo")}', {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": "acg-test/0.1"
          },
          body: "grant_type=authorization_code&code=probe"
        });
        return rsp.status;
        """.trimIndent(),
      )
    assertEquals(200L, echoStatus)
    val echo = assertNotNull(pageServer.echo.get(), "the server never received the POST")
    assertEquals("POST", echo.method, "the fetch method must propagate to the outgoing request method")
    assertEquals(
      "application/x-www-form-urlencoded",
      echo.contentType?.substringBefore(';'),
      "an explicit Content-Type must not be overridden by the body's default type",
    )
    assertEquals("grant_type=authorization_code&code=probe", echo.body)
  }

  /**
   * `webview` 的四参数契约：url / header / script / onInterceptRequest，命中后返回对象整份被采纳。
   * 守的是 `init.js` 给插件的 API —— 没有内置插件调它，UA 走 `fetch`。
   */
  @Test(timeout = 120000)
  fun webviewInterceptionAdoptsTheReturnedObject() {
    TestEnv.skipUnlessHostAvailable()

    val intercepted =
      evalAsync(
        """
        // 自定义头当探针：headers 只作用于本次导航，全局 UA 走 options.userAgent
        const seen = [];
        const req = await webview('${url("/page.html")}', {
          headers: { "X-Probe": "dispatch-test" },
          script: "JSON.stringify(window.__subLoaded === true)",
          onInterceptRequest: (request) => {
            if (request.isForMainFrame) {
              const headers = request.headers || {};
              const key = Object.keys(headers).find((n) => n.toLowerCase() === "x-probe");
              seen.push(key ? headers[key] : "none");
            }
            return request.url.endsWith("/sub.js")
              ? { url: request.url, headers: { "Range": "bytes=0-" } }
              : null;
          },
        });
        const hs = Object.keys(req.headers || {});
        return [req.url, seen[0] || "no-main-frame",
          hs.length > 0 ? "headers-ok" : "no-headers",
          hs.indexOf("Range") < 0 ? "no-range" : "has-range"].join("|");
        """.trimIndent(),
        timeoutMs = 60_000,
      )
    assertEquals(
      "${url("/sub.js")}|dispatch-test|headers-ok|has-range",
      intercepted,
      "the hit must be the subresource (its object adopted wholesale) and args[1]'s header must reach the page",
    )
  }

  /** 两个同步回调都抛异常：不能穿过 native 回调，页面照常加载完并交出脚本结果。 */
  @Test(timeout = 120000)
  fun webviewCallbacksThatThrowDoNotBreakThePage() {
    TestEnv.skipUnlessHostAvailable()

    val result =
      evalAsync(
        """
        const r = await webview('${url("/page.html")}', {
          script: "JSON.stringify(window.__subLoaded === true)",
          onInterceptRequest: () => { throw new Error("intercept boom"); },
          onNavigate: () => { throw new Error("navigate boom"); },
        });
        return r === "true" ? "page-loaded" : "unexpected:" + String(r);
        """.trimIndent(),
        timeoutMs = 60_000,
      )
    assertEquals("page-loaded", result, "a throwing callback must not break navigation")
  }

  /** 导航前回调返回非布尔值：取消这次导航，并以它作为 `webview` 的结果提前收工。 */
  @Test(timeout = 120000)
  fun onNavigateReturningAValueEndsTheWebview() {
    TestEnv.skipUnlessHostAvailable()

    val landed =
      evalAsync(
        """
        const r = await webview('${url("/page.html")}', {
          script: "location.href = '/other.html'; null",
          onNavigate: (target) => (target.endsWith('/other.html') ? { landed: target } : false),
        });
        return r && r.landed ? r.landed : "unexpected:" + JSON.stringify(r);
        """.trimIndent(),
        timeoutMs = 60_000,
      )
    assertEquals(url("/other.html"), landed, "a non-boolean return must end the task with that value")
  }

  /** 拦截命中：返回值经 JSON 过桥，`webview()` 兑现成插件给的那个值（对象和裸字符串都要能拿回）。 */
  @Test(timeout = 120000)
  fun interceptDecisionEndsTheWebviewAsADataValue() {
    TestEnv.skipUnlessHostAvailable()

    val out =
      evalAsync(
        """
        const page = '${url("/page.html")}';
        const hit = { onInterceptRequest: (request) => (request.url.endsWith('/sub.js') ? request.url : false) };
        const object = await webview(page, { onInterceptRequest: (request) => (request.url.endsWith('/sub.js') ? { via: "plugin" } : false) });
        const text = await webview(page, hit);
        return JSON.stringify([object && object.via, text]);
        """.trimIndent(),
        timeoutMs = 60_000,
      )
    assertEquals(
      """["plugin","${url("/sub.js")}"]""",
      out,
      "an interception hit must come back as the data the plugin returned, text included",
    )
  }

  /** 导航失败：必须 reject，不回空值。 */
  @Test(timeout = 120000)
  fun webviewRejectsWhenNavigationFails() {
    TestEnv.skipUnlessHostAvailable()

    val failed =
      evalAsync(
        """
        try { await webview('http://127.0.0.1:1/'); return "no throw"; }
        catch (e) { return "caught"; }
        """.trimIndent(),
        timeoutMs = 60_000,
      )
    assertEquals("caught", failed, "a failing webview must reject, not return an empty value")
  }

  /** 模板里没占位符：必须 reject，且 `args[0]` 原样到达 Kotlin 侧。 */
  @Test(timeout = 120000)
  fun oauthRejectsTemplateWithoutPlaceholder() =
    runBlocking {
      assertEquals(
        "placeholder-mentioned",
        evalAsync(
          """
          try { await oauth('https://example.com/auth?x=1'); return "no throw"; }
          catch (e) { return String(e.message).includes("{{callback}}") ? "placeholder-mentioned" : String(e.message); }
          """.trimIndent(),
        ),
        "a template without the placeholder must reject, and args[0] must arrive as-is",
      )
    }

  /** 占位符换成编码后的回调地址，插件拿到浏览器原样请求的 URL；双重解码会让 `state` 露馅。 */
  @Test(timeout = 120000)
  fun oauthSubstitutesEncodedCallback() =
    runBlocking {
      val launched = CompletableDeferred<String>()
      val login =
        async {
          JsEngine.instance.oauthAsync(TEMPLATE) { launched.complete(it) }
        }

      val authorizeUrl = launched.await()
      assertFalse(authorizeUrl.contains("{{callback}}"), "the placeholder must be substituted: $authorizeUrl")
      assertTrue(
        authorizeUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A"),
        "the substituted value must be percent-encoded (the authorize URL puts it in the query string): $authorizeUrl",
      )

      val callbackUrl =
        URLDecoder.decode(authorizeUrl.substringAfter("redirect_uri=").substringBefore('&'), Charsets.UTF_8)
      val uri = URI(callbackUrl)
      assertEquals("127.0.0.1", uri.host, callbackUrl)

      // 浏览器按这个地址回来：值里塞一个会被误解码的 %26
      val origin = callbackUrl.substringBeforeLast('/')
      val target = callbackUrl.removePrefix(origin) + "?code=4808dfb7&state=probe%26x"
      assertEquals(200, request(origin, target))

      assertEquals(
        "$origin$target",
        login.await(),
        "the plugin receives the exact URL the browser requested: verbatim, not decoded (decoding is the plugin's job, segment by segment)",
      )
    }

  /** 直接打原始 HTTP，request-target 原样写出（HTTP 客户端会重新编码 `%26`，量不到原样）。 */
  private fun request(
    origin: String,
    target: String,
  ): Int {
    val uri = URI(origin)
    Socket(uri.host, uri.port).use { socket ->
      socket.soTimeout = REQUEST_TIMEOUT_MS
      socket.getOutputStream().apply {
        write("GET $target HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n".toByteArray())
        flush()
      }
      val response = socket.getInputStream().readBytes().decodeToString()
      val statusLine = response.substringBefore("\r\n")
      return statusLine.split(' ')[1].toInt()
    }
  }

  /** `Settings` 写入等它落盘再读回 —— 不 `await` 的话下一次读会跟它抢，落盘顺序就不确定了。 */
  @Test(timeout = 120000)
  fun settingsBridgeRoundTripsOnAwait() {
    assertEquals(
      "client-1",
      evalAsync(
        """
        await Settings.set("probe.client_id", "client-1");
        return await Settings.get("probe.client_id");
        """.trimIndent(),
      ),
      "the write must be awaited -- without await the next read races it and the persist order is undefined",
    )
  }

  /** `Settings` 收到 `null` 就是删键：读回是 null，不是空串也不是 undefined。 */
  @Test(timeout = 120000)
  fun settingsBridgeDeletesOnNull() {
    val afterDelete =
      evalAsync(
        """
        await Settings.set("probe.session", "{}");
        await Settings.set("probe.session", null);
        return await Settings.get("probe.session");
        """.trimIndent(),
      )
    assertNull(
      afterDelete,
      "a null value deletes the key, so reading back yields null (not an empty string, not undefined)",
    )
  }

  /** 非字符串原样过桥、宿主收窄失败 ⇒ Promise 被拒绝。 */
  @Test(timeout = 120000)
  fun settingsBridgeRejectsNonStrings() {
    // 非字符串原样过桥，宿主收窄失败 ⇒ Promise 被拒绝
    assertEquals(
      "caught",
      evalAsync(
        """
        try {
          await Settings.set("probe.number", 42);
          return "no";
        } catch (e) {
          return "caught";
        }
        """.trimIndent(),
      ),
    )
  }

  /** `FormData` 的形状：`__js_proto__` / `__items__` 与每项的 name / value / type / filename。 */
  @Test(timeout = 120000)
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

  private companion object {
    const val REQUEST_TIMEOUT_MS = 10_000

    /** 授权链接的典型形状：query 里留 `{{callback}}` 占位符。 */
    const val TEMPLATE =
      "https://example.com/oauth/authorize?client_id=client-id&response_type=code" +
        "&state=probe123&redirect_uri={{callback}}"
  }

  // ---- WebView2 宿主端到端 ----

  /** 35 s 护栏。 */
  private suspend fun <T> guarded(block: suspend () -> T): T = withTimeout(35_000) { block() }

  /** 只拦 `/sub.js`：命中后拦截结果整份被采纳，主框架请求放行。 */
  @Test(timeout = 120_000)
  fun subresourceInterceptionCarriesTheRequestsOwnDetails() =
    runBlocking {
      TestEnv.skipUnlessHostAvailable()
      val seen = ArrayList<SeenRequest>()
      val result =
        guarded {
          JsEngine.instance.webviewAsync(
            url("/page.html"),
            null,
            null,
            { u, headers, method, isForMainFrame ->
              seen += SeenRequest(u, headers, method, isForMainFrame)
              // 只在子资源上命中，回被拦请求自己的 url + headers；主框架返回 false 放行
              if (u.endsWith("/sub.js")) mapOf("url" to u, "headers" to headers) else false
            },
          )
        }

      val seenSnapshot = seen.toList()
      val seenUrls = seenSnapshot.map { it.url }
      val hit =
        assertIs<Map<*, *>>(
          result,
          "the subresource /sub.js was not intercepted (result is $result). Seen: $seenUrls",
        )
      val hitUrl = assertIs<String>(hit["url"], "an interception result should carry back the intercepted request url")
      assertTrue(
        hitUrl.endsWith("/sub.js"),
        "the interception should hit the subresource: $hitUrl",
      )

      val main = seenSnapshot.firstOrNull { it.url.endsWith("/page.html") }
      assertTrue(main != null, "main-frame navigation should be visible; seen: ${seenSnapshot.map { it.url }}")
      assertTrue(main.isForMainFrame, "main-frame navigation must have isForMainFrame == true")

      val sub = seenSnapshot.first { it.url.endsWith("/sub.js") }
      assertEquals("GET", sub.method, "method must be the real HTTP method")
      assertTrue(!sub.isForMainFrame, "<script src> is not a main frame")
      assertTrue(
        sub.headers.isNotEmpty(),
        "subresource request headers should be real (WebView2's ICoreWebView2HttpRequestHeaders)",
      )
    }

  /** 全程放行：每个请求都看得见，`<script>` 与 `<img>` 都真的加载了，脚本拿回页面状态。 */
  @Test(timeout = 120_000)
  fun releasingEveryRequestLetsPageFinish() =
    runBlocking {
      TestEnv.skipUnlessHostAvailable()
      val releasedSeen = ArrayList<SeenRequest>()
      val released =
        guarded {
          JsEngine.instance.webviewAsync(
            url("/page.html"),
            null,
            "({ sub: !!window.__subLoaded, imgs: document.images.length })",
            { u, headers, method, isForMainFrame ->
              releasedSeen += SeenRequest(u, headers, method, isForMainFrame)
              false
            },
          )
        }

      // 回调被反复调用过，且主框架与子资源都到过
      val releasedSnapshot = releasedSeen.toList()
      val urls = releasedSnapshot.map { it.url }
      assertTrue(
        releasedSnapshot.any { it.url.endsWith("/page.html") && it.isForMainFrame },
        "the main-frame request should be in the callback: $urls",
      )
      assertTrue(
        releasedSnapshot.any { it.url.endsWith("/sub.js") && !it.isForMainFrame },
        "the <script> request should have been seen: $urls",
      )
      assertTrue(
        releasedSnapshot.size >= 2,
        "the callback should fire for every request, but it fired only ${releasedSnapshot.size} time(s): $urls",
      )

      // 全程放行 ⇒ 无命中 ⇒ 整轮以「脚本跑完」收场
      val json =
        assertIs<String>(
          released,
          "releasing everything must not produce an Intercepted outcome, got $released",
        )
      assertTrue(
        json.contains("\"sub\":true"),
        "after releasing, the <script> should really have executed (this is the evidence for the 'released' outcome): $json",
      )
      assertTrue(
        json.contains("\"imgs\":1"),
        "after releasing, the <img> subresource should also have been loaded into the DOM (proves releasing is not main-frame-only): $json",
      )
    }

  /** cookie 跨任务共享：两次任务各建自己的 environment，但共用同一份 user data folder。 */
  @Test(timeout = 120000)
  fun cookiesAreSharedAcrossTasks() =
    runBlocking {
      TestEnv.skipUnlessHostAvailable()

      val wrote =
        guarded {
          JsEngine.instance.webviewAsync(
            url("/page.html"),
            null,
            "document.cookie = 'webview_probe=1; path=/; max-age=3600'; " +
              "document.cookie.includes('webview_probe=1')",
            null,
          )
        }
      assertEquals(
        "true",
        assertIs<String>(wrote, "the cookie writer did not take the script branch: $wrote"),
        "the cookie should be writable",
      )

      val read =
        guarded {
          JsEngine.instance.webviewAsync(url("/page.html"), null, "document.cookie.includes('webview_probe=1')", null)
        }
      assertEquals(
        "true",
        assertIs<String>(read, "the cookie reader did not take the script branch: $read"),
        "another view should see the cookie written by the previous view -- otherwise the two are not sharing one store",
      )
    }
}
