// 能力桥入口：_binding(method, args) 通用派发，args 为实参数组。
async (_binding) => {
  // 把 FormData 项映射为 { name, value, filename, type } 四个键，type 仅在带 filename 时给出。
  const _formItem = (name, value, filename) => ({
    name,
    value,
    filename,
    type: filename == null ? null : "application/octet-stream"
  });

  class FormData {
    constructor(data) {
      const _data = data || {}
      this.__js_proto__ = "FormData";
      this.__items__ = Object.keys(_data).map((k) => _formItem(
        k,
        _data[k].filename ? _data[k].value : _data[k],
        _data[k].filename
      ));
    }

    append(name, value, filename) {
      this.__items__.push(_formItem(name, value, filename))
    }

    delete(name) {
      const index = this.__items__.findIndex((v) => v.name === name);
      if (index < 0) return;
      this.__items__.splice(index, 1);
    }

    entries() {
      const items = this.__items__;
      return (function* () {
        for (const item of items)
          yield [item.name, item.value, item.filename];
      })();
    }

    get(name) {
      const found = this.__items__.find((v) => v.name === name);
      return found ? found.value : null;
    }

    getAll(name) {
      return this.__items__
        .filter((v) => v.name === name)
        .map((v) => v.value);
    }

    has(name) {
      return this.__items__.some((v) => v.name === name);
    }

    keys() {
      const items = this.__items__;
      return (function* () {
        for (const item of items)
          yield item.name;
      })();
    }

    set(name, value, filename) {
      let written = false;
      const items = [];
      for (const item of this.__items__) {
        if (item.name !== name) {
          items.push(item);
          continue;
        }
        if (!written) {
          items.push(_formItem(name, value, filename));
          written = true;
        }
      }
      if (!written) items.push(_formItem(name, value, filename));
      this.__items__ = items;
    }

    values() {
      const items = this.__items__;
      return (function* () {
        for (const item of items)
          yield item.value;
      })();
    }

    forEach(callback, thisArg) {
      for (const [name, value] of this.entries())
        callback.call(thisArg, value, name, this);
    }

    [Symbol.iterator]() {
      return this.entries();
    }
  };

  class Headers {
    constructor(init) {
      this.__items__ = Object.create(null);
      if (init == null) return;
      if (init instanceof Headers) {
        for (const key of Object.keys(init.__items__)) this.__items__[key] = init.__items__[key].slice();
        return;
      }
      if (typeof init !== "string" && typeof init[Symbol.iterator] === "function") {
        for (const pair of init) {
          if (!pair || pair.length < 2) throw new TypeError("Each header pair must be [name, value]");
          this.append(pair[0], pair[1]);
        }
        return;
      }
      for (const key of Object.keys(init)) {
        this.__items__[key.toLowerCase()] =
          Array.isArray(init[key]) ? init[key].map(String) : [String(init[key])];
      }
    }

    append(name, value) {
      const key = String(name).toLowerCase();
      const values = this.__items__[key] || [];
      values.push(String(value));
      this.__items__[key] = values;
    }

    set(name, value) {
      this.__items__[String(name).toLowerCase()] =
        Array.isArray(value) ? value.map(String) : [String(value)];
    }

    get(name) {
      const values = this.__items__[String(name).toLowerCase()];
      return values ? values.join(", ") : null;
    }

    getSetCookie() {
      return (this.__items__["set-cookie"] || []).slice();
    }

    has(name) {
      return this.__items__[String(name).toLowerCase()] !== undefined;
    }

    delete(name) {
      delete this.__items__[String(name).toLowerCase()];
    }

    keys() {
      return Object.keys(this.__items__).sort()[Symbol.iterator]();
    }

    values() {
      return Object.keys(this.__items__).sort()
        .map((key) => this.__items__[key].join(", "))[Symbol.iterator]();
    }

    entries() {
      return Object.keys(this.__items__).sort()
        .map((key) => [key, this.__items__[key].join(", ")]);
    }

    [Symbol.iterator]() {
      return this.entries()[Symbol.iterator]();
    }

    forEach(callback, thisArg) {
      for (const [name, value] of this) callback.call(thisArg, value, name, this);
    }
  }

  class _TextEncoder {
    constructor(encoding, options) {
      this.encoding = "" + (encoding || "utf-8");
    }
    encode(data) {
      return _binding('encode', [data, this.encoding])
    }
  }

  class _TextDecoder {
    constructor(encoding, options) {
      this.encoding = "" + (encoding || "utf-8");
    }
    decode(data) {
      return _binding('decode', [data, this.encoding])
    }
  }

  class Response {
    constructor(response) {
      response = response || {};
      this.headers = new Headers(response.headers);
      this.ok = response.ok;
      this.redirected = response.redirected;
      this.status = response.status;
      this.url = response.url;
      this.bodyUsed = false;
      const rsp = response._opaque;
      this.arrayBuffer = async () => {
        if(this.bodyUsed) throw new TypeError("body stream already read");
        this.bodyUsed = true;
        // _binding 返回 Deferred<ByteArray>（桥约定见 ability-bridge.md），需 await 取字节数组。
        return await _binding('Response.arrayBuffer', [rsp]);
      }
      // TODO `type`, `useFinalURL`
    }

    async text(utfLabel) {
      // decode 是同步桥，需先把 arrayBuffer() 的结果 await 成字节数组。
      const buf = await this.arrayBuffer();
      return new _TextDecoder(utfLabel || "utf-8").decode(buf);
    }

    async json(utfLabel) {
      return JSON.parse(await this.text(utfLabel));
    }
  }

  class Request {
    constructor(input, init) {
      const options = init || {};
      const request = typeof input === "string" ? { url: input } : input;
      this.url = request.url;
      this.method = options.method || request.method || "GET";
      this.headers = new Headers(options.headers || request.headers);
      this.body = options.body || request.body;
      this.redirect = options.redirect || request.redirect || "follow";
      // TODO `mode`, `credentials`, `cache`, `referrer`, `referrerPolicy`, `integrity`, `redirect`
    }
  }

  // 按字节做百分号编码，每字节固定两位十六进制。
  const encodeURI_hex = (encoder, c) => [...new Uint8Array(encoder.encode(c))]
    .map(v => "%" + v.toString(16).padStart(2, "0"))
    .join("").toUpperCase();

  // options 归一后整体过桥；契约见 docs/design/quickjs-ability-bridge.md §七。
  const webview = async (url, options = {}) => {
    const source = options || {};
    const ret = await _binding("webview", [
      "" + (url || ""),
      {
        headers: source.headers || {},
        script: source.script == null ? null : "" + source.script,
        // 假值放行；真值序列化后交给宿主，命中即中止本次加载并把它作为 webview() 的结果
        onInterceptRequest:
          typeof source.onInterceptRequest === "function"
            ? (request) => {
                const decision = source.onInterceptRequest(request);
                if (decision == null || decision === false) return false;
                return JSON.stringify(decision);
              }
            : null,
        show: !!source.show,
        // 布尔值就是「取消 / 继续」；其他返回值序列化后交给宿主，等于「这次导航就是要的结果」
        onNavigate:
          typeof source.onNavigate === "function"
            ? (target) => {
                const decision = source.onNavigate(target);
                if (typeof decision === "boolean") return decision;
                if (decision == null) return false;
                return JSON.stringify(decision);
              }
            : null,
        // UA 默认由宿主注入（与 fetch 同一个串），这里只是显式覆盖的口子
        userAgent: typeof source.userAgent === "string" ? source.userAgent : null
      }
    ]);
    if (ret == null) return undefined;
    if (typeof ret === "string") {
      if (ret === "") return undefined;
      return JSON.parse(ret);
    }
    if (typeof ret === "object") return ret;
    return undefined;
  };

  // base64 字母表；QuickJS 没有 atob / btoa，cheerio 这类库在缺 atob 时会走 Node 的 Buffer
  const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  // 二进制字符串（每字符一个字节）→ base64。
  const _btoa = (value) => {
    const text = `${value}`;
    const bytes = [];
    for (let i = 0; i < text.length; i++) bytes.push(text.charCodeAt(i) & 0xff);
    let out = "";
    for (let i = 0; i < bytes.length; i += 3) {
      const a = bytes[i], b = bytes[i + 1], c = bytes[i + 2];
      out += B64.charAt(a >> 2);
      out += B64.charAt(((a & 3) << 4) | ((b === undefined ? 0 : b) >> 4));
      out += b === undefined ? "=" : B64.charAt(((b & 15) << 2) | ((c === undefined ? 0 : c) >> 6));
      out += c === undefined ? "=" : B64.charAt(c & 63);
    }
    return out;
  };

  // base64 → 二进制字符串；非法字符丢掉，等价于浏览器的宽松做法。
  const _atob = (value) => {
    const text = `${value}`;
    let clean = "";
    for (let i = 0; i < text.length; i++) {
      const ch = text.charAt(i);
      if (B64.indexOf(ch) >= 0 || ch === "=") clean += ch;
    }
    let out = "";
    for (let i = 0; i < clean.length; i += 4) {
      const a = B64.indexOf(clean.charAt(i));
      const b = B64.indexOf(clean.charAt(i + 1));
      const c = clean.charAt(i + 2) === "=" ? 0 : B64.indexOf(clean.charAt(i + 2));
      const d = clean.charAt(i + 3) === "=" ? 0 : B64.indexOf(clean.charAt(i + 3));
      out += String.fromCharCode((a << 2) | (b >> 4));
      if (clean.charAt(i + 2) !== "=" && clean.charAt(i + 2) !== "") {
        out += String.fromCharCode(((b & 15) << 4) | (c >> 2));
      }
      if (clean.charAt(i + 3) !== "=" && clean.charAt(i + 3) !== "") {
        out += String.fromCharCode(((c & 3) << 6) | d);
      }
    }
    return out;
  };

  // WHATWG URL 常用子集：只支持 scheme://，不做 IDN 与 data:/mailto:。
  const DEFAULT_PORTS = { "http:": "80", "https:": "443", "ws:": "80", "wss:": "443", "ftp:": "21" };
  const URL_RE = /^([A-Za-z][A-Za-z0-9+.-]*:)?(?:\/\/([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/;

  const utf8Bytes = (ch) => {
    const code = ch.codePointAt(0);
    if (code < 0x80) return [code];
    if (code < 0x800) return [0xc0 | (code >> 6), 0x80 | (code & 0x3f)];
    if (code < 0x10000) return [0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f)];
    return [0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f)];
  };

  // keep 是该位置原样保留的字符；表单值里的 % 是字面量，不能沿用已有的 %XX。
  const percentEncode = (value, keep, keepEscapes = true) => {
    const text = String(value);
    let out = "";
    for (let i = 0; i < text.length; i++) {
      const code = text.codePointAt(i);
      const ch = String.fromCodePoint(code);
      if (code > 0xffff) i++;
      if (/^[A-Za-z0-9\-._~]$/.test(ch) || keep.indexOf(ch) >= 0) {
        out += ch;
        continue;
      }
      if (keepEscapes && ch === "%" && /^[0-9A-Fa-f]{2}$/.test(text.slice(i + 1, i + 3))) {
        out += text.slice(i, i + 3);
        i += 2;
        continue;
      }
      for (const byte of utf8Bytes(ch)) out += "%" + byte.toString(16).padStart(2, "0").toUpperCase();
    }
    return out;
  };

  const formEncode = (value) => percentEncode(value, "*", false).replace(/%20/g, "+").replace(/~/g, "%7E");
  const formDecode = (value) => {
    const text = String(value).replace(/\+/g, " ");
    try {
      return decodeURIComponent(text);
    } catch {
      return text;
    }
  };

  // 中间空段要保留：/a//b 不能塌成 /a/b。
  const normalizePath = (path) => {
    const absolute = path.startsWith("/");
    const segments = path.split("/").slice(absolute ? 1 : 0);
    const last = segments.length - 1;
    const trailing = last >= 0 && (segments[last] === "" || segments[last] === "." || segments[last] === "..");
    const parts = [];
    for (let i = 0; i <= last; i++) {
      const part = segments[i];
      if (part === "." || (part === "" && (i === 0 || i === last))) continue;
      if (part === "..") {
        parts.pop();
        continue;
      }
      parts.push(part);
    }
    let out = (absolute ? "/" : "") + parts.join("/");
    if (trailing && !out.endsWith("/")) out += "/";
    return out || (absolute ? "/" : "");
  };

  class URLSearchParams {
    constructor(init = "") {
      this.__items__ = [];
      if (typeof init === "string") {
        const text = init.startsWith("?") ? init.slice(1) : init;
        for (const pair of text.split("&")) {
          if (pair === "") continue;
          const at = pair.indexOf("=");
          this.__items__.push([
            formDecode(at < 0 ? pair : pair.slice(0, at)),
            formDecode(at < 0 ? "" : pair.slice(at + 1)),
          ]);
        }
      } else if (init && typeof init[Symbol.iterator] === "function") {
        for (const pair of init) {
          if (!pair || pair.length < 2) throw new TypeError("Each query pair must be [name, value]");
          this.append(pair[0], pair[1]);
        }
      } else if (init && typeof init === "object") {
        for (const key of Object.keys(init)) this.append(key, init[key]);
      }
    }

    append(name, value) {
      this.__items__.push([String(name), String(value)]);
      this.__sync__();
    }

    delete(name) {
      this.__items__ = this.__items__.filter(([key]) => key !== name);
      this.__sync__();
    }

    get(name) {
      const found = this.__items__.find(([key]) => key === name);
      return found ? found[1] : null;
    }

    getAll(name) {
      return this.__items__.filter(([key]) => key === name).map(([, value]) => value);
    }

    has(name) {
      return this.__items__.some(([key]) => key === name);
    }

    set(name, value) {
      let written = false;
      const items = [];
      for (const [key, current] of this.__items__) {
        if (key !== name) {
          items.push([key, current]);
          continue;
        }
        if (!written) {
          items.push([name, String(value)]);
          written = true;
        }
      }
      if (!written) items.push([name, String(value)]);
      this.__items__ = items;
      this.__sync__();
    }

    sort() {
      this.__items__.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
      this.__sync__();
    }

    forEach(callback, thisArg) {
      for (const [key, value] of this.__items__) callback.call(thisArg, value, key, this);
    }

    keys() {
      return this.__items__.map(([key]) => key)[Symbol.iterator]();
    }

    values() {
      return this.__items__.map(([, value]) => value)[Symbol.iterator]();
    }

    entries() {
      return this.__items__.map(([key, value]) => [key, value])[Symbol.iterator]();
    }

    [Symbol.iterator]() {
      return this.entries();
    }

    toString() {
      return this.__items__.map(([key, value]) => formEncode(key) + "=" + formEncode(value)).join("&");
    }

    __bind__(url) {
      this.__url__ = url;
    }

    __sync__() {
      if (this.__url__) this.__url__.search = this.toString();
    }
  }

  class URL {
    constructor(input, base) {
      this.__parse__(String(input), base == null ? null : (base instanceof URL ? base : new URL(String(base))));
    }

    __params__() {
      const params = new URLSearchParams(this.search);
      params.__bind__(this);
      this.searchParams = params;
    }

    __parse__(input, base) {
      const match = URL_RE.exec(input);
      if (!match) throw new TypeError("Invalid URL");
      let protocol = match[1] ? match[1].toLowerCase() : null;
      let authority = match[2];
      let path = match[3] || "";
      let query = match[4];
      const fragment = match[5];
      if (!protocol) {
        if (!base) throw new TypeError("Invalid URL");
        protocol = base.protocol;
        if (authority == null) {
          authority = base.__authorityPart__();
          if (path === "") {
            path = base.pathname;
            if (query === undefined) query = base.__record__.query;
          } else if (!path.startsWith("/")) {
            path = base.pathname.replace(/[^/]*$/, "") + path;
          }
        }
      }
      if (authority == null || !Object.prototype.hasOwnProperty.call(DEFAULT_PORTS, protocol)) {
        throw new TypeError("Invalid URL");
      }
      if (path === "") path = "/";
      const at = authority.lastIndexOf("@");
      const userinfo = at < 0 ? "" : authority.slice(0, at);
      const hostport = at < 0 ? authority : authority.slice(at + 1);
      let host = hostport;
      let port = "";
      if (hostport.startsWith("[")) {
        const close = hostport.indexOf("]");
        if (close < 0) throw new TypeError("Invalid URL");
        host = hostport.slice(0, close + 1);
        if (hostport[close + 1] === ":") port = hostport.slice(close + 2);
      } else {
        const colon = hostport.lastIndexOf(":");
        if (colon >= 0 && /^[0-9]*$/.test(hostport.slice(colon + 1))) {
          host = hostport.slice(0, colon);
          port = hostport.slice(colon + 1);
        }
      }
      host = percentEncode(host.toLowerCase(), "[]:!$&'()*+,;=");
      if (port === DEFAULT_PORTS[protocol]) port = "";
      const colon = userinfo.indexOf(":");
      this.__record__ = {
        protocol,
        username: percentEncode(colon < 0 ? userinfo : userinfo.slice(0, colon), "!$&'()*+,;="),
        password: colon < 0 ? "" : percentEncode(userinfo.slice(colon + 1), ":!$&'()*+,;="),
        host,
        port,
        pathname: percentEncode(normalizePath(path), "/:@!$&'()*+,;="),
        query: query === undefined ? null : percentEncode(query, "/?:@!$&'()*+,;="),
        fragment: fragment === undefined ? null : percentEncode(fragment, "/?:@!$&'()*+,;="),
      };
      this.__params__();
    }

    __authorityPart__() {
      const record = this.__record__;
      const user = record.username
        ? record.username + (record.password ? ":" + record.password : "") + "@"
        : "";
      return user + record.host + (record.port ? ":" + record.port : "");
    }

    get href() {
      return this.__record__.protocol + "//" + this.__authorityPart__() + this.pathname + this.search + this.hash;
    }

    set href(value) {
      this.__parse__(String(value), null);
    }

    get origin() {
      const record = this.__record__;
      return record.protocol + "//" + record.host + (record.port ? ":" + record.port : "");
    }

    get protocol() { return this.__record__.protocol; }
    get username() { return this.__record__.username; }
    get password() { return this.__record__.password; }
    get host() { return this.__record__.host + (this.__record__.port ? ":" + this.__record__.port : ""); }
    get hostname() { return this.__record__.host; }
    get port() { return this.__record__.port; }
    get pathname() { return this.__record__.pathname; }
    set pathname(value) {
      this.__record__.pathname = percentEncode(normalizePath(String(value)), "/:@!$&'()*+,;=");
    }
    get search() { return this.__record__.query === null ? "" : "?" + this.__record__.query; }
    set search(value) {
      const text = String(value);
      this.__record__.query = text === ""
        ? null
        : percentEncode(text.startsWith("?") ? text.slice(1) : text, "/?:@!$&'()*+,;=");
      this.__params__();
    }
    get hash() { return this.__record__.fragment === null ? "" : "#" + this.__record__.fragment; }
    set hash(value) {
      const text = String(value);
      this.__record__.fragment = text === ""
        ? null
        : percentEncode(text.startsWith("#") ? text.slice(1) : text, "/?:@!$&'()*+,;=");
    }
    toString() { return this.href; }
    toJSON() { return this.href; }
  }

  const timerTable = new Map();
  let nextTimerId = 1;
  const sleep = (ms) => _binding("delay", [ms]);

  const scheduleTimer = (callback, ms, args, repeat) => {
    if (typeof callback !== "function") throw new TypeError("timer callback must be a function");
    const id = nextTimerId++;
    const timer = { cancelled: false };
    timerTable.set(id, timer);
    const period = Math.max(Number(ms) || 0, repeat ? 1 : 0);
    (async () => {
      do {
        await sleep(period);
        if (timer.cancelled) return;
        if (!repeat) timerTable.delete(id);
        try {
          await callback(...args);
        } catch (e) {
          console.error(e);
        }
      } while (repeat && !timer.cancelled);
    })();
    return id;
  };

  const cancelTimer = (id) => {
    const timer = timerTable.get(id);
    if (!timer) return;
    timer.cancelled = true;
    timerTable.delete(id);
  };

  const consoleObject = {
    assert: (condition, ...args) => {
      if (condition) return;
      _binding('console', ['assert', args.length === 0 ? ['Assertion failed'] : args]);
    }
  };
  ['log', 'info', 'warn', 'error', 'debug'].forEach((level) => {
    consoleObject[level] = (...args) => {
      _binding('console', [level, args]);
    };
  });

  const globalProperties = {
    atob: _atob,
    btoa: _btoa,
    URL,
    URLSearchParams,
    // 引导函数的 this 就是全局对象；浏览器向库靠 window / self 判断环境
    window: this,
    self: this,
    TextEncoder: _TextEncoder,
    TextDecoder: _TextDecoder,
    Headers,
    Request,
    Response,
    Cookies: {
      get: (url) => _binding('Cookies.get', [url]),
      // 兑现 = 写入命令已排进宿主线程队列（JNI 是 void + post），不是落盘完成；连写要 await 才有顺序
      set: (url, cookie) => _binding('Cookies.set', [url, cookie])
    },
    FormData,
    console: consoleObject,
    setTimeout: (callback, ms, ...args) => scheduleTimer(callback, ms, args, false),
    setInterval: (callback, ms, ...args) => scheduleTimer(callback, ms, args, true),
    clearTimeout: cancelTimer,
    clearInterval: cancelTimer,
    fetch: async (input, init) => {
      const request = new Request(input, init);
      const options = Object.assign({}, request, { headers: request.headers.__items__ });
      const response = await _binding('fetch', [options]);
      return new Response(response);
    },
    webview,
    // 用系统浏览器打开任意 URL；不等回调。
    openBrowser: (url) => _binding("openBrowser", ["" + (url || "")]),
    // {{callback}} 换成本机会话地址，等回跳返回完整 URL；见 docs/design/browser-login.md。
    oauth: (url) => _binding("oauth", ["" + (url || "")]),
    // 扁平字符串键值，值为 null 即删键；读写均需 await。
    Settings: {
      get: (key) => _binding("Settings.get", [key]),
      set: (key, value) => _binding("Settings.set", [key, value])
    },
    encodeURI: (uri, encoding) => {
      const encoder = new _TextEncoder(encoding || "utf-8");
      return `${uri}`.replace(/[^a-zA-Z0-9-_.!~*'();/?:@&=+$,#]/g, (c) => encodeURI_hex(encoder, c));
    },
    encodeURIComponent: (uri, encoding) => {
      const encoder = new _TextEncoder(encoding || "utf-8");
      return `${uri}`.replace(/[^a-zA-Z0-9-_.!~*'()]/g, (c) => encodeURI_hex(encoder, c));
    }
  }

  Object.defineProperties(this, Object.assign({},
    ...Object.keys(globalProperties).map((key) => ({
      [key]: {
        value: globalProperties[key],
        writable: false
      }
    }))));
};
