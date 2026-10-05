// 能力桥只有一个：_binding(method, args) —— 通用派发，args 是那一份实参（不是位置参数）
async (_binding) => {
  // FormData 的每一项在 Kotlin 侧被 `Http` 拆成 multipart 的一个 part，
  // 认的是 `name` / `value` / `type` / `filename` 四个键 —— 这里**只给带
  // filename 的项写 type**，因为只有文件 part 才有 Content-Type
  // （`Http` 那边 `type is String && value is ByteArray` 才走文件分支）。
  // 键名必须与 `Http` 侧逐字一致，少一个字母就静默降级成普通字段。
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
      // __items__ 是普通数组，没有 append（那是 FormData 自己的方法）。
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
      const index = this.__items__.findIndex((v) => v.name === name);
      if (index < 0) {
        this.append(name, value, filename);
        return;
      }
      // splice 只能用在 __items__ 上，不在 FormData 上（this.splice 会静默无效）
      this.__items__.splice(index, 1, _formItem(name, value, filename));
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
      this.headers = response.headers;
      this.ok = response.ok;
      this.redirected = response.redirected;
      this.status = response.status;
      this.url = response.url;
      this.bodyUsed = false;
      const rsp = response._opaque;
      this.arrayBuffer = async () => {
        if(this.bodyUsed) throw new TypeError("body stream already read");
        this.bodyUsed = true;
        // `await` 不能省：`_binding` 落到 Kotlin 的能力桥，返回的是
        // `Deferred<ByteArray>`（桥约定，见 ability-bridge.md），JS 侧 await  才拿到
        // 字节数组。少这个 await 的话 `decode` 收到的是 `CompletableDeferredImpl`，
        // 强转 `ByteArray` 当场抛 ClassCastException（实测，见 JsEngineDispatchTest）。
        return await _binding('Response.arrayBuffer', [rsp]);
      }
      // TODO `type`, `useFinalURL`
    }

    async text(utfLabel) {
      // `decode` 是**同步**桥（直接收 ByteArray），所以 `arrayBuffer()` 的结果
      // 必须先 await 成字节数组 —— 传 Promise 进去会在 Kotlin 侧强转时炸。
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
    const ret = await _binding("webview", [
      "" + (url || ""),
      header || {},
      script == null ? null : "" + script,
      typeof onInterceptRequest === "function" ? onInterceptRequest : null
    ]);
    if (!ret || typeof ret !== "object") return undefined;

    // Kotlin 侧用这个键区分「命中拦截」与「脚本返回值」；两者都可能是任意对象。
    if (ret.__webview_kind__ === "intercept") return ret.value;

    if (ret.__webview_kind__ === "script") {
      const json = ret.value;
      // 空串/null 表示脚本没有返回值 —— 对齐 http.js 的 `it = it && JSON.parse(it)`。
      // 没有「非 JSON 就原样返回」的兜底：宿主给的 json 一定是 JSON
      // （WebView2 的 ExecuteScript 自己序列化，裸文本会被加引号变合法 JSON，
      // 压根到不了这里），而 `webview.cpp` 在没有脚本时回的是空串。
      if (json == null || json === "") return undefined;
      return JSON.parse(json);
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
        _binding('console', ['log', args]);
      },
      info: (...args) => {
        _binding('console', ['info', args]);
      },
      debug: (...args) => {
        _binding('console', ['debug', args]);
      },
      error: (...args) => {
        _binding('console', ['error', args]);
      }
    },
    fetch: async (input, init) => {
      const response = await _binding('fetch', [new Request(input, init)]);
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
