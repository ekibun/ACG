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
      // 追加一项到 __items__。
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
      // 原地替换 __items__ 中的同名项。
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
      this.headers = options.headers || request.headers || {};
      this.body = options.body || request.body;
      this.redirect = options.redirect || request.redirect || "follow";
      // TODO `mode`, `credentials`, `cache`, `referrer`, `referrerPolicy`, `integrity`, `redirect`
    }
  }

  // 按字节做百分号编码，每字节固定两位十六进制。
  const encodeURI_hex = (encoder, c) => [...new Uint8Array(encoder.encode(c))]
    .map(v => "%" + v.toString(16).padStart(2, "0"))
    .join("").toUpperCase();

  /**
   * 后台 WebView：按 url 打开页面，可带请求头、注入并执行脚本、拦截请求，返回 Promise。
   *
   * 参数依次为 url、请求头、脚本、拦截回调：
   *
   *   // 1) 拦一个请求：回调返回真值即中止加载，并把被拦下请求的 url+headers 交回脚本
   *   const req = await webview(url, { "User-Agent": ua }, null,
   *     (request) => !!request.headers.Range);
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
    // 按返回值类型解析：null 为无脚本 / 脚本无返回值，字符串为脚本返回值（JSON），对象为命中的拦截。
    if (ret == null) return undefined;            // 无脚本 / 脚本无返回值
    if (typeof ret === "string") {               // 脚本返回值（JSON 字符串）
      if (ret === "") return undefined;
      return JSON.parse(ret);
    }
    if (typeof ret === "object") return ret;      // 命中拦截，{url, headers} 原样
    return undefined;
  };

  const globalProperties = {
    TextEncoder: _TextEncoder,
    TextDecoder: _TextDecoder,
    Request,
    Response,
    Cookies: {
      get: (url) => {
        return _binding('Cookies.get', [url]);
      },
      set: (url, cookie) => {
        _binding('Cookies.set', [url, cookie]);
      }
    },
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
    /**
     * 打开系统浏览器做登录：`url` 为授权地址，回调地址处用占位符 `{{callback}}` 标出，替换为
     * `http://127.0.0.1:<端口>/<会话id>`（百分号编码形式，直接放在 redirect_uri 上）：
     *
     *   const cb = await openBrowser("https://bgm.tv/oauth/authorize?client_id=…&redirect_uri={{callback}}");
     *   // cb = "http://127.0.0.1:51987/9f3a4b…?code=4808dfb7…&state=probe123"
     *
     * 返回浏览器实际请求的完整 URL（含查询串、原样未解码）；参数逐段 `decodeURIComponent`，
     * 换 token 的 redirect_uri 回传 `cb.split("?")[0]`。
     */
    openBrowser: (url) => _binding("openBrowser", ["" + (url || "")]),
    /**
     * 扁平的字符串设置，落盘在平台的设置文件里：
     *
     *   await Settings.get("bgm.client_id");      // 没有该键则 null
     *   await Settings.set("bgm.client_id", "…"); // 写
     *   await Settings.set("bgm.session", null);  // 值为 null 即删键
     *
     * 只接受字符串，值传 null 表示删键；两个方法都是异步的，写也需 await。
     */
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
