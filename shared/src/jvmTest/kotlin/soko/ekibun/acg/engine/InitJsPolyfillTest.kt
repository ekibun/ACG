package soko.ekibun.acg.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.TestEnv
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `init.js` 的浏览器兼容层，按各规范的可判定行为写断言。
 *
 * 只走真 `JsEngine` 派发；不验内部结构、宿主输出格式、私有接线或实现相关的时序。
 * 依据：HTML（btoa/atob、window/self、timers）、URL Standard、Console Standard、
 * Fetch Standard（Headers / FormData）、ECMAScript（encodeURI / encodeURIComponent）。
 * 宿主能力的派发另由 `JsEngineTest` 覆盖。
 */
class InitJsPolyfillTest {
  @BeforeTest
  fun prepare() {
    TestEnv.ensurePaths()
  }

  @AfterTest
  fun reset() {
    runBlocking { JsEngine.instance.reset() }
  }

  private fun eval(body: String): Any? =
    runBlocking {
      val deferred =
        assertIs<Deferred<Any?>>(
          JsEngine.instance.evaluate("(async () => { $body })()", "<polyfill>"),
        )
      deferred.await()
    }

  private fun evalMap(body: String): Map<*, *> = assertIs<Map<*, *>>(eval(body))

  /** 求值并收下宿主 stdout，仅用于 `console.assert` 的条件语义。 */
  private fun evalCapturing(body: String): Pair<Map<*, *>, String> {
    val buffer = ByteArrayOutputStream()
    val original = System.out
    val capture = PrintStream(buffer, true, "UTF-8")
    System.setOut(capture)
    val out =
      try {
        evalMap(body)
      } finally {
        System.setOut(original)
        capture.close()
      }
    return out to buffer.toString("UTF-8")
  }

  @Test(timeout = 120000)
  fun base64RoundTripsWithPadding() {
    val out =
      evalMap(
        """
        const raw = String.fromCharCode(0, 255);
        const binary = atob(btoa(raw));
        return {
          simple: btoa('hi') + '|' + atob('aGk='),
          padding: btoa('a') + '|' + atob('YQ=='),
          binary: binary.charCodeAt(0) + ',' + binary.charCodeAt(1),
          empty: btoa('') + '|' + atob(''),
        };
        """.trimIndent(),
      )
    assertEquals("aGk=|hi", out["simple"], "btoa/atob round-trip ASCII")
    assertEquals("YQ==|a", out["padding"], "padding bytes survive")
    assertEquals("0,255", out["binary"], "a binary string holds one byte per character")
    assertEquals("|", out["empty"], "empty input stays empty")
  }

  @Test(timeout = 120000)
  fun windowAndSelfPointAtTheGlobalObject() {
    assertEquals(true, eval("return window === self && self === globalThis;"))
  }

  @Test(timeout = 120000)
  fun urlResolvesRelativeReferencesAndNormalizes() {
    val out =
      evalMap(
        """
        const u = new URL('../c?q=2#y', 'https://user:pw@EXAMPLE.com:8080/a/b/');
        const before = u.href;
        u.searchParams.append('x', '1');
        return {
          href: before,
          host: u.host,
          origin: u.origin,
          pathname: u.pathname,
          linked: u.search,
          defaultPort: new URL('https://x.com:443/a').href,
          nonAscii: new URL('https://x.com/中文?q=空 格').href,
          doubleSlash: new URL('https://x.com/a//b/../c').pathname,
          protocolRelative: new URL('//cdn.x.com/z', 'https://a.com/p').href,
          root: new URL('https://x.com').href,
          ipv6: new URL('http://[::1]:8080/p').href,
        };
        """.trimIndent(),
      )
    assertEquals("https://user:pw@example.com:8080/a/c?q=2#y", out["href"])
    assertEquals("example.com:8080", out["host"])
    assertEquals("https://example.com:8080", out["origin"])
    assertEquals("/a/c", out["pathname"])
    assertEquals("?q=2&x=1", out["linked"], "searchParams writes back to search")
    assertEquals("https://x.com/a", out["defaultPort"], "default port is dropped")
    assertEquals("https://x.com/%E4%B8%AD%E6%96%87?q=%E7%A9%BA%20%E6%A0%BC", out["nonAscii"])
    assertEquals("/a//c", out["doubleSlash"], "empty path segments are preserved")
    assertEquals("https://cdn.x.com/z", out["protocolRelative"])
    assertEquals("https://x.com/", out["root"])
    assertEquals("http://[::1]:8080/p", out["ipv6"])
  }

  @Test(timeout = 120000)
  fun urlSearchParamsImplementsFormEncodingAndMutation() {
    val out =
      evalMap(
        """
        const p = new URLSearchParams({ a: "!~*'() x", b: 'a+b' });
        const multi = new URLSearchParams('a=1&a=2&b=3');
        const getAll = multi.getAll('a').join(',');
        const get = multi.get('b');
        const has = multi.has('c');
        const afterSet = (() => { multi.set('a', '9'); return multi.toString(); })();
        const afterDelete = (() => { multi.delete('b'); return multi.toString(); })();
        const sorted = (() => { const q = new URLSearchParams('b=2&a=1'); q.sort(); return q.toString(); })();
        return {
          form: p.toString(),
          iteration: [...p].map(([k, v]) => k + '=' + v).join(';'),
          getAll,
          get,
          has,
          afterSet,
          afterDelete,
          sorted,
        };
        """.trimIndent(),
      )
    assertEquals("a=%21%7E*%27%28%29+x&b=a%2Bb", out["form"], "urlencoded set is narrower than encodeURIComponent")
    assertEquals("a=!~*'() x;b=a+b", out["iteration"])
    assertEquals("1,2", out["getAll"])
    assertEquals("3", out["get"])
    assertEquals(false, out["has"])
    assertEquals("a=9&b=3", out["afterSet"], "set collapses repeated keys")
    assertEquals("a=9", out["afterDelete"])
    assertEquals("a=1&b=2", out["sorted"])
  }

  @Test(timeout = 120000)
  fun timersRunOnceCancelAndRepeat() {
    val out =
      evalMap(
        """
        globalThis.__log = [];
        setTimeout((v) => __log.push(v), 20, 'x');
        const cancelled = setTimeout(() => __log.push('cancelled'), 20);
        const idType = typeof cancelled;
        clearTimeout(cancelled);
        let ticks = 0;
        const interval = setInterval(() => {
          ticks++;
          if (ticks >= 3) clearInterval(interval);
        }, 10);
        await new Promise((resolve) => setTimeout(resolve, 200));
        return {
          once: __log.filter((v) => v === 'x').length,
          cancelled: __log.includes('cancelled'),
          repeated: ticks,
          idType,
        };
        """.trimIndent(),
      )
    assertEquals(1L, out["once"], "setTimeout fires its callback once")
    assertEquals(false, out["cancelled"], "clearTimeout prevents the callback")
    assertEquals(3L, out["repeated"], "clearInterval stops further ticks")
    assertEquals("number", out["idType"], "a timer handle is a number")
  }

  @Test(timeout = 120000)
  fun timerCallbackFailureDoesNotStopInterval() {
    val out =
      evalMap(
        """
        globalThis.__hits = 0;
        const interval = setInterval(async () => {
          __hits++;
          if (__hits === 1) throw new Error('boom');
          clearInterval(interval);
        }, 10);
        await new Promise((resolve) => setTimeout(resolve, 150));
        return { hits: __hits };
        """.trimIndent(),
      )
    assertEquals(2L, out["hits"], "a rejected callback must not cancel later ticks")
  }

  @Test(timeout = 120000)
  fun consoleExposesLevelsAndHonorsAssertCondition() {
    val (out, printed) =
      evalCapturing(
        """
        console.assert(true, 'hidden');
        console.assert(false, 'boom');
        return {
          levels: ['log', 'info', 'warn', 'error', 'debug', 'assert']
            .map((n) => typeof console[n]).join('|'),
          writable: Object.getOwnPropertyDescriptor(console, 'log').writable,
        };
        """.trimIndent(),
      )
    assertEquals("function|function|function|function|function|function", out["levels"])
    assertEquals(true, out["writable"], "console operations are configurable per WebIDL")
    assertFalse(printed.contains("hidden"), "a true assertion produces no output")
    assertTrue(printed.contains("boom"), "a false assertion reports its data")
  }

  @Test(timeout = 120000)
  fun headersCombineOnReadAndKeepRepeatedValues() {
    val out =
      evalMap(
        """
        const h = new Headers([
          ['Content-Type', 'a'], ['Content-Type', 'b'],
          ['Set-Cookie', 's1'], ['Set-Cookie', 's2'],
        ]);
        const copy = new Headers(h);
        const fromObject = new Headers({ 'X-Test': '1' });
        return {
          get: h.get('CONTENT-TYPE'),
          cookies: h.getSetCookie().join('|'),
          has: h.has('content-type'),
          copy: copy.get('set-cookie'),
          fromObject: fromObject.get('x-test'),
          afterAppend: (() => {
            const q = new Headers(); q.append('A', '1'); q.append('A', '2'); return q.get('a');
          })(),
          afterSet: (() => {
            const q = new Headers([['A', '1'], ['A', '2']]); q.set('A', '9'); return q.get('a');
          })(),
          afterDelete: (() => {
            const q = new Headers([['A', '1']]); q.delete('A'); return q.has('a');
          })(),
          keys: [...h.keys()].join(','),
          entries: [...h].map(([k, v]) => k + '=' + v).join(';'),
        };
        """.trimIndent(),
      )
    assertEquals("a, b", out["get"], "get combines repeated values with a comma")
    assertEquals("s1|s2", out["cookies"], "getSetCookie keeps each value")
    assertEquals(true, out["has"])
    assertEquals("s1, s2", out["copy"], "copying a Headers keeps its values")
    assertEquals("1", out["fromObject"], "a record is a valid HeadersInit")
    assertEquals("1, 2", out["afterAppend"], "append retains repeated values")
    assertEquals("9", out["afterSet"], "set replaces all values for a name")
    assertEquals(false, out["afterDelete"])
    assertEquals("content-type,set-cookie", out["keys"], "keys are sorted lowercase")
    assertEquals("content-type=a, b;set-cookie=s1, s2", out["entries"], "iteration sorts and combines")
  }

  @Test(timeout = 120000)
  fun uriEncodingKeepsReservedCharactersAndUsesTwoDigitEscapes() {
    val out =
      evalMap(
        """
        return {
          space: encodeURIComponent('a b'),
          chinese: encodeURIComponent('中'),
          uri: encodeURI('https://x.com/a?b=1#c'),
          lf: encodeURIComponent(String.fromCharCode(10)),
        };
        """.trimIndent(),
      )
    assertEquals("a%20b", out["space"])
    assertEquals("%E4%B8%AD", out["chinese"])
    assertEquals("https://x.com/a?b=1#c", out["uri"])
    assertEquals("%0A", out["lf"], "each byte uses exactly two hex digits")
  }

  @Test(timeout = 120000)
  fun formDataImplementsTheNamedValueApi() {
    val out =
      evalMap(
        """
        const fd = new FormData();
        fd.append('a', '1');
        fd.append('a', '2');
        fd.append('b', '3');
        const get = fd.get('a');
        const all = fd.getAll('a').join(',');
        const keys = [...fd.keys()].join(',');
        const has = fd.has('b');
        const afterSet = (() => { fd.set('a', '9'); return fd.getAll('a').join(','); })();
        const afterDelete = (() => { fd.delete('b'); return fd.has('b'); })();
        return { get, all, keys, has, afterSet, afterDelete };
        """.trimIndent(),
      )
    assertEquals("1", out["get"], "get returns the first value for a name")
    assertEquals("1,2", out["all"], "getAll keeps repeated values")
    assertEquals("a,a,b", out["keys"])
    assertEquals(true, out["has"])
    assertEquals("9", out["afterSet"], "set collapses repeated names")
    assertEquals(false, out["afterDelete"])
  }
}
