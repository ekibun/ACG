async (_java) => {
  class FormData {
    constructor(data) {
      const _data = data || {}
      this.__js_proto__ = "FormData";
      this.__items__ = Object.keys(_data).map((k) => ({
        name: k,
        value: _data[k].filename ? _data[k].value : _data[k],
        filename: _data[k].filename
      }));
    }

    append(name, value, filename) {
      // __items__ 是普通数组，没有 append（那是 FormData 自己的方法）。
      this.__items__.push({ name, value, filename })
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
      const index = this.__items__.findIndex((v) => v.name === name);
      if (index < 0) {
        this.append(name, value, filename);
        return;
      }
      // 原来写的是 this.splice(...)，但 splice 在 __items__ 上，不在 FormData 上
      this.__items__.splice(index, 1, { name, value, filename });
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

  class _TextEncoder {
    constructor(encoding, options) {
      this.encoding = "" + (encoding || "utf-8");
    }
    encode(data) {
      return _java(null, 'encode', data, this.encoding)
    }
  }

  class _TextDecoder {
    constructor(encoding, options) {
      this.encoding = "" + (encoding || "utf-8");
    }
    decode(data) {
      return _java(null, 'decode', data, this.encoding)
    }
  }

  class Response {
    constructor(response) {
      response = response || {};
      this.headers = response.headers;
      this.ok = response.ok;
      this.redirected = response.redirected;
      this.status = response.status;
      this.url = response.url;
      this.body = response.body;
      // TODO `type`, `useFinalURL`, `bodyUsed`
    }

    clone() {
      return new Response(this)
    }

    async arrayBuffer() {
      return this.body;
    }

    async text(utfLabel) {
      return new _TextDecoder(utfLabel || "utf-8").decode(this.body);
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
      this.headers = options.headers || request.headers || {};
      this.body = options.body || request.body;
      this.redirect = options.redirect || request.redirect || "follow";
      // TODO `mode`, `credentials`, `cache`, `referrer`, `referrerPolicy`, `integrity`, `redirect`
    }
  }

  // toString(16) 不补零：0x0a 会得到 "a"，URL 编码必须写成 "%0a"。
  const encodeURI_hex = (encoder, c) => [...new Uint8Array(encoder.encode(c))]
    .map(v => "%" + v.toString(16).padStart(2, "0"))
    .join("").toUpperCase();

  /**
   * 后台 WebView —— 对应 BangumiPlugin 的 `modules/http.js#__webview__`。
   *
   * 那个工程里它是个 `require("http")` 出来的模块；这里**内联**，原因是 QuickJS
   * 的动态 `import()` 在本工程的 JNI 桥上会把进程打崩（实测两种路径都 abort）：
   * 模块不存在时命中 `assert(js_rc(p)->ref_count == 0)`（quickjs.c:6425），
   * 模块存在时又在 `JS_FreeRuntime` 命中
   * `assert(list_empty(&rt->gc_obj_list))`（quickjs.c:2464）。
   * 所以这里没有对应的 `files/js/module/webview.js`。init.js 里其它能力
   * （fetch / TextEncoder / FormData）本来也都是内联的，与之一致。
   *
   * 与参照实现只差一件事：那边是**阻塞**等待（`while(!finished) sleep(1000)`），
   * 这里返回 Promise。QuickJS 只有一个事件循环，阻塞会把整个引擎锁死 ——
   * 脚本从那边搬过来时，把 `var ret = __webview__(...)` 换成 `await webview(...)`。
   *
   * 用法（位置参数与 http.js 完全一致）：
   *
   *   // 1) 拦一个请求：命中即中止加载，把 url+headers 交回脚本自己去 fetch
   *   const req = await webview(url, { "User-Agent": ua }, null, (request) =>
   *     request.headers.Range ? { url: request.url, headers: request.headers } : null);
   *   const buf = await (await fetch(req.url, { headers: req.headers })).arrayBuffer();
   *
   *   // 2) 跑页面 JS 取值：script 的返回值（JSON）解析后交回
   *   const data = await webview(url, {}, "JSON.stringify(window.__NUXT__)");
   */
  const webview = async (url, header, script, onInterceptRequest) => {
    const ret = await _java(
      null,
      "webviewAsync",
      "" + (url || ""),
      header || {},
      script == null ? null : "" + script,
      typeof onInterceptRequest === "function" ? onInterceptRequest : null
    );
    if (!ret || typeof ret !== "object") return undefined;

    // Kotlin 侧用这个键区分「命中拦截」与「脚本返回值」；两者都可能是任意对象。
    if (ret.__webview_kind__ === "intercept") return ret.value;

    if (ret.__webview_kind__ === "script") {
      const json = ret.value;
      // 空串/null 表示脚本没有返回值 —— 对齐 http.js 的 `it = it && JSON.parse(it)`。
      if (json == null || json === "") return undefined;
      try {
        return JSON.parse(json);
      } catch (e) {
        // 不是 JSON（脚本返回裸文本）时原样给出，比丢掉强。
        return json;
      }
    }

    return undefined;
  };

  const globalProperties = {
    TextEncoder: _TextEncoder,
    TextDecoder: _TextDecoder,
    Request,
    Response,
    FormData,
    console: {
      log: (...args) => {
        _java(null, "console", "log", args);
      },
      info: (...args) => {
        _java(null, "console", "info", args);
      },
      debug: (...args) => {
        _java(null, "console", "debug", args);
      },
      error: (...args) => {
        _java(null, "console", "error", args);
      }
    },
    fetch: async (input, init) => {
      const response = await _java(null, "fetchAsync", new Request(input, init));
      return new Response(response);
    },
    webview,
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
