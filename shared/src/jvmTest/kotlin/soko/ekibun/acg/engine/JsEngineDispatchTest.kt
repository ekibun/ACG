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
 * 能力桥的**真派发**回归测试：走真 [JsEngine]、**不注入任何桩**。
 *
 * 本文件是 `init.js` 那条 JS 桥的**唯一**测试面 —— 桩顶替 `_binding` 的测法验不到
 * 派发形状（桩用 `getOrNull(i)` + 静默默认值，形状错了不响），所以这里逐个分支锁住
 * **参数形状**：`args[0]` 是什么、`args[1]` 是什么。
 * 判据与踩坑取证见 `docs/design/quickjs-ability-bridge.md`。
 *
 * [WebViewHostTest] 补的是另一半：它直接调同包的 [webviewAsync] 驱动、
 * 绕开 JS 桥，验 WebView2 宿主本身的行为。
 * **它证明不了派发改对了** —— `args` 的下标写错时它照样全绿。
 *
 * 两条容易写错的前提，改测试前先读：
 *
 * - **下标错位是「响」的，不是静默的**（类型对不上就 `ClassCastException`）。
 *   真正静默的是别的东西：`JSInvokable` companion 的 `block` 若写成
 *   `thisVal?.block(arrayOf(argv))`，`argv` 就被多包一层 —— 而桩测试都自己实现
 *   [soko.ekibun.quickjs.JSInvokable] 接口、直接拿 `argv`，那种错全套件无感。
 *   **桩证明不了派发的形状。**
 * - **只锁行为，不锁源码文本。** 真破坏本来就会自己响（把引导函数形参删掉 ⇒ 8 条全红），
 *   文本断言是给已经会响的东西再加一层响；而「换个设计」（多一个桥、给形参改名）
 *   不是缺陷，为它判红是越界。设计约定归 `ability-bridge.md` 的检查清单。
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
    // JsEngine 自己持有 runtime（`quickjsDelegate`），不 reset 就留下一整个 runtime
    // 与它的归属线程。泄漏报告要能当判据用（0 条是判据成立的前提），
    // 就不能容它有常驻噪音。
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

  /**
   * `encode`：`args[0]` 是待编码的串、`args[1]` 是 charset 名。
   *
   * 这条最容易被下标错位伤到 —— **两个都是字符串**，错位了类型照样过，只会编出另一串
   * 字节（或让 `Charset.forName` 抛异常）。
   *
   * `encodeURIComponent('\n')` 走的是真`encode` 分支（`init.js` 里它拿
   * `encoder.encode(c)` 的字节补零成 `%XX`），所以断言 `%0A` 就等于断言
   * 「`args[1]` 的 charset 真的生效、字节真的回来了」。
   */
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

  /**
   * `decode`：`args[0]` 是字节、`args[1]` 是 charset 名，方向与 `encode` 相反。
   *
   * 走 `TextDecoder.decode` 这条链：`encode` 的结果（字节）要能原样喂回 `decode`。
   */
  @Test
  fun decodeBranchIsReachable() {
    assertEquals(
      "\u4e2d\u6587abc",
      evalAsync(
        """return new TextDecoder('utf-8').decode(new TextEncoder().encode('\u4e2d\u6587abc'));""",
      ),
    )
  }

  /**
   * `console`：`args[0]` 是 level、`args[1]` 是**实参数组整体**作为 `args` 的一个元素。
   *
   * 写成 `['log', a, b]` 的话，`console(type, data: List<Any?>)` 会收到 `b` 而不是数组。
   * 这条验的是**分支可达**：下标错位会落到 `else` 上抛 `JSError`
   * （`console(type, data)` 两个形参类型不同，错位必然炸，不会静默）。
   *
   * 形状（`args[0]`=level、`args[1]`=**实参数组整体**作为 `args` 的一个元素）
   * 由 `init.js` 侧的构造保证 —— 真派发这边看不到 stdout 的结构，所以锁不了。
   * 别指望别的测试兜住它：桩式的注入根本看不到 `argv`，形参个数写错也不响。
   */
  @Test
  fun consoleBranchIsReachable() {
    assertEquals(1L, evalAsync("""console.log('dispatch', 'ok'); return 1;"""))
  }

  /**
   * `fetch`：`args[0]` 是 [soko.ekibun.acg.engine.Request] 展开的 `Map`
   *（**不是**位置参数），Kotlin 侧拿到的是 `args[0] as Map<Any, Any?>`。
   *
   * 顺带锁住响应字段：`status` / `ok` / `url` / `body` 都得真的过来 ——
   * `init.js` 的 `Response` 构造器只做字段搬运，缺一个就静默成 `undefined`。
   */
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

  /**
   * `Response.arrayBuffer` 是**独立的一条桥**（不走 `fetch` 内部），参数是
   * `[_opaque]` —— 即 `fetch` 回的那个 [soko.ekibun.common.Http.Response] 包装。
   *
   * 锁两件事：
   * 1. **能真读到 body**（`arrayBuffer()` 必须 `await` 那个 `Deferred` ——
   *    少一个 await 就会把 `CompletableDeferredImpl` 喂给 `decode`，当场
   *    `ClassCastException`，这是实测症状）；
   * 2. **`bodyUsed` 语义**：`fetch` 只建会话不读 body，所以 `bodyUsed` 初始必须是
   *    `false`（能被读）；读过之后同一对象再读一次必须抛 `TypeError`。
   */
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

  /**
   * `webview`：`args[0..3]` 依次是 url / header / script / onInterceptRequest。
   *
   * **本条是那 4 行下标唯一的哨兵** —— 反向验证过：把 `args[1]` 串到 `args[2]`，
   * 本文件里只有这一条红。所以改那 4 行时它必须跟着走。
   *
   * 四个下标各自的错位症状（**实测出来的**，不是推的）：
   *
   * | 错位 | 症状 |
   * |---|---|
   * | `args[0]` ↔ `args[1]` | url 变成 header 的 Map ⇒ 导航直接坏 |
   * | `args[1]` ↔ `args[2]` | 强转 `ClassCastException`（**响**，见下） |
   * | `args[2]` ↔ `args[3]` | 回调类型不匹配（`String?` ↔ `JSFunction?`）⇒ 强转 `ClassCastException` |
   *
   * 1↔2 那行两种传参**都响**：带 script 时 `args[2]` 的 String 撞 `Map?`；
   * 不带 script 时 `args[1]` 的 Map 撞 `String?` —— `init.js` 里 `header || {}`
   * 保证它永不为 null，所以不存在「header 是 null 就静默过去」这条缝。
   *
   * 所以**这条不是靠「不报错」活着的**（那 4 行下标写错都会当场抛），它真正锁的是
   * **实参有没有真的按位送到** —— 所以除了解禁结果，还断言 `User-Agent` 真到了页面
   * （`pageRequests` 里查）：这一条对「header 被换成别的东西」敏感。
   *
   * 回调返回 `{ url, headers }` 这类对象：命中判定是「非 null 且非 false」，命中后**返回对象
   * 被整体采用** —— 回传的就是它自己，连它塞进去的 `Range` 也在（断言里那条 `has-range`
   * 钉的就是这个）。要回「被拦下请求自己」的 url / headers，就自己拼成对象返回。
   */
  @Test
  fun webviewBranchIsReachable() {
    skipUnlessHostIsAvailable(WebViewHostTest.hostHealth)

    // 只在子资源上命中：主框架要放行，页面才有机会去请求 <script>。
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

    // args[1]（header）真的送到了 —— 下标 1↔2 串位时这条会红，而上面那条不会。
    val ua = pageRequests.firstOrNull()?.get("User-agent")?.firstOrNull()
    assertEquals("dispatch-test", ua, "webview headers must reach the page via args[1] (pageRequests=$pageRequests)")

    assertTrue(
      pageRequests.size >= 1,
      "a main-frame request should have reached the local server (proves WebView2 really navigated)",
    )
  }

  /**
   * `webview` 的**空脚本**分支：不传 script 时宿主回空串，JS 侧要给出 `undefined`。
   *
   * 这条覆盖的是「没传脚本」时宿主那一支：由 Kotlin 侧直接发
   * `ScriptFinished(json = null)`，不是 JSON。
   *
   * 断言只锁 `=== undefined` 这个**可观测结果**，不关心 `init.js` 是从哪个分支出来的：
   * `!ret` / `ret.value == null` / `json === ""` 三条路都收敛到 `undefined`，
   * 而这正是要验的语义。
   */
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

  /**
   * `webview` 失败必须 **reject**，而不是回一个让脚本误判的空值。
   *
   * 用一个导航不了的端口逼出 [JSError]（[webviewAsync] 直接抛）⇒ JS 侧 Promise 拒绝。
   *
   * **只断言「拒绝了」不断言文案**：失败文案是平台给的（`导航失败（WebView2
   * 错误码 …）` / 超时 / 起不来），锁死它等于把测试焊死在这台机器的 WebView2 版本上。
   */
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

  /**
   * `openBrowser`：`args[0]` 是带 `{{callback}}` 占位符的授权链接，就这一个实参。
   *
   * **只走模板校验那一支**：真跑到底会去开系统浏览器（桌面是 `Desktop.browse`，
   * Android 是 Custom Tabs），那是测试不能碰的动作 —— 所以这里给的模板故意没有占位符，
   * `launch` 根本到不了。
   *
   * 断言**只锁「`args[0]` 到了 Kotlin 侧」**，不锁整句文案：文案与「`launch` 一次都没调」
   * 这两件事由 [BrowserLoginTest] 负责（那边注入 `launch`，能看见它到底调没调；这里看不见）。
   * 越界 / 类型不符同样会 reject，所以真正要验的是「拒绝的理由里带着那个 URL」。
   */
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

  /**
   * `FormData` 的形状 —— 它一次都不调 `_binding`，是纯 JS 类，但**跨语言**：
   * `__js_proto__` / `__items__` / 每项的 `name` / `value` / `type` / `filename`
   * 是 `soko.ekibun.common.Http` 构造 multipart 时读的**唯一**入口。
   *
   * 键名错一个字母的后果是**静默降级**：`filename` 写成 `fileName`，Kotlin 侧读到的
   * 就是 null，文件 part 照样发出去、只是没有文件名（HTTP 层面完全合法，
   * 服务端收不到文件名这件事不会引起任何异常）。
   *
   * `FormData` 一次都不调 `_binding`（纯 JS 类），所以「派发分支都绿」**不代表**
   * 它的形状有人验 —— 这条是它唯一的覆盖点。
   *
   * **脚本直接 `return` 对象、不走 `JSON.stringify`** —— 因为真派发的返回路径就是
   * `jsToJava`，它（`cxx/quickjs/quickjs.cpp`）把 JS 普通对象整图展开成
   * `LinkedHashMap`、数组展开成 `ObjectArray`。所以 Kotlin 侧看到的就是
   * `Map` / `Array`，**这正是 `Http.kt` 收到的东西**（走一遍 JSON 反而会把它
   * 降级成字符串、还把 `ArrayBuffer` 变成 `{}`）。顺带这条也钉住了跨语言的实际类型：
   * 文件项的 `value` 回到宿主是 `ArrayBuffer`（有 `byteLength`），不是字符串。
   */
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

    // 前三项：普通字段 —— 没有 filename 就没有 type，Kotlin 走 append(name, value)
    for (i in 0..2) {
      val it = assertIs<Map<*, *>>(items[i], "item ${i + 1} should be a plain field")
      assertEquals("<absent>", it["filename"], "a plain field must not have a filename key at all")
      assertEquals("<null>", it["type"], "only file parts carry a Content-Type, so a plain field's type should be null")
    }
    val a1 = assertIs<Map<*, *>>(items[0])
    assertEquals("a", a1["name"])
    assertEquals("1", a1["value"])

    // 带 filename 的那项：type 必须与 filename 同时出现，否则 Http 走不到文件分支
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

  /**
   * 宿主体检：真导航一次 `about:blank`。
   *
   * 理由与实现都归 [WebViewHostTest] 的 `checkHost`（那边有完整说明）：环境建得成但**引擎完全
   * 不动**是一种真实坏法 —— 连 `about:blank` 都到不了 `NavigationCompleted`。不体检就直跑则
   * 30s 超时加一堆看不懂的断言失败，纯属噪声。
   */
  private fun hostHealth(): String? = WebViewHostTest.hostHealth
}
